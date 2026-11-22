import AVFoundation

/// Plays one file at a time through `player → converter → 10-band EQ → mixer`.
/// The converter turns each file's own format into one fixed stereo format, so the equalizer
/// never sees a format change (it rejects mono and surround layouts, and reconnecting it
/// between songs raises an exception).
/// Knows nothing about queues; `PlayerController` decides what to load next.
@MainActor
final class AudioEngine {
    private enum Backend {
        case localFile
        case remoteStream
    }

    /// Called when the loaded file plays through to its end (not on stop, seek or load).
    var onTrackEnded: (() -> Void)?
    var onStreamReady: ((TimeInterval) -> Void)?
    var onStreamBufferingChanged: ((Bool) -> Void)?
    var onStreamFailed: ((String) -> Void)?

    private(set) var duration: TimeInterval = 0
    private(set) var isPlaying = false

    private var backend: Backend = .localFile
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let converter = AVAudioMixerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: EQSettings.frequencies.count)
    private var file: AVAudioFile?
    private var avPlayer: AVPlayer?
    private var streamLoader: ChunkedCloudResourceLoader?
    private var streamObservers: [NSKeyValueObservation] = []
    private var streamEndObserver: NSObjectProtocol?
    private var streamReady = false
    private var pendingStreamSeek: TimeInterval?
    private let streamLoaderQueue = DispatchQueue(label: "com.musico.stream-loader")
    /// Frame the current (or next) schedule starts from; the playback position while paused.
    private var seekFrame: AVAudioFramePosition = 0
    /// Last position read from a running engine. Used when the system has already stopped the
    /// engine (phone call, route change) and it no longer reports a render time.
    private var lastKnownFrame: AVAudioFramePosition = 0
    private var isScheduled = false
    /// Frames rendered since the current schedule started, or nil when the engine is not rendering.
    /// A property so tests can stand in for a stopped engine.
    lazy var renderedFrames: () -> AVAudioFramePosition? = { [unowned self] in
        guard let nodeTime = player.lastRenderTime, let playerTime = player.playerTime(forNodeTime: nodeTime) else { return nil }
        return playerTime.sampleTime
    }
    /// Bumped whenever a schedule is abandoned, so its completion callback can be ignored.
    private var generation = 0
    private var configObserver: NSObjectProtocol?

    init() {
        engine.attach(player)
        engine.attach(converter)
        engine.attach(eq)
        let hardwareRate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        let chain = AVAudioFormat(standardFormatWithSampleRate: hardwareRate > 0 ? hardwareRate : 44100, channels: 2)
        engine.connect(converter, to: eq, format: chain)
        engine.connect(eq, to: engine.mainMixerNode, format: chain)
        for (index, band) in eq.bands.enumerated() {
            band.filterType = .parametric
            band.frequency = EQSettings.frequencies[index]
            band.bandwidth = 1.0
            band.gain = 0
            band.bypass = false
        }
        eq.bypass = true
        configObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.handleConfigurationChange() }
        }
    }

    deinit {
        if let configObserver { NotificationCenter.default.removeObserver(configObserver) }
        if let streamEndObserver { NotificationCenter.default.removeObserver(streamEndObserver) }
        streamObservers.forEach { $0.invalidate() }
        streamLoader?.cancelAll()
    }

    private var sampleRate: Double { file?.processingFormat.sampleRate ?? 44100 }

    private var currentFrame: AVAudioFramePosition {
        guard let file else { return 0 }
        guard isPlaying else { return seekFrame }
        if let rendered = renderedFrames() {
            lastKnownFrame = min(max(seekFrame + rendered, 0), file.length)
        }
        return lastKnownFrame
    }

    var isEngineRunning: Bool { engine.isRunning }

    var currentTime: TimeInterval {
        switch backend {
        case .localFile:
            return Double(currentFrame) / sampleRate
        case .remoteStream:
            if let pendingStreamSeek { return pendingStreamSeek }
            guard let avPlayer else { return 0 }
            let seconds = avPlayer.currentTime().seconds
            guard seconds.isFinite, seconds >= 0 else { return 0 }
            return duration > 0 ? min(seconds, duration) : seconds
        }
    }

    /// Opens a file and leaves it paused at the start.
    func load(url: URL) throws {
        unload()
        backend = .localFile
        let opened = try AVAudioFile(forReading: url)
        let format = opened.processingFormat
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: converter, format: format)
        file = opened
        duration = Double(opened.length) / format.sampleRate
    }

    /// Streams a remote track progressively via `ChunkedCloudResourceLoader` and `AVPlayer`.
    func loadStream(
        url: URL,
        headers: [String: String],
        contentLength: Int64?,
        fileExtension: String,
        estimatedDuration: TimeInterval
    ) {
        unload()
        backend = .remoteStream
        duration = max(estimatedDuration.isFinite ? estimatedDuration : 0, 0)
        streamReady = false
        pendingStreamSeek = nil

        let loader = ChunkedCloudResourceLoader(
            headers: headers,
            contentLength: contentLength,
            fileExtension: fileExtension
        )
        let assetURL = ChunkedCloudResourceLoader.resourceURL(for: url)
        let asset = AVURLAsset(url: assetURL)
        asset.resourceLoader.setDelegate(loader, queue: streamLoaderQueue)

        let item = AVPlayerItem(asset: asset)
        let remotePlayer = AVPlayer(playerItem: item)
        remotePlayer.automaticallyWaitsToMinimizeStalling = true

        streamLoader = loader
        avPlayer = remotePlayer

        let statusObs = item.observe(\.status, options: [.new]) { [weak self, weak item] _, _ in
            Task { @MainActor in
                guard let self, let item,
                      self.backend == .remoteStream,
                      self.avPlayer?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    let itemSeconds = item.duration.seconds
                    if item.duration.isNumeric, itemSeconds.isFinite, itemSeconds > 0 {
                        self.duration = itemSeconds
                    }
                    self.streamReady = true
                    if let pending = self.pendingStreamSeek {
                        self.pendingStreamSeek = nil
                        let target = CMTime(seconds: pending, preferredTimescale: 600)
                        item.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero, completionHandler: nil)
                    }
                    self.onStreamReady?(self.duration)
                case .failed:
                    let msg = item.error?.localizedDescription ?? L10n.tr("Could not resolve a playable audio stream for this track.")
                    self.isPlaying = false
                    self.onStreamFailed?(msg)
                default:
                    break
                }
            }
        }

        let timeControlObs = remotePlayer.observe(\.timeControlStatus, options: [.new]) { [weak self, weak remotePlayer] _, _ in
            Task { @MainActor in
                guard let self, let remotePlayer,
                      self.backend == .remoteStream,
                      self.avPlayer === remotePlayer else { return }
                switch remotePlayer.timeControlStatus {
                case .waitingToPlayAtSpecifiedRate:
                    self.onStreamBufferingChanged?(true)
                case .playing:
                    self.isPlaying = true
                    self.onStreamBufferingChanged?(false)
                case .paused:
                    self.onStreamBufferingChanged?(false)
                    if remotePlayer.rate == 0 {
                        self.isPlaying = false
                    }
                @unknown default:
                    break
                }
            }
        }

        streamObservers = [statusObs, timeControlObs]
        streamEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self, weak item] _ in
            MainActor.assumeIsolated {
                guard let self, let item,
                      self.backend == .remoteStream,
                      self.avPlayer?.currentItem === item else { return }
                self.isPlaying = false
                self.avPlayer?.seek(to: .zero)
                self.onTrackEnded?()
            }
        }
    }

    func unload() {
        abandonSchedule()
        file = nil
        teardownStream()
        backend = .localFile
        duration = 0
        seekFrame = 0
        isPlaying = false
        stopRendering()
    }

    private func teardownStream() {
        streamLoader?.cancelAll()
        streamLoader = nil
        streamObservers.forEach { $0.invalidate() }
        streamObservers.removeAll()
        if let streamEndObserver {
            NotificationCenter.default.removeObserver(streamEndObserver)
            self.streamEndObserver = nil
        }
        avPlayer?.pause()
        avPlayer?.replaceCurrentItem(with: nil)
        avPlayer = nil
        streamReady = false
        pendingStreamSeek = nil
    }

    /// Pauses the engine so the app is not kept awake rendering silence.
    private func stopRendering() {
        if engine.isRunning { engine.pause() }
    }

    func play() {
        switch backend {
        case .localFile:
            guard let file, !isPlaying else { return }
            if seekFrame >= file.length { seekFrame = 0 }
            do {
                if !engine.isRunning { try engine.start() }
            } catch {
                return
            }
            if !isScheduled { schedule(from: seekFrame) }
            player.play()
            isPlaying = true
        case .remoteStream:
            guard let avPlayer, !isPlaying else { return }
            if duration > 0 && currentTime >= duration - 0.1 {
                avPlayer.seek(to: .zero)
            }
            avPlayer.play()
            isPlaying = true
        }
    }

    func pause() {
        switch backend {
        case .localFile:
            if isPlaying {
                let frame = currentFrame
                abandonSchedule()
                seekFrame = frame
                isPlaying = false
            }
            stopRendering()
        case .remoteStream:
            avPlayer?.pause()
            isPlaying = false
        }
    }

    /// Moves the position, keeping the play/pause state. Seeking to the end while playing ends the track.
    func seek(to time: TimeInterval) {
        switch backend {
        case .localFile:
            guard let file else { return }
            let wasPlaying = isPlaying
            let target = AVAudioFramePosition((time.isFinite ? time : 0) * sampleRate)
            abandonSchedule()
            seekFrame = min(max(target, 0), file.length)
            guard wasPlaying else { return }
            if seekFrame >= file.length {
                isPlaying = false
                stopRendering()
                onTrackEnded?()
            } else {
                // The system may have stopped the engine without telling us yet; starting the
                // player node on a stopped engine raises an exception.
                if !engine.isRunning {
                    do { try engine.start() } catch {
                        isPlaying = false
                        return
                    }
                }
                schedule(from: seekFrame)
                player.play()
            }
        case .remoteStream:
            guard let avPlayer else { return }
            let finite = time.isFinite ? time : 0
            let clamped = duration > 0 ? min(max(finite, 0), duration) : max(finite, 0)
            if !streamReady {
                pendingStreamSeek = clamped
                return
            }
            pendingStreamSeek = nil
            if isPlaying && duration > 0 && clamped >= duration {
                isPlaying = false
                avPlayer.pause()
                onTrackEnded?()
                return
            }
            let cmTime = CMTime(seconds: clamped, preferredTimescale: 600)
            avPlayer.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    private func abandonSchedule() {
        generation += 1
        if isScheduled { player.stop() }
        isScheduled = false
    }

    private func schedule(from frame: AVAudioFramePosition) {
        guard let file, frame < file.length else { return }
        lastKnownFrame = frame
        player.scheduleSegment(file, startingFrame: frame, frameCount: AVAudioFrameCount(file.length - frame),
                               at: nil, completionCallbackType: .dataPlayedBack,
                               completionHandler: Self.completion(self, token: generation))
        isScheduled = true
    }

    /// Built outside the main actor's isolation: the audio thread calls it.
    private nonisolated static func completion(_ engine: AudioEngine, token: Int) -> AVAudioPlayerNodeCompletionHandler {
        { [weak engine] _ in
            Task { @MainActor in
                guard let engine, engine.generation == token else { return }
                engine.finishTrack()
            }
        }
    }

    private func finishTrack() {
        guard let file else { return }
        abandonSchedule()
        seekFrame = file.length
        isPlaying = false
        stopRendering()
        onTrackEnded?()
    }

    /// The output device changed (sample rate, route); the engine has stopped and must be restarted.
    private func handleConfigurationChange() {
        guard file != nil else { return }
        let wasPlaying = isPlaying
        let frame = currentFrame
        abandonSchedule()
        seekFrame = frame
        isPlaying = false
        if wasPlaying { play() }
    }

    // MARK: Equalizer

    func apply(_ settings: EQSettings) {
        for (index, band) in eq.bands.enumerated() {
            let gain = index < settings.gains.count ? settings.gains[index] : 0
            band.gain = min(max(gain, EQSettings.gainRange.lowerBound), EQSettings.gainRange.upperBound)
        }
        eq.globalGain = min(max(settings.preamp, EQSettings.gainRange.lowerBound), EQSettings.gainRange.upperBound)
        eq.bypass = !settings.enabled
    }

    var bandGains: [Float] { eq.bands.map(\.gain) }
    var isEQBypassed: Bool { eq.bypass }
}
