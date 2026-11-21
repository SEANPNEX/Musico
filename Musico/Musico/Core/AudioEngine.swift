import AVFoundation

/// Plays one file at a time through `player → converter → 10-band EQ → mixer`.
/// The converter turns each file's own format into one fixed stereo format, so the equalizer
/// never sees a format change (it rejects mono and surround layouts, and reconnecting it
/// between songs raises an exception).
/// Knows nothing about queues; `PlayerController` decides what to load next.
@MainActor
final class AudioEngine {
    /// Called when the loaded file plays through to its end (not on stop, seek or load).
    var onTrackEnded: (() -> Void)?

    private(set) var duration: TimeInterval = 0
    private(set) var isPlaying = false

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let converter = AVAudioMixerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: EQSettings.frequencies.count)
    private var file: AVAudioFile?
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

    var currentTime: TimeInterval { Double(currentFrame) / sampleRate }

    /// Opens a file and leaves it paused at the start.
    func load(url: URL) throws {
        unload()
        let opened = try AVAudioFile(forReading: url)
        let format = opened.processingFormat
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: converter, format: format)
        file = opened
        duration = Double(opened.length) / format.sampleRate
    }

    func unload() {
        abandonSchedule()
        file = nil
        duration = 0
        seekFrame = 0
        isPlaying = false
        stopRendering()
    }

    /// Pauses the engine so the app is not kept awake rendering silence.
    private func stopRendering() {
        if engine.isRunning { engine.pause() }
    }

    func play() {
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
    }

    func pause() {
        if isPlaying {
            let frame = currentFrame
            abandonSchedule()
            seekFrame = frame
            isPlaying = false
        }
        stopRendering()
    }

    /// Moves the position, keeping the play/pause state. Seeking to the end while playing ends the track.
    func seek(to time: TimeInterval) {
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
