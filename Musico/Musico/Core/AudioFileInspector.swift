import AudioToolbox
import AVFoundation
import CoreMedia
import Foundation

struct AudioTechnicalInfo: Sendable, Equatable {
    var bitDepth: Int?
    var sampleRate: Double?
    var bitRateBps: Double?
    var channels: Int?
    var duration: TimeInterval?
    var fileSize: Int64

    var precisionDisplay: String {
        guard let bitDepth, bitDepth > 0 else { return "N/A" }
        return "\(bitDepth)-bit"
    }

    var frequencyDisplay: String {
        guard let sampleRate, sampleRate > 0 else { return "N/A" }
        let khz = sampleRate / 1000.0
        if abs(khz.rounded() - khz) < 0.001 {
            return "\(Int(khz.rounded())) kHz"
        }
        let oneDecimal = (khz * 10).rounded() / 10
        if abs(oneDecimal - khz) < 0.001 {
            return String(format: "%.1f kHz", khz)
        }
        return String(format: "%.2f kHz", khz)
    }

    var bitRateDisplay: String {
        guard let bitRateBps, bitRateBps > 0 else { return "N/A" }
        return "\(Int((bitRateBps / 1000.0).rounded())) kbps"
    }

    var channelsDisplay: String {
        guard let channels, channels > 0 else { return "N/A" }
        switch channels {
        case 1: return "1 ch (Mono)"
        case 2: return "2 ch (Stereo)"
        default: return "\(channels) ch"
        }
    }

    var fileSizeDisplay: String {
        guard fileSize > 0 else { return "N/A" }
        return ByteCountFormatter.string(fromByteCount: fileSize, countStyle: .file)
    }
}

enum AudioFileInspector {
    static func inspect(url: URL?, track: Track) async -> AudioTechnicalInfo {
        var info = AudioTechnicalInfo(
            bitDepth: nil,
            sampleRate: nil,
            bitRateBps: nil,
            channels: nil,
            duration: track.duration > 0 ? track.duration : nil,
            fileSize: track.fileSize
        )

        guard let url, FileManager.default.fileExists(atPath: url.path) else {
            return info
        }

        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = (attrs[.size] as? NSNumber)?.int64Value, size > 0 {
            info.fileSize = size
        }

        // 1. Direct FLAC STREAMINFO parse (gives exact bit depth, sample rate, and channels).
        if url.pathExtension.lowercased() == "flac", let flac = readFLACStreamInfo(url) {
            info.sampleRate = flac.sampleRate
            info.channels = flac.channels
            info.bitDepth = flac.bitDepth
            if let dur = flac.duration, dur > 0 {
                info.duration = dur
            }
        }

        // 2. AudioToolbox AudioFile inspection (ASBD & bitrate).
        var audioFileID: AudioFileID?
        if AudioFileOpenURL(url as CFURL, .readPermission, 0, &audioFileID) == noErr, let afid = audioFileID {
            defer { AudioFileClose(afid) }

            var asbd = AudioStreamBasicDescription()
            var asbdSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
            if AudioFileGetProperty(afid, kAudioFilePropertyDataFormat, &asbdSize, &asbd) == noErr {
                if info.sampleRate == nil, asbd.mSampleRate > 0 {
                    info.sampleRate = asbd.mSampleRate
                }
                if info.channels == nil, asbd.mChannelsPerFrame > 0 {
                    info.channels = Int(asbd.mChannelsPerFrame)
                }
                if info.bitDepth == nil, asbd.mBitsPerChannel > 0 {
                    info.bitDepth = Int(asbd.mBitsPerChannel)
                }
            }

            var bitRate: UInt32 = 0
            var bitRateSize = UInt32(MemoryLayout<UInt32>.size)
            if AudioFileGetProperty(afid, kAudioFilePropertyBitRate, &bitRateSize, &bitRate) == noErr, bitRate > 0 {
                info.bitRateBps = Double(bitRate)
            }

            if info.duration == nil || info.duration == 0 {
                var estDuration: Float64 = 0
                var durSize = UInt32(MemoryLayout<Float64>.size)
                if AudioFileGetProperty(afid, kAudioFilePropertyEstimatedDuration, &durSize, &estDuration) == noErr, estDuration > 0 {
                    info.duration = estDuration
                }
            }
        }

        // 3. AVURLAsset fallback for format descriptions & estimated data rate.
        let asset = AVURLAsset(url: url)
        if info.duration == nil || info.duration == 0, let dur = try? await asset.load(.duration) {
            let seconds = CMTimeGetSeconds(dur)
            if seconds.isFinite, seconds > 0 {
                info.duration = seconds
            }
        }
        if let audioTrack = (try? await asset.loadTracks(withMediaType: .audio))?.first {
            if let descs = try? await audioTrack.load(.formatDescriptions) {
                for desc in descs {
                    if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee {
                        if info.sampleRate == nil, asbd.mSampleRate > 0 {
                            info.sampleRate = asbd.mSampleRate
                        }
                        if info.channels == nil, asbd.mChannelsPerFrame > 0 {
                            info.channels = Int(asbd.mChannelsPerFrame)
                        }
                        if info.bitDepth == nil, asbd.mBitsPerChannel > 0 {
                            info.bitDepth = Int(asbd.mBitsPerChannel)
                        }
                    }
                }
            }
            if info.bitRateBps == nil || info.bitRateBps == 0 {
                if let rate = try? await audioTrack.load(.estimatedDataRate), rate > 0 {
                    info.bitRateBps = Double(rate)
                }
            }
        }

        // 4. Average bitrate fallback when exact bitrate is unavailable (e.g., FLAC / WAV).
        if (info.bitRateBps == nil || info.bitRateBps == 0),
           info.fileSize > 0,
           let dur = info.duration,
           dur > 0 {
            info.bitRateBps = Double(info.fileSize * 8) / dur
        }

        return info
    }

    private struct FLACStreamInfo {
        var sampleRate: Double
        var channels: Int
        var bitDepth: Int
        var duration: TimeInterval?
    }

    private static func readFLACStreamInfo(_ url: URL) -> FLACStreamInfo? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        func readBytes(_ count: Int) -> [UInt8]? {
            guard count >= 0, let data = try? handle.read(upToCount: count), data.count == count else { return nil }
            return [UInt8](data)
        }
        guard var magic = readBytes(4) else { return nil }
        if magic[0...2] == [0x49, 0x44, 0x33] {
            guard let rest = readBytes(6) else { return nil }
            let size = rest[2...5].reduce(0) { $0 << 7 | Int($1 & 0x7F) }
            guard (try? handle.seek(toOffset: UInt64(10 + size))) != nil, let next = readBytes(4) else { return nil }
            magic = next
        }
        guard magic == [0x66, 0x4C, 0x61, 0x43] else { return nil }
        guard let blockHeader = readBytes(4) else { return nil }
        let type = blockHeader[0] & 0x7F
        let length = Int(blockHeader[1]) << 16 | Int(blockHeader[2]) << 8 | Int(blockHeader[3])
        guard type == 0, length >= 18, let body = readBytes(length) else { return nil }

        // Bytes 10..17 hold sampleRate (20b), channels-1 (3b), bitsPerSample-1 (5b), totalSamples (36b).
        let sampleRate = (Int(body[10]) << 12) | (Int(body[11]) << 4) | (Int(body[12]) >> 4)
        let channels = ((Int(body[12]) >> 1) & 0x07) + 1
        let bitDepth = (((Int(body[12]) & 0x01) << 4) | (Int(body[13]) >> 4)) + 1
        let totalSamples = (UInt64(body[13] & 0x0F) << 32)
            | (UInt64(body[14]) << 24)
            | (UInt64(body[15]) << 16)
            | (UInt64(body[16]) << 8)
            | UInt64(body[17])
        let duration: TimeInterval? = (sampleRate > 0 && totalSamples > 0)
            ? Double(totalSamples) / Double(sampleRate)
            : nil
        return FLACStreamInfo(
            sampleRate: Double(sampleRate),
            channels: channels,
            bitDepth: bitDepth,
            duration: duration
        )
    }
}
