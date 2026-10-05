import AVFoundation
import CryptoKit
import Foundation
import PamNative
import UIKit

/// PAM module `audio-player`: headless AVQueuePlayer instances addressed by
/// id; one plays at a time (Android ExoPlayer parity).
public final class AudioPlayerModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            let id = try values.text("playerId")
            if method == "next" {
                guard let player = AudioPlayers.get(id) else { throw AudioError("Player \(id) not found") }
                player.events.next(completion)
                return
            }
            let config = method == "play" ? try Self.config(id, values) : nil
            DispatchQueue.main.async {
                do {
                    switch method {
                    case "play": try AudioPlayers.play(config!)
                    case "stop": AudioPlayers.stop(id)
                    default:
                        guard let player = AudioPlayers.get(id), !player.finished else {
                            throw AudioError("Player \(id) is not playing")
                        }
                        switch method {
                        case "pause": player.pause()
                        case "resume": player.resume()
                        case "seek": player.seek(values.integer("position", 0))
                        case "skipTo": try player.skipTo(Int(values.integer("index", 0)))
                        case "setRate": player.setRate(Float(values.decimal("rate", 1)))
                        case "setRoute": player.setRoute(Int(values.integer("route", 1)))
                        case "setVolume": player.setVolume(Float(values.decimal("volume", 1)))
                        default: throw AudioError("Unknown audio player method \(method)")
                        }
                    }
                    succeed(completion)
                } catch {
                    fail(completion, error.localizedDescription)
                }
            }
        } catch {
            fail(completion, error.localizedDescription)
        }
    }

    public func close() {
        DispatchQueue.main.async { AudioPlayers.stopAll() }
    }

    static func config(_ id: String, _ values: [String: WireValue]) throws -> PlayerConfig {
        guard let sources = try JSONSerialization.jsonObject(with: Data(values.text("sourcesJson").utf8)) as? [String],
              (1...200).contains(sources.count) else {
            throw AudioError("Provide between 1 and 200 sources")
        }
        for source in sources where !PlayerConfig.isValidSource(source) {
            throw AudioError("Invalid source \(source)")
        }
        return PlayerConfig(
            id: id,
            sources: sources,
            rate: Float(min(max(values.decimal("rate", 1), 0.25), 4)),
            route: Int(values.integer("route", Int64(PamAudioPlayer.routeAuto))),
            volume: Float(min(max(values.decimal("volume", 1), 0), 1)),
            startAt: max(values.integer("startAt", 0), 0),
            progressInterval: min(max(values.integer("progressInterval", 250), 50), 5_000)
        )
    }
}

struct AudioError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func succeed(_ completion: ModuleCompletion, _ values: [String: WireValue] = [:]) {
    completion(.success, (try? WireMap.encode(values)) ?? Data())
}

func fail(_ completion: ModuleCompletion, _ message: String) {
    completion(.failure, Data(message.utf8))
}

extension Dictionary where Key == String, Value == WireValue {
    func text(_ key: String) throws -> String {
        guard case let .text(value)? = self[key] else { throw AudioError("Missing \(key)") }
        return value
    }

    func integer(_ key: String, _ fallback: Int64) -> Int64 {
        if case let .integer(value)? = self[key] { return value }
        return fallback
    }

    func decimal(_ key: String, _ fallback: Double) -> Double {
        switch self[key] {
        case let .decimal(value)?: return value
        case let .integer(value)?: return Double(value)
        default: return fallback
        }
    }
}

struct PlayerConfig {
    let id: String
    let sources: [String]
    let rate: Float
    let route: Int
    let volume: Float
    let startAt: Int64
    let progressInterval: Int64

    static func isValidSource(_ source: String) -> Bool {
        if source.range(of: "^https?://\\S+$", options: [.regularExpression, .caseInsensitive]) != nil { return true }
        return !source.isEmpty && !source.hasPrefix("/") && !source.contains("..") && !source.contains("://")
    }
}

/// Push-style event channel with progress coalescing (Android EventChannel).
final class EventChannel: @unchecked Sendable {
    private let capacity: Int
    private let lock = NSLock()
    private var queue: [[String: WireValue]] = []
    private var lastCoalescable = false
    private var waiter: ModuleCompletion?
    private var closed = false

    init(capacity: Int = 256) {
        self.capacity = capacity
    }

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if closed {
            lock.unlock()
            fail(completion, "Event channel closed")
            return
        }
        if !queue.isEmpty {
            let event = queue.removeFirst()
            if queue.isEmpty { lastCoalescable = false }
            lock.unlock()
            succeed(completion, event)
            return
        }
        let replaced = waiter
        waiter = completion
        lock.unlock()
        if let replaced { fail(replaced, "Event read replaced") }
    }

    /// [coalesce] replaces a still-undelivered coalescable tail (progress).
    func offer(_ event: [String: WireValue], coalesce: Bool = false) {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        let receiver = waiter
        if receiver == nil {
            if coalesce && lastCoalescable && !queue.isEmpty { queue.removeLast() }
            if queue.count >= capacity { queue.removeFirst() }
            queue.append(event)
            lastCoalescable = coalesce
        } else {
            waiter = nil
        }
        lock.unlock()
        if let receiver { succeed(receiver, event) }
    }

    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        queue.removeAll()
        let pending = waiter
        waiter = nil
        lock.unlock()
        if let pending { fail(pending, "Event channel closed") }
    }

    var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return queue.count
    }
}

/// Process-wide player registry (main thread).
enum AudioPlayers {
    private static var players: [String: PamAudioPlayer] = [:]

    static func get(_ id: String) -> PamAudioPlayer? {
        if Thread.isMainThread { return players[id] }
        return DispatchQueue.main.sync { players[id] }
    }

    static func current() -> PamAudioPlayer? { players.values.first { !$0.finished } }

    static func play(_ config: PlayerConfig) throws {
        for player in Array(players.values) {
            if !player.finished { player.stop() }
            if player.finished && player.events.pendingCount == 0 { players[player.id] = nil }
        }
        players.removeValue(forKey: config.id)?.stop()
        let player = PamAudioPlayer(config: config)
        players[config.id] = player
        try player.start()
    }

    static func stop(_ id: String) {
        players.removeValue(forKey: id)?.stop()
    }

    static func stopAll() {
        players.values.forEach { $0.stop() }
        players.removeAll()
    }
}

/// One AVQueuePlayer with a native queue, periodic progress, interruption and
/// route-loss handling and proximity-driven earpiece routing.
final class PamAudioPlayer: NSObject {
    static let routeAuto = 1
    static let routeSpeaker = 2
    static let routeEarpiece = 3
    static let stateBuffering: Int64 = 2
    static let statePlaying: Int64 = 3
    static let statePaused: Int64 = 4
    static let stateEnded: Int64 = 5
    static let stateFailed: Int64 = 6
    static let eventProgress: Int64 = 1
    static let eventState: Int64 = 2
    static let eventItemChanged: Int64 = 3
    static let eventEnded: Int64 = 4
    static let eventFailure: Int64 = 5
    static let eventRoute: Int64 = 6

    let id: String
    let events = EventChannel(capacity: 64)
    private let config: PlayerConfig
    private let player = AVQueuePlayer()
    private var urls: [URL] = []
    private(set) var index = 0
    private var rate: Float
    private var route: Int
    private(set) var earpiece = false
    private var near = false
    private var lastState: Int64 = 0
    private(set) var finished = false
    private var released = false
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var notifications: [NSObjectProtocol] = []

    init(config: PlayerConfig) {
        id = config.id
        self.config = config
        rate = config.rate
        route = config.route
        super.init()
    }

    func start() throws {
        urls = try config.sources.map(Self.resolve)
        player.volume = config.volume
        player.actionAtItemEnd = .advance
        try configureSession(earpiece: false)
        enqueue(from: 0)
        if config.startAt > 0 {
            player.seek(to: CMTime(value: config.startAt, timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero)
        }
        observe()
        player.playImmediately(atRate: rate)
        applyRoute()
    }

    func pause() {
        player.pause()
    }

    func resume() {
        if player.currentItem == nil { enqueue(from: index) }
        player.playImmediately(atRate: rate)
    }

    func seek(_ position: Int64) {
        player.seek(to: CMTime(value: max(position, 0), timescale: 1_000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            self?.emitProgress()
        }
    }

    func skipTo(_ target: Int) throws {
        guard urls.indices.contains(target) else { throw AudioError("Queue index out of range") }
        let playing = player.timeControlStatus != .paused
        enqueue(from: target)
        emit(Self.eventItemChanged, ["index": .integer(Int64(target))])
        if playing { player.playImmediately(atRate: rate) }
    }

    func setRate(_ value: Float) {
        rate = min(max(value, 0.25), 4)
        if player.timeControlStatus != .paused { player.rate = rate }
    }

    func setVolume(_ value: Float) {
        player.volume = min(max(value, 0), 1)
    }

    func setRoute(_ value: Int) {
        route = value
        updateProximityMonitoring()
        applyRoute()
    }

    var isPlaying: Bool { !finished && player.timeControlStatus == .playing }

    var position: Int64 {
        guard !finished else { return 0 }
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? Int64(seconds * 1_000) : 0
    }

    var currentRate: Float { rate }

    /// Test hook equivalent to a proximity change.
    func simulateProximity(_ near: Bool) { onProximity(near) }

    func stop() { release(ended: false) }

    // MARK: Queue

    private func enqueue(from start: Int) {
        player.removeAllItems()
        index = start
        for url in urls[start...] {
            let item = AVPlayerItem(url: url)
            item.audioTimePitchAlgorithm = .timeDomain
            if player.canInsert(item, after: nil) { player.insert(item, after: nil) }
        }
    }

    private func observe() {
        let interval = CMTime(value: config.progressInterval, timescale: 1_000)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            guard let self, self.player.timeControlStatus == .playing else { return }
            self.emitProgress()
        }
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            DispatchQueue.main.async { self?.onStatus(player.timeControlStatus) }
        })
        observations.append(player.observe(\.currentItem, options: [.old, .new]) { [weak self] _, change in
            DispatchQueue.main.async {
                guard let self, !self.finished,
                      let old = change.oldValue, old != nil,
                      let new = change.newValue, new != nil else { return }
                self.index = min(self.index + 1, self.urls.count - 1)
                self.emit(Self.eventItemChanged, ["index": .integer(Int64(self.index))])
            }
        })
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            guard let self, let item = note.object as? AVPlayerItem, self.player.items().last === item || self.player.items().isEmpty else { return }
            if self.index >= self.urls.count - 1 { self.finish() }
        })
        notifications.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.failPlayback(error?.localizedDescription ?? "Playback failed")
        })
        notifications.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber).flatMap { AVAudioSession.InterruptionType(rawValue: $0.uintValue) }
            if type == .began { self?.pause() }
        })
        notifications.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? NSNumber).flatMap { AVAudioSession.RouteChangeReason(rawValue: $0.uintValue) }
            // Becoming noisy: headphones unplugged.
            if reason == .oldDeviceUnavailable, self?.earpiece == false { self?.pause() }
            if self?.route == Self.routeAuto { self?.applyRoute() }
        })
        notifications.append(center.addObserver(forName: UIDevice.proximityStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.onProximity(UIDevice.current.proximityState)
        })
        observations.append(player.observe(\.currentItem?.status, options: [.new]) { [weak self] player, _ in
            DispatchQueue.main.async {
                if player.currentItem?.status == .failed {
                    self?.failPlayback(player.currentItem?.error?.localizedDescription ?? "Playback failed")
                }
            }
        })
    }

    private func onStatus(_ status: AVPlayer.TimeControlStatus) {
        guard !finished else { return }
        switch status {
        case .waitingToPlayAtSpecifiedRate:
            emitState(Self.stateBuffering)
        case .playing:
            emitState(Self.statePlaying)
            emitProgress()
        case .paused:
            if player.currentItem != nil {
                emitProgress()
                emitState(Self.statePaused)
            }
        @unknown default:
            break
        }
        updateProximityMonitoring()
    }

    private func finish() {
        guard !finished else { return }
        emitState(Self.stateEnded)
        finished = true
        emit(Self.eventEnded)
        release(ended: true)
    }

    private func failPlayback(_ message: String) {
        guard !finished else { return }
        emitState(Self.stateFailed)
        emit(Self.eventFailure, ["message": .text(message)])
        finished = true
        release(ended: true)
    }

    private func release(ended: Bool) {
        if released {
            if !ended { events.close() }
            return
        }
        released = true
        finished = true
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.forEach { $0.invalidate() }
        observations = []
        notifications.forEach { NotificationCenter.default.removeObserver($0) }
        notifications = []
        player.pause()
        player.removeAllItems()
        UIDevice.current.isProximityMonitoringEnabled = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if !ended { events.close() }
    }

    // MARK: Routing

    private func configureSession(earpiece: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        if earpiece {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try session.overrideOutputAudioPort(.none)
        } else {
            try session.setCategory(.playback, mode: .spokenAudio, options: [])
        }
        try session.setActive(true)
    }

    private func applyRoute() {
        switch route {
        case Self.routeSpeaker: routeToEarpiece(false)
        case Self.routeEarpiece: routeToEarpiece(true)
        default: routeToEarpiece(near && !Self.headsetConnected())
        }
    }

    private func updateProximityMonitoring() {
        let wanted = !finished && (route == Self.routeEarpiece || (route == Self.routeAuto && player.timeControlStatus == .playing))
        if UIDevice.current.isProximityMonitoringEnabled != wanted {
            UIDevice.current.isProximityMonitoringEnabled = wanted
        }
    }

    private func onProximity(_ near: Bool) {
        self.near = near
        if route == Self.routeAuto && !finished { applyRoute() }
    }

    private func routeToEarpiece(_ on: Bool) {
        guard on != earpiece else { return }
        earpiece = on
        try? configureSession(earpiece: on)
        emit(Self.eventRoute, ["route": .integer(Int64(on ? Self.routeEarpiece : Self.routeSpeaker))])
    }

    static let headsetPorts: [AVAudioSession.Port] = [.headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .usbAudio, .carAudio]

    static func headsetConnected() -> Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { headsetPorts.contains($0.portType) }
    }

    // MARK: Events

    private func emitProgress() {
        guard !finished else { return }
        let duration = player.currentItem?.duration.seconds ?? 0
        let buffered = player.currentItem?.loadedTimeRanges.last.map { CMTimeRangeGetEnd($0.timeRangeValue).seconds } ?? 0
        events.offer([
            "kind": .integer(Self.eventProgress),
            "position": .integer(position),
            "duration": .integer(duration.isFinite ? Int64(max(duration, 0) * 1_000) : 0),
            "buffered": .integer(buffered.isFinite ? Int64(max(buffered, 0) * 1_000) : 0),
            "index": .integer(Int64(index)),
        ], coalesce: true)
    }

    private func emitState(_ state: Int64) {
        guard state != lastState else { return }
        lastState = state
        emit(Self.eventState, ["state": .integer(state)])
    }

    private func emit(_ kind: Int64, _ values: [String: WireValue] = [:]) {
        var event = values
        event["kind"] = .integer(kind)
        events.offer(event)
    }

    // MARK: Sources

    /// https URLs play from the 64 MiB audio cache when present (and are
    /// cached in the background otherwise); paths resolve inside pam-files.
    static func resolve(_ source: String) throws -> URL {
        if source.lowercased().hasPrefix("http://") || source.lowercased().hasPrefix("https://") {
            guard let url = URL(string: source) else { throw AudioError("Invalid source \(source)") }
            return AudioCache.shared.cachedOrStream(url)
        }
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pam-files").standardizedFileURL.resolvingSymlinksInPath()
        let file = root.appendingPathComponent(source).standardizedFileURL.resolvingSymlinksInPath()
        guard file.path.hasPrefix(root.path + "/") else { throw AudioError("Audio path escapes the sandbox") }
        return file
    }
}

/// Shared 64 MiB LRU cache for remote audio (voice notes are fetched once).
final class AudioCache: @unchecked Sendable {
    static let shared = AudioCache()
    static let limit: Int64 = 64 * 1024 * 1024
    private let root: URL
    private let lock = NSLock()
    private var downloading: Set<String> = []

    init(root: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("pam-audio-cache")) {
        self.root = root
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    func file(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
        let ext = url.pathExtension.isEmpty ? "audio" : url.pathExtension.lowercased()
        return root.appendingPathComponent("\(digest).\(ext)")
    }

    func cachedOrStream(_ url: URL) -> URL {
        let target = file(for: url)
        if FileManager.default.fileExists(atPath: target.path) {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
            return target
        }
        prefetch(url, to: target)
        return url
    }

    private func prefetch(_ url: URL, to target: URL) {
        lock.lock()
        guard !downloading.contains(target.lastPathComponent) else {
            lock.unlock()
            return
        }
        downloading.insert(target.lastPathComponent)
        lock.unlock()
        URLSession.shared.downloadTask(with: URLRequest(url: url, timeoutInterval: 15)) { [weak self] location, response, _ in
            guard let self else { return }
            defer {
                self.lock.lock()
                self.downloading.remove(target.lastPathComponent)
                self.lock.unlock()
            }
            guard let location, (200..<300).contains((response as? HTTPURLResponse)?.statusCode ?? 0) else { return }
            try? FileManager.default.removeItem(at: target)
            try? FileManager.default.moveItem(at: location, to: target)
            self.trim()
        }.resume()
    }

    func trim() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: keys) else { return }
        var entries = files.compactMap { url -> (URL, Int64, Date)? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast)
        }
        var total = entries.reduce(0) { $0 + $1.1 }
        entries.sort { $0.2 < $1.2 }
        for entry in entries where total > Self.limit {
            try? FileManager.default.removeItem(at: entry.0)
            total -= entry.1
        }
    }
}
