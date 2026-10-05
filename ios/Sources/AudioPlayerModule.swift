import Foundation
import PamNative

/// iOS playback (AVQueuePlayer) is not shipped in 0.1; calls fail with a typed message instead of crashing.
public final class AudioPlayerModule: NativeModule, @unchecked Sendable {
    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        completion(.failure, Data("pam-native-audio 0.1 supports Android only; iOS playback is planned.".utf8))
    }
}
