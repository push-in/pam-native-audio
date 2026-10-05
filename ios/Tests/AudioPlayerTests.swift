import AVFoundation
import PamNative
import XCTest
// Generated plugin target: PamPlugin<index>PushinbrPamNativeAudio (index = plugin order).
@testable import PamPlugin0PushinbrPamNativeAudio

/// XCTest mirror of AudioPlayerInstrumentedTest. Uncompiled — needs Mac validation.
final class AudioPlayerTests: XCTestCase {
    private let module = AudioPlayerModule()
    private let files = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("pam-files/voice")

    override func setUpWithError() throws {
        try FileManager.default.createDirectory(at: files, withIntermediateDirectories: true)
        try wav(seconds: 1.2).write(to: files.appendingPathComponent("a.wav"))
        try wav(seconds: 1.2).write(to: files.appendingPathComponent("b.wav"))
        try wav(seconds: 8).write(to: files.appendingPathComponent("long.wav"))
    }

    override func tearDown() {
        DispatchQueue.main.async { AudioPlayers.stopAll() }
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        super.tearDown()
    }

    /// 16-bit mono 8 kHz PCM tone.
    private func wav(seconds: Double) -> Data {
        let rate = 8_000
        let samples = Int(Double(rate) * seconds)
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + samples * 2))
        data.append(contentsOf: Array("WAVEfmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(rate)); append(UInt32(rate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(samples * 2))
        for index in 0..<samples { append(Int16(sin(Double(index) * 0.3) * 8_000)) }
        return data
    }

    private func call(_ method: String, _ values: [String: WireValue]) -> Bool {
        let done = expectation(description: method)
        var ok = false
        module.invoke(method: method, payload: (try? WireMap.encode(values)) ?? Data()) { status, _ in
            ok = status == .success
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        return ok
    }

    private func play(_ id: String, _ sources: [String], rate: Double = 1, route: Int64 = 1, interval: Int64 = 100) -> Bool {
        let json = String(decoding: try! JSONSerialization.data(withJSONObject: sources), as: UTF8.self)
        return call("play", ["playerId": .text(id), "sourcesJson": .text(json), "rate": .decimal(rate), "route": .integer(route), "progressInterval": .integer(interval)])
    }

    private func awaitEvent(_ id: String, timeout: TimeInterval = 10, _ predicate: @escaping ([String: WireValue]) -> Bool) -> [String: WireValue] {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let done = expectation(description: "next")
            var event: [String: WireValue]?
            module.invoke(method: "next", payload: (try? WireMap.encode(["playerId": .text(id)])) ?? Data()) { status, payload in
                if status == .success { event = try? WireMap.decode(payload) }
                done.fulfill()
            }
            wait(for: [done], timeout: max(0.1, deadline.timeIntervalSinceNow))
            if let event, predicate(event) { return event }
            if event == nil { break }
        }
        XCTFail("event not received")
        return [:]
    }

    private func int(_ event: [String: WireValue], _ key: String) -> Int64 {
        if case let .integer(value)? = event[key] { return value }
        return -1
    }

    func testQueueAdvancesNativelyWithProgressAndEndEvents() {
        XCTAssertTrue(play("q1", ["voice/a.wav", "voice/b.wav"]))
        _ = awaitEvent("q1") { self.int($0, "kind") == 2 && self.int($0, "state") == 3 }
        let progress = awaitEvent("q1") { self.int($0, "kind") == 1 && self.int($0, "position") > 0 }
        XCTAssertEqual(Double(int(progress, "duration")), 1_200, accuracy: 30)
        XCTAssertEqual(int(awaitEvent("q1") { self.int($0, "kind") == 3 }, "index"), 1)
        _ = awaitEvent("q1") { self.int($0, "kind") == 4 }
        XCTAssertTrue(AudioPlayers.get("q1")!.finished)
        XCTAssertNil(AudioPlayers.current())
    }

    func testPauseResumeSeekAndRateApplyToTheLivePlayer() {
        XCTAssertTrue(play("p1", ["voice/a.wav"], rate: 1.5, interval: 50))
        _ = awaitEvent("p1") { self.int($0, "kind") == 2 && self.int($0, "state") == 3 }
        let player = AudioPlayers.get("p1")!
        XCTAssertEqual(player.currentRate, 1.5, accuracy: 0.001)
        XCTAssertTrue(call("pause", ["playerId": .text("p1")]))
        _ = awaitEvent("p1") { self.int($0, "kind") == 2 && self.int($0, "state") == 4 }
        XCTAssertFalse(player.isPlaying)
        XCTAssertTrue(call("seek", ["playerId": .text("p1"), "position": .integer(600)]))
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        XCTAssertGreaterThanOrEqual(player.position, 590)
        XCTAssertTrue(call("setRate", ["playerId": .text("p1"), "rate": .decimal(2)]))
        XCTAssertEqual(player.currentRate, 2, accuracy: 0.001)
        XCTAssertTrue(call("resume", ["playerId": .text("p1")]))
        _ = awaitEvent("p1") { self.int($0, "kind") == 4 }
    }

    func testStartingAnotherPlayerStopsTheCurrentOne() {
        XCTAssertTrue(play("one", ["voice/a.wav"]))
        let first = AudioPlayers.get("one")!
        XCTAssertTrue(play("two", ["voice/b.wav"]))
        XCTAssertTrue(first.finished)
        XCTAssertEqual(AudioPlayers.current()?.id, "two")
        XCTAssertFalse(call("pause", ["playerId": .text("one")]))
    }

    func testAutoRouteSwitchesToTheEarpieceNearTheEarAndBack() {
        XCTAssertTrue(play("r1", ["voice/long.wav"], route: 1))
        let player = AudioPlayers.get("r1")!
        DispatchQueue.main.async { player.simulateProximity(true) }
        XCTAssertEqual(int(awaitEvent("r1") { self.int($0, "kind") == 6 }, "route"), 3)
        XCTAssertTrue(player.earpiece)
        XCTAssertEqual(AVAudioSession.sharedInstance().category, .playAndRecord)
        DispatchQueue.main.async { player.simulateProximity(false) }
        XCTAssertEqual(int(awaitEvent("r1") { self.int($0, "kind") == 6 }, "route"), 2)
        XCTAssertTrue(call("setRoute", ["playerId": .text("r1"), "route": .integer(2)]))
        DispatchQueue.main.async { player.simulateProximity(true) }
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        XCTAssertFalse(player.earpiece)
    }

    func testInvalidSourcesFailWithoutCrashing() {
        XCTAssertFalse(play("bad", ["../secret.wav"]))
        XCTAssertFalse(play("bad", ["/data/x.wav"]))
        XCTAssertTrue(play("missing", ["voice/missing.wav"]))
        let failure = awaitEvent("missing") { self.int($0, "kind") == 5 }
        if case let .text(message)? = failure["message"] { XCTAssertFalse(message.isEmpty) } else { XCTFail("message") }
        XCTAssertFalse(call("next", ["playerId": .text("nobody")]))
    }

    func testEventChannelCoalescesProgressTail() {
        let channel = EventChannel(capacity: 4)
        channel.offer(["kind": .integer(1), "position": .integer(1)], coalesce: true)
        channel.offer(["kind": .integer(1), "position": .integer(2)], coalesce: true)
        XCTAssertEqual(channel.pendingCount, 1)
        channel.offer(["kind": .integer(2)])
        channel.offer(["kind": .integer(1), "position": .integer(3)], coalesce: true)
        XCTAssertEqual(channel.pendingCount, 3)
    }
}
