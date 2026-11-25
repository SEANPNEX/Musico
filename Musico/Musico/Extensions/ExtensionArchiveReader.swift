import Compression
import Foundation

enum ExtensionArchiveError: LocalizedError, Equatable {
    case invalidArchive(String)
    case unsafePath(String)
    case tooManyEntries(Int)
    case archiveTooLarge(Int)
    case unsupportedCompression(UInt16)
    case decompressionFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidArchive(let detail):
            L10n.trNonisolated("Invalid extension archive: %@", detail)
        case .unsafePath(let path):
            L10n.trNonisolated("Extension archive contains unsafe path “%@”.", path)
        case .tooManyEntries(let count):
            L10n.trNonisolated("Extension archive exceeds the %@ entry limit.", String(count))
        case .archiveTooLarge(let limitMB):
            L10n.trNonisolated("Extension archive exceeds the %@ MB uncompressed size limit.", String(limitMB))
        case .unsupportedCompression(let method):
            L10n.trNonisolated("Extension archive uses unsupported compression method (%@).", String(method))
        case .decompressionFailed(let name):
            L10n.trNonisolated("Failed to decompress “%@” from extension archive.", name)
        }
    }
}

/// Pure-Swift ZIP reader supporting stored (method 0) and raw-deflate (method 8) entries via `Compression`.
enum ExtensionArchiveReader {
    static let maxEntries = 512
    static let maxUncompressedBytes = 32 * 1024 * 1024
    static let maxArchiveBytes = 32 * 1024 * 1024

    private static let eocdSignature: UInt32 = 0x0605_4b50
    private static let centralDirSignature: UInt32 = 0x0201_4b50
    private static let localHeaderSignature: UInt32 = 0x0403_4b50
    private static let eocdMinSize = 22
    private static let centralDirHeaderSize = 46
    private static let localHeaderSize = 30
    private static let maxCommentLength = 65_535

    private struct CentralDirectoryEntry {
        var name: String
        var isDirectory: Bool
        var flags: UInt16
        var method: UInt16
        var crc32: UInt32
        var compressedSize: Int
        var uncompressedSize: Int
        var localHeaderOffset: Int
    }

    static func extractEntries(from archiveData: Data) throws -> [String: Data] {
        guard archiveData.count >= eocdMinSize else {
            throw ExtensionArchiveError.invalidArchive("Archive is too small.")
        }
        guard archiveData.count <= maxArchiveBytes else {
            throw ExtensionArchiveError.archiveTooLarge(maxArchiveBytes / (1024 * 1024))
        }

        let bytes = [UInt8](archiveData)
        let eocdOffset = try findEOCDOffset(in: bytes)
        let diskNumber = readUInt16(bytes, at: eocdOffset + 4)
        let cdDisk = readUInt16(bytes, at: eocdOffset + 6)
        guard diskNumber == 0, cdDisk == 0 else {
            throw ExtensionArchiveError.invalidArchive("Multi-disk ZIP archives are not supported.")
        }

        let entriesOnDisk = Int(readUInt16(bytes, at: eocdOffset + 8))
        let totalEntries = Int(readUInt16(bytes, at: eocdOffset + 10))
        let cdSize = Int(readUInt32(bytes, at: eocdOffset + 12))
        let cdOffset = Int(readUInt32(bytes, at: eocdOffset + 16))

        guard entriesOnDisk != 0xFFFF,
              totalEntries != 0xFFFF,
              cdSize != Int(UInt32.max),
              cdOffset != Int(UInt32.max) else {
            throw ExtensionArchiveError.invalidArchive("ZIP64 archives are not supported.")
        }
        guard entriesOnDisk == totalEntries else {
            throw ExtensionArchiveError.invalidArchive("Central directory entry count mismatch.")
        }
        guard totalEntries <= maxEntries else {
            throw ExtensionArchiveError.tooManyEntries(maxEntries)
        }
        guard cdOffset >= 0, cdSize >= 0, cdOffset + cdSize <= eocdOffset else {
            throw ExtensionArchiveError.invalidArchive("Central directory bounds are invalid.")
        }

        let entries = try parseCentralDirectory(
            bytes: bytes,
            offset: cdOffset,
            size: cdSize,
            expectedCount: totalEntries
        )

        var files: [String: Data] = [:]
        var runningUncompressedBytes = 0
        for entry in entries {
            guard !entry.isDirectory else { continue }
            guard runningUncompressedBytes + entry.uncompressedSize <= maxUncompressedBytes else {
                throw ExtensionArchiveError.archiveTooLarge(maxUncompressedBytes / (1024 * 1024))
            }
            let payload = try extractPayload(for: entry, bytes: bytes, centralDirOffset: cdOffset)
            runningUncompressedBytes += payload.count
            guard runningUncompressedBytes <= maxUncompressedBytes else {
                throw ExtensionArchiveError.archiveTooLarge(maxUncompressedBytes / (1024 * 1024))
            }
            if files[entry.name] != nil {
                throw ExtensionArchiveError.invalidArchive("Duplicate archive entry '\(entry.name)'.")
            }
            files[entry.name] = payload
        }
        return files
    }

    private static func findEOCDOffset(in bytes: [UInt8]) throws -> Int {
        let minOffset = max(0, bytes.count - eocdMinSize - maxCommentLength)
        var cursor = bytes.count - eocdMinSize
        while cursor >= minOffset {
            if readUInt32(bytes, at: cursor) == eocdSignature {
                let commentLength = Int(readUInt16(bytes, at: cursor + 20))
                if cursor + eocdMinSize + commentLength == bytes.count {
                    return cursor
                }
            }
            cursor -= 1
        }
        throw ExtensionArchiveError.invalidArchive("Missing End of Central Directory record.")
    }

    private static func parseCentralDirectory(
        bytes: [UInt8],
        offset: Int,
        size: Int,
        expectedCount: Int
    ) throws -> [CentralDirectoryEntry] {
        var cursor = offset
        let end = offset + size
        var parsed: [CentralDirectoryEntry] = []
        parsed.reserveCapacity(expectedCount)
        var totalClaimedBytes = 0

        for _ in 0..<expectedCount {
            guard cursor + centralDirHeaderSize <= end else {
                throw ExtensionArchiveError.invalidArchive("Truncated central directory entry.")
            }
            guard readUInt32(bytes, at: cursor) == centralDirSignature else {
                throw ExtensionArchiveError.invalidArchive("Invalid central directory signature.")
            }

            let flags = readUInt16(bytes, at: cursor + 8)
            guard (flags & 0x0001) == 0 else {
                throw ExtensionArchiveError.invalidArchive("Encrypted ZIP entries are not supported.")
            }
            let method = readUInt16(bytes, at: cursor + 10)
            guard method == 0 || method == 8 else {
                throw ExtensionArchiveError.unsupportedCompression(method)
            }

            let crc32 = readUInt32(bytes, at: cursor + 16)
            let compressedSize = Int(readUInt32(bytes, at: cursor + 20))
            let uncompressedSize = Int(readUInt32(bytes, at: cursor + 24))
            let fileNameLen = Int(readUInt16(bytes, at: cursor + 28))
            let extraLen = Int(readUInt16(bytes, at: cursor + 30))
            let commentLen = Int(readUInt16(bytes, at: cursor + 32))
            let localOffset = Int(readUInt32(bytes, at: cursor + 42))

            let headerTotal = centralDirHeaderSize + fileNameLen + extraLen + commentLen
            guard cursor + headerTotal <= end else {
                throw ExtensionArchiveError.invalidArchive("Central directory entry extends past directory bounds.")
            }

            let nameStart = cursor + centralDirHeaderSize
            let nameData = Data(bytes[nameStart..<(nameStart + fileNameLen)])
            guard let rawName = String(data: nameData, encoding: .utf8) else {
                throw ExtensionArchiveError.invalidArchive("Archive entry filename is not valid UTF-8.")
            }

            let isDirectory = rawName.hasSuffix("/")
            let cleanName = try validateAndNormalizeEntryPath(rawName, isDirectory: isDirectory)

            guard uncompressedSize <= maxUncompressedBytes else {
                throw ExtensionArchiveError.archiveTooLarge(maxUncompressedBytes / (1024 * 1024))
            }
            totalClaimedBytes += uncompressedSize
            guard totalClaimedBytes <= maxUncompressedBytes else {
                throw ExtensionArchiveError.archiveTooLarge(maxUncompressedBytes / (1024 * 1024))
            }

            parsed.append(CentralDirectoryEntry(
                name: cleanName,
                isDirectory: isDirectory,
                flags: flags,
                method: method,
                crc32: crc32,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize,
                localHeaderOffset: localOffset
            ))
            cursor += headerTotal
        }

        return parsed
    }

    static func validateAndNormalizeEntryPath(_ rawPath: String, isDirectory: Bool) throws -> String {
        guard !rawPath.isEmpty, !rawPath.contains("\0") else {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }
        guard !rawPath.hasPrefix("/"), !rawPath.hasPrefix("\\") else {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }
        if rawPath.count >= 2, rawPath[rawPath.index(rawPath.startIndex, offsetBy: 1)] == ":" {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }
        guard !rawPath.contains("\\") else {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }

        let parts = rawPath.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        for segment in parts where segment == ".." {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }

        var normalizedParts: [String] = []
        for (idx, segment) in parts.enumerated() {
            if segment.isEmpty {
                if isDirectory && idx == parts.count - 1 { continue }
                throw ExtensionArchiveError.unsafePath(rawPath)
            }
            if segment == "." {
                continue
            }
            normalizedParts.append(segment)
        }

        let joined = normalizedParts.joined(separator: "/")
        if !isDirectory && joined.isEmpty {
            throw ExtensionArchiveError.unsafePath(rawPath)
        }
        return joined
    }

    private static func extractPayload(
        for entry: CentralDirectoryEntry,
        bytes: [UInt8],
        centralDirOffset: Int
    ) throws -> Data {
        let localOffset = entry.localHeaderOffset
        guard localOffset >= 0, localOffset + localHeaderSize <= centralDirOffset else {
            throw ExtensionArchiveError.invalidArchive("Local file header offset out of bounds for '\(entry.name)'.")
        }
        guard readUInt32(bytes, at: localOffset) == localHeaderSignature else {
            throw ExtensionArchiveError.invalidArchive("Invalid local file header signature for '\(entry.name)'.")
        }

        let localNameLen = Int(readUInt16(bytes, at: localOffset + 26))
        let localExtraLen = Int(readUInt16(bytes, at: localOffset + 28))
        let dataStart = localOffset + localHeaderSize + localNameLen + localExtraLen
        let dataEnd = dataStart + entry.compressedSize
        guard dataStart >= localOffset + localHeaderSize, dataEnd <= centralDirOffset else {
            throw ExtensionArchiveError.invalidArchive("Compressed payload out of bounds for '\(entry.name)'.")
        }

        let output: Data
        switch entry.method {
        case 0:
            guard entry.compressedSize == entry.uncompressedSize else {
                throw ExtensionArchiveError.invalidArchive("Stored entry size mismatch for '\(entry.name)'.")
            }
            output = Data(bytes[dataStart..<dataEnd])

        case 8:
            if entry.uncompressedSize == 0 {
                output = Data()
            } else {
                guard entry.compressedSize > 0 else {
                    throw ExtensionArchiveError.decompressionFailed(entry.name)
                }
                // Allocate +1 byte to detect archives whose declared uncompressedSize is smaller than reality.
                let capacity = min(entry.uncompressedSize + 1, maxUncompressedBytes + 1)
                var buffer = [UInt8](repeating: 0, count: capacity)
                let decodedSize: Int = bytes.withUnsafeBytes { rawBuf in
                    guard let base = rawBuf.baseAddress else { return 0 }
                    let srcPtr = base.advanced(by: dataStart).assumingMemoryBound(to: UInt8.self)
                    return buffer.withUnsafeMutableBufferPointer { dstBuf in
                        guard let dstPtr = dstBuf.baseAddress else { return 0 }
                        return compression_decode_buffer(
                            dstPtr,
                            capacity,
                            srcPtr,
                            entry.compressedSize,
                            nil,
                            COMPRESSION_ZLIB
                        )
                    }
                }
                guard decodedSize == entry.uncompressedSize else {
                    throw ExtensionArchiveError.decompressionFailed(entry.name)
                }
                output = Data(buffer.prefix(decodedSize))
            }

        default:
            throw ExtensionArchiveError.unsupportedCompression(entry.method)
        }

        let computedCRC = crc32IEEE(output)
        guard computedCRC == entry.crc32 else {
            throw ExtensionArchiveError.decompressionFailed(entry.name)
        }
        return output
    }

    static func crc32IEEE(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            let idx = Int((crc ^ UInt32(byte)) & 0xFF)
            crc = (crc >> 8) ^ crc32Table[idx]
        }
        return crc ^ 0xFFFF_FFFF
    }

    private static let crc32Table: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    private static func readUInt16(_ bytes: [UInt8], at offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }

    private static func readUInt32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
    }
}
