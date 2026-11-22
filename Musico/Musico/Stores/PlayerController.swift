import AVFoundation
import MediaPlayer
import Observation
import UIKit

/// Drives playback from the active queue and talks to the system: audio session,
/// lock-screen controls, Now Playing info, sleep timer.
@MainActor @Observable
final class PlayerController {
    enum SleepMode: Equatable {
        case off
        case until(Date)
        case endOfTrack
    }

    private(set) var currentTrack: Track?
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var bufferingProgress: CloudDownloadProgress?
    private(set) var duration: TimeInterval = 0
    private(set) var currentTime: TimeInterval = 0
    var errorMessage: String?
    private(set) var sleepMode: SleepMode = .off
    var eq: EQSettings {
        didSet {
            engine.apply(eq)
            try? Persistence.save(eq, to: settingsURL)
        }
    }

    @ObservationIgnored let library: LibraryStore
    @ObservationIgnored let queues: QueueStore
    @ObservationIgnored private let history: PlayHistoryStore
    @ObservationIgnored private let cloudAudio: CloudAudioCache
    /// Time actually listened to the loaded track since it was loaded, and whether that
    /// listen has been counted as a play yet.
    @ObservationIgnored private var listened: TimeInterval = 0
    @ObservationIgnored private var playCounted = false
    @ObservationIgnored private var markedRecent = false
    @ObservationIgnored private let engine: AudioEngine
    @ObservationIgnored private let settingsURL: URL
    /// Whether the engine currently holds `currentTrack`'s file.
    @ObservationIgnored private var isLoaded = false
    @ObservationIgnored private var cloudLoadTask: Task<Void, Never>?
    @ObservationIgnored private var cloudLoadGeneration = 0
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var tickCount = 0
    @ObservationIgnored private var sleepTask: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var commandTargets: [(MPRemoteCommand, Any)] = []
    @ObservationIgnored private var resumeAfterInterruption = false

    static let maxConsecutiveFailures = 3
    static let restartThreshold: TimeInterval = 3
    static let tickInterval: TimeInterval = 0.25
    /// Listening this long puts a song in "recently played"; shorter is treated as a skip.
    static let recentAfter: TimeInterval = 5

    init(library: LibraryStore, queues: QueueStore, history: PlayHistoryStore, engine: AudioEngine, settingsURL: URL,
         cloudAudio: CloudAudioCache = .shared) {
        self.library = library
        self.queues = queues
        self.history = history
        self.engine = engine
        self.settingsURL = settingsURL
        self.cloudAudio = cloudAudio
        eq = Persistence.load(EQSettings.self, from: settingsURL) ?? EQSettings()
        engine.apply(eq)
        engine.onTrackEnded = { [weak self] in self?.trackDidEnd() }
        library.onTracksRemoved = { [weak self] ids in self?.tracksRemoved(ids) }
        library.onTrackUpdated = { [weak self] track in self?.trackUpdated(track) }
        library.onFolderAdded = { [weak self] name, trackIDs in self?.syncFolderQueue(name: name, trackIDs: trackIDs) }
        configureAudioSession { try $0.setCategory(.playback, mode: .default) }
        observeSystem()
        registerRemoteCommands()
        let timer = Timer(timeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        // Common modes, so progress and lyrics keep moving while a list or slider is being dragged.
        RunLoop.main.add(timer, forMode: .common)
        ticker = timer
    }

    /// Detaches from the system. The app keeps one controller for its lifetime; tests create many.
    func shutdown() {
        cancelCloudLoad()
        ticker?.invalidate()
        ticker = nil
        sleepTask?.cancel()
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        commandTargets.forEach { $0.0.removeTarget($0.1) }
        commandTargets = []
        engine.onTrackEnded = nil
        engine.unload()
        isPlaying = false
    }

    // MARK: Loading

    private func cancelCloudLoad() {
        cloudLoadGeneration += 1
        cloudLoadTask?.cancel()
        cloudLoadTask = nil
        isBuffering = false
        bufferingProgress = nil
        engine.onStreamReady = nil
        engine.onStreamBufferingChanged = nil
        engine.onStreamFailed = nil
    }

    /// Loads the active queue's current track. Returns false if there is none or it cannot be opened.
    private func loadCurrent(autoplay: Bool, at position: TimeInterval) -> Bool {
        bufferingProgress = nil
        isLoaded = false
        guard let id = queues.active.currentTrackID, let track = library.track(id) else { return false }
        currentTrack = track
        duration = track.duration
        currentTime = 0
        guard let url = library.url(for: track) else { return false }
        // Opening an iCloud file that is not downloaded would block until it arrives.
        // Ask for it and treat it as unplayable for now.
        if let status = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey]).ubiquitousItemDownloadingStatus,
           status != .current {
            try? FileManager.default.startDownloadingUbiquitousItem(at: url)
            return false
        }
        do {
            try engine.load(url: url)
        } catch {
            return false
        }
        isLoaded = true
        listened = 0
        playCounted = false
        markedRecent = false
        duration = engine.duration
        if position > 0, position < engine.duration - 1 { engine.seek(to: position) }
        currentTime = engine.currentTime
        if autoplay { startEngine() }
        isPlaying = engine.isPlaying
        updateNowPlaying()
        return true
    }

    /// The audio session is an iPhone and iPad concept. A Mac app plays without one, and asking
    /// for it there can block the main thread at launch waiting on a system service.
    private func configureAudioSession(_ body: (AVAudioSession) throws -> Void) {
        #if !targetEnvironment(macCatalyst)
        try? body(AVAudioSession.sharedInstance())
        #endif
    }

    private func startEngine() {
        configureAudioSession { try $0.setActive(true) }
        engine.play()
    }

    /// Loads the current track, skipping forward over files that will not open.
    private func startCurrent(autoplay: Bool, at position: TimeInterval = 0, startingFailures: Int = 0) {
        cancelCloudLoad()
        var failures = startingFailures
        var position = position
        while true {
            guard let id = queues.active.currentTrackID else {
                clearPlayback()
                return
            }
            if let track = library.track(id), track.isCloud {
                startCloudTrack(track, autoplay: autoplay, at: position, failures: failures)
                return
            }
            if loadCurrent(autoplay: autoplay, at: position) { return }
            position = 0
            failures += 1
            var advanced: String?
            if failures < Self.maxConsecutiveFailures {
                queues.updateActive { advanced = $0.next(auto: false) }
            }
            if advanced == nil {
                engine.unload()
                isPlaying = false
                errorMessage = failures > 1
                    ? L10n.tr("Playback stopped: %@ songs in a row could not be played.", String(failures))
                    : L10n.tr("“%@” could not be played.", currentTrack?.title ?? L10n.tr("This song"))
                updateNowPlaying()
                return
            }
        }
    }

    private func startCloudTrack(_ track: Track, autoplay: Bool, at position: TimeInterval, failures: Int) {
        cloudLoadGeneration += 1
        let gen = cloudLoadGeneration
        engine.unload()
        isLoaded = false
        isPlaying = false
        currentTrack = track
        duration = track.duration
        currentTime = min(max(position, 0), track.duration)
        errorMessage = nil
        bufferingProgress = nil
        isBuffering = true
        updateNowPlaying()

        let root = library.root(track.rootID)
        let cache = cloudAudio

        cloudLoadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if let cachedURL = await cache.cachedURLIfPresent(for: track.id) {
                    try await self.loadCachedCloudFile(
                        cachedURL,
                        track: track,
                        root: root,
                        cache: cache,
                        autoplay: autoplay,
                        position: position,
                        gen: gen
                    )
                    return
                }

                if !cache.usesCustomDownloader {
                    let stream = try await cache.resolveStream(track: track, root: root, forceRefresh: false)
                    guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }

                    let shouldStream = (stream.contentLength ?? 0) > CloudAudioCache.streamingThresholdBytes
                        || track.duration > 600
                    if shouldStream {
                        self.startProgressiveStream(
                            stream,
                            track: track,
                            root: root,
                            cache: cache,
                            autoplay: autoplay,
                            position: position,
                            failures: failures,
                            gen: gen,
                            hasRetried: false
                        )
                        return
                    }
                }

                var cachedURL = try await cache.resolveAndCache(
                    track: track,
                    root: root,
                    forceRefresh: false,
                    onProgress: { [weak self] progress in
                        Task { @MainActor [weak self] in
                            guard let self, self.cloudLoadGeneration == gen else { return }
                            self.bufferingProgress = progress
                        }
                    }
                )
                guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }

                do {
                    try self.engine.load(url: cachedURL)
                } catch {
                    // Cached file may be corrupt or incomplete; re-resolve once before failing over.
                    await cache.remove(trackID: track.id)
                    cachedURL = try await cache.resolveAndCache(
                        track: track,
                        root: root,
                        forceRefresh: true,
                        onProgress: { [weak self] progress in
                            Task { @MainActor [weak self] in
                                guard let self, self.cloudLoadGeneration == gen else { return }
                                self.bufferingProgress = progress
                            }
                        }
                    )
                    guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }
                    try self.engine.load(url: cachedURL)
                }

                guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }
                self.finishCloudFileLoad(track: track, autoplay: autoplay, position: position)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }
                self.handleCloudTrackFailure(track: track, autoplay: autoplay, failures: failures)
            }
        }
    }

    private func loadCachedCloudFile(
        _ initialURL: URL,
        track: Track,
        root: LibraryRoot?,
        cache: CloudAudioCache,
        autoplay: Bool,
        position: TimeInterval,
        gen: Int
    ) async throws {
        var cachedURL = initialURL
        do {
            try engine.load(url: cachedURL)
        } catch {
            await cache.remove(trackID: track.id)
            cachedURL = try await cache.resolveAndCache(
                track: track,
                root: root,
                forceRefresh: true,
                onProgress: { [weak self] progress in
                    Task { @MainActor [weak self] in
                        guard let self, self.cloudLoadGeneration == gen else { return }
                        self.bufferingProgress = progress
                    }
                }
            )
            guard !Task.isCancelled, cloudLoadGeneration == gen else { return }
            try engine.load(url: cachedURL)
        }
        guard !Task.isCancelled, cloudLoadGeneration == gen else { return }
        finishCloudFileLoad(track: track, autoplay: autoplay, position: position)
    }

    private func finishCloudFileLoad(track: Track, autoplay: Bool, position: TimeInterval) {
        isBuffering = false
        bufferingProgress = nil
        isLoaded = true
        listened = 0
        playCounted = false
        markedRecent = false
        duration = engine.duration > 0 ? engine.duration : track.duration
        if position > 0, position < engine.duration - 1 {
            engine.seek(to: position)
        }
        currentTime = engine.currentTime
        if autoplay { startEngine() }
        isPlaying = engine.isPlaying
        updateNowPlaying()
        prefetchNextCloudTrack()
    }

    private func startProgressiveStream(
        _ stream: CloudStreamResult,
        track: Track,
        root: LibraryRoot?,
        cache: CloudAudioCache,
        autoplay: Bool,
        position: TimeInterval,
        failures: Int,
        gen: Int,
        hasRetried: Bool
    ) {
        engine.onStreamReady = { [weak self] dur in
            guard let self, self.cloudLoadGeneration == gen else { return }
            if dur > 0 { self.duration = dur }
            self.isBuffering = false
            self.bufferingProgress = nil
            self.isLoaded = true
            self.currentTime = self.engine.currentTime
            self.isPlaying = self.engine.isPlaying
            self.updateNowPlaying()
        }
        engine.onStreamBufferingChanged = { [weak self] buffering in
            guard let self, self.cloudLoadGeneration == gen else { return }
            if self.isLoaded {
                self.isBuffering = buffering
            }
        }
        engine.onStreamFailed = { [weak self] _ in
            guard let self, self.cloudLoadGeneration == gen else { return }
            if !hasRetried {
                self.cloudLoadTask = Task { @MainActor [weak self] in
                    guard let self else { return }
                    do {
                        let refreshed = try await cache.resolveStream(track: track, root: root, forceRefresh: true)
                        guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }
                        self.startProgressiveStream(
                            refreshed,
                            track: track,
                            root: root,
                            cache: cache,
                            autoplay: autoplay,
                            position: position,
                            failures: failures,
                            gen: gen,
                            hasRetried: true
                        )
                    } catch is CancellationError {
                        return
                    } catch {
                        guard !Task.isCancelled, self.cloudLoadGeneration == gen else { return }
                        self.handleCloudTrackFailure(track: track, autoplay: autoplay, failures: failures)
                    }
                }
            } else {
                self.handleCloudTrackFailure(track: track, autoplay: autoplay, failures: failures)
            }
        }

        engine.loadStream(
            url: stream.url,
            headers: stream.headers,
            contentLength: stream.contentLength,
            fileExtension: stream.fileExtension,
            estimatedDuration: track.duration
        )
        listened = 0
        playCounted = false
        markedRecent = false
        if position > 0, (track.duration == 0 || position < track.duration - 1) {
            engine.seek(to: position)
        }
        if autoplay { startEngine() }
        isPlaying = engine.isPlaying
        updateNowPlaying()
    }

    private func handleCloudTrackFailure(track: Track, autoplay: Bool, failures: Int) {
        isBuffering = false
        bufferingProgress = nil
        let nextFailures = failures + 1
        var advanced: String?
        if nextFailures < Self.maxConsecutiveFailures {
            queues.updateActive { advanced = $0.next(auto: false) }
        }
        if advanced != nil {
            startCurrent(autoplay: autoplay, at: 0, startingFailures: nextFailures)
        } else {
            engine.unload()
            isPlaying = false
            errorMessage = nextFailures > 1
                ? L10n.tr("Playback stopped: %@ songs in a row could not be played.", String(nextFailures))
                : L10n.tr("“%@” could not be played.", track.title)
            updateNowPlaying()
        }
    }

    private func prefetchNextCloudTrack() {
        guard let nextIndex = queues.active.upcomingIndices.first,
              queues.active.trackIDs.indices.contains(nextIndex),
              let nextTrack = library.track(queues.active.trackIDs[nextIndex]),
              nextTrack.isCloud,
              nextTrack.duration <= 900 else { return }
        let root = library.root(nextTrack.rootID)
        let cache = cloudAudio
        Task.detached(priority: .utility) {
            await cache.prefetch(track: nextTrack, root: root)
        }
    }

    private func clearPlayback() {
        cancelCloudLoad()
        engine.unload()
        isLoaded = false
        currentTrack = nil
        isPlaying = false
        duration = 0
        currentTime = 0
        updateNowPlaying()
    }

    // MARK: Starting playback

    /// Replaces the active queue with `ids` and plays from `startAt`.
    func play(_ ids: [String], startAt: Int) {
        guard !ids.isEmpty else { return }
        errorMessage = nil
        queues.updateActive { $0.replace(with: ids, startAt: startAt) }
        startCurrent(autoplay: true)
    }

    func playInNewQueue(_ ids: [String], startAt: Int = 0, name: String? = nil) {
        guard !ids.isEmpty else { return }
        persistPosition()
        queues.setActive(queues.addQueue(name: name))
        play(ids, startAt: startAt)
    }

    func playInNamedQueue(_ ids: [String], name: String, startAt: Int = 0, shuffle: Bool? = nil) {
        guard !ids.isEmpty else { return }
        persistPosition()
        let queueID = queues.createOrReplaceQueue(named: name, trackIDs: ids, startAt: startAt)
        queues.setActive(queueID)
        if let shuffle {
            var rng = SystemRandomNumberGenerator()
            queues.updateActive { $0.setShuffle(shuffle, using: &rng) }
        }
        errorMessage = nil
        startCurrent(autoplay: true)
    }

    func playNext(_ ids: [String]) {
        let wasEmpty = queues.active.currentTrackID == nil
        queues.updateActive { $0.insertNext(ids) }
        if wasEmpty { startCurrent(autoplay: true) }
    }

    /// Appends to a queue (the active one when `queue` is nil).
    func addToQueue(_ ids: [String], queue: UUID?) {
        let target = queue ?? queues.activeID
        let startsPlayback = target == queues.activeID && queues.active.currentTrackID == nil
        queues.update(target) { $0.append(ids) }
        if startsPlayback { startCurrent(autoplay: true) }
    }

    // MARK: Transport

    func togglePlayPause() {
        if isPlaying || isBuffering {
            pause()
        } else {
            resume()
        }
    }

    func resume() {
        guard !isPlaying else { return }
        errorMessage = nil
        if isLoaded {
            startEngine()
            isPlaying = engine.isPlaying
            updateNowPlaying()
        } else {
            startCurrent(autoplay: true, at: queues.active.position)
        }
    }

    func pause() {
        resumeAfterInterruption = false
        if isBuffering {
            if isLoaded {
                engine.pause()
                isBuffering = false
                isPlaying = false
                currentTime = engine.currentTime
                persistPosition()
            } else {
                cancelCloudLoad()
                engine.unload()
                isPlaying = false
            }
            updateNowPlaying()
            return
        }
        guard isPlaying else { return }
        engine.pause()
        isPlaying = false
        currentTime = engine.currentTime
        persistPosition()
        updateNowPlaying()
    }

    func next() {
        let wasPlaying = isPlaying || isBuffering
        var advanced: String?
        queues.updateActive { advanced = $0.next(auto: false) }
        if advanced != nil { startCurrent(autoplay: wasPlaying) }
    }

    /// Restarts the track when more than a few seconds in, otherwise goes to the previous one.
    func previous() {
        if isLoaded, engine.currentTime > Self.restartThreshold {
            seek(to: 0)
            return
        }
        let wasPlaying = isPlaying || isBuffering
        queues.updateActive { _ = $0.previous() }
        startCurrent(autoplay: wasPlaying)
    }

    func seek(to time: TimeInterval) {
        guard isLoaded else { return }
        engine.seek(to: time)
        currentTime = engine.currentTime
        updateNowPlaying()
    }

    func jump(to index: Int) {
        errorMessage = nil
        queues.updateActive { $0.jump(to: index) }
        startCurrent(autoplay: true)
    }

    func toggleShuffle() {
        var rng = SystemRandomNumberGenerator()
        queues.updateActive { $0.setShuffle(!$0.shuffle, using: &rng) }
    }

    func cycleRepeat() {
        queues.updateActive {
            switch $0.repeatMode {
            case .off: $0.repeatMode = .all
            case .all: $0.repeatMode = .one
            case .one: $0.repeatMode = .off
            }
        }
    }

    /// Adds listening time for the loaded track. A song counts as played once half of it has
    /// been listened to; skipping after a few seconds does not count.
    func noteListening(_ seconds: TimeInterval) {
        guard isLoaded, let track = currentTrack else { return }
        listened += seconds
        history.addListening(seconds)
        if !markedRecent, listened >= Self.recentAfter {
            markedRecent = true
            history.markStarted(track.id)
        }
        if !playCounted, duration > 0, listened >= duration / 2 { countPlay(track) }
    }

    private func countPlay(_ track: Track) {
        playCounted = true
        markedRecent = true
        history.recordPlay(track.id)
    }

    /// The loaded file finished playing by itself.
    func trackDidEnd() {
        if isLoaded, !playCounted, let track = currentTrack { countPlay(track) }
        let stopAfter = sleepMode == .endOfTrack
        if stopAfter { setSleep(.off) }
        var advanced: String?
        queues.updateActive { advanced = $0.next(auto: true) }
        if advanced != nil {
            startCurrent(autoplay: !stopAfter)
        } else {
            // End of the queue with repeat off: rest at the start of the last track.
            engine.pause()
            engine.seek(to: 0)
            isPlaying = false
            currentTime = 0
            persistPosition()
            updateNowPlaying()
        }
    }

    // MARK: Queues

    func switchQueue(to id: UUID) {
        guard id != queues.activeID else { return }
        let wasPlaying = isPlaying || isBuffering
        persistPosition()
        queues.setActive(id)
        errorMessage = nil
        startCurrent(autoplay: wasPlaying, at: queues.active.position)
    }

    func deleteQueue(_ id: UUID) {
        let wasActive = id == queues.activeID
        let wasPlaying = isPlaying || isBuffering
        queues.delete(id)
        if wasActive, queues.activeID != id { startCurrent(autoplay: wasPlaying, at: queues.active.position) }
    }

    func removeFromQueue(at index: Int) {
        let removingCurrent = index == queues.active.currentIndex
        let wasPlaying = isPlaying || isBuffering
        queues.updateActive { $0.remove(at: index) }
        if removingCurrent { startCurrent(autoplay: wasPlaying) }
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        queues.updateActive { $0.move(from: source, to: destination) }
    }

    func sortActiveQueue(by order: TrackSortOrder) {
        let map = Dictionary(library.tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let history = self.history
        queues.updateActive {
            $0.sort(
                by: order,
                tracksByID: map,
                playCount: { history.count(for: $0) },
                lastPlayed: { history.lastPlayed(for: $0) }
            )
        }
    }

    func clearQueue() {
        queues.updateActive { $0.replace(with: [], startAt: 0) }
        clearPlayback()
    }

    private func trackUpdated(_ track: Track) {
        guard currentTrack?.id == track.id else { return }
        currentTrack = track
        updateNowPlaying()
    }

    private func syncFolderQueue(name: String, trackIDs: [String]) {
        let previousCurrentID = queues.active.currentTrackID
        let wasPlaying = isPlaying || isBuffering
        let queueID = queues.createOrReplaceQueue(named: name, trackIDs: trackIDs, startAt: nil)
        if queueID == queues.activeID, queues.active.currentTrackID != previousCurrentID {
            startCurrent(autoplay: wasPlaying)
        }
    }

    private func tracksRemoved(_ ids: Set<String>) {
        let currentRemoved = currentTrack.map { ids.contains($0.id) } ?? false
        let wasPlaying = isPlaying || isBuffering
        queues.removeTracks(ids)
        history.removeTracks(ids)
        if currentRemoved { startCurrent(autoplay: wasPlaying) }
    }

    // MARK: Persistence

    /// Shows the saved queue's track, paused at its saved position, after launch.
    func restore() {
        if let id = queues.active.currentTrackID, let track = library.track(id), track.isCloud {
            cancelCloudLoad()
            isLoaded = false
            currentTrack = track
            duration = track.duration
            currentTime = min(max(queues.active.position, 0), track.duration)
            isPlaying = false
            updateNowPlaying()
            return
        }
        _ = loadCurrent(autoplay: false, at: queues.active.position)
    }

    /// Records the position. `flush` writes it to disk; playback ticks pass false most of the time.
    func persistPosition(flush: Bool = true) {
        guard isLoaded else { return }
        queues.setActivePosition(engine.currentTime)
        if flush {
            queues.save()
            history.save()
        }
    }

    private func tick() {
        guard isPlaying else { return }
        currentTime = engine.currentTime
        noteListening(Self.tickInterval)
        tickCount += 1
        // Every 5 s in memory, every 30 s to disk; pausing and backgrounding flush immediately.
        if tickCount % 20 == 0 { persistPosition(flush: tickCount % 120 == 0) }
    }

    // MARK: Sleep timer

    func setSleep(_ mode: SleepMode) {
        sleepTask?.cancel()
        sleepTask = nil
        sleepMode = mode
        guard case .until(let date) = mode else { return }
        sleepTask = Task { [weak self] in
            let delay = max(date.timeIntervalSinceNow, 0)
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.sleepMode = .off
            self.pause()
        }
    }

    // MARK: System integration

    private func observeSystem() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            MainActor.assumeIsolated {
                guard let self else { return }
                if type == .began {
                    let wasPlaying = self.isPlaying
                    self.pause()
                    self.resumeAfterInterruption = wasPlaying
                } else if type == .ended, self.resumeAfterInterruption {
                    self.resumeAfterInterruption = false
                    if options.contains(.shouldResume) { self.resume() }
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.pause() }
        })
    }

    private func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        func add(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlayerController, MPRemoteCommandEvent) -> Void) {
            command.isEnabled = true
            commandTargets.append((command, command.addTarget(handler: Self.handler(self, action))))
        }
        add(center.playCommand) { player, _ in player.resume() }
        add(center.pauseCommand) { player, _ in player.pause() }
        add(center.togglePlayPauseCommand) { player, _ in player.togglePlayPause() }
        add(center.nextTrackCommand) { player, _ in player.next() }
        add(center.previousTrackCommand) { player, _ in player.previous() }
        add(center.changePlaybackPositionCommand) { player, event in
            if let event = event as? MPChangePlaybackPositionCommandEvent { player.seek(to: event.positionTime) }
        }
    }

    /// Built outside the main actor's isolation because the system may call these from any thread.
    private nonisolated static func handler(
        _ player: PlayerController, _ action: @escaping @MainActor (PlayerController, MPRemoteCommandEvent) -> Void
    ) -> (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        { [weak player] event in
            Task { @MainActor in
                if let player { action(player, event) }
            }
            return .success
        }
    }

    private nonisolated static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }

    private func updateNowPlaying() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let key = track.artworkKey, let image = library.artwork.image(forKey: key) {
            info[MPMediaItemPropertyArtwork] = Self.makeArtwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
