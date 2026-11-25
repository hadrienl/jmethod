import AVFoundation

@MainActor
final class SuccessAudio {
    private var player: AVAudioPlayer?

    func play(_ sound: SuccessSound) {
        guard let file = Bundle.main.url(forResource: sound.asset, withExtension: "wav", subdirectory: "raw") else { return }
        stop()
        do {
            // Mélange avec l’audio éventuel de l’utilisateur et respecte le mode silencieux.
            try AVAudioSession.sharedInstance().setCategory(.ambient)
            let next = try AVAudioPlayer(contentsOf: file)
            if next.play() { player = next }
        } catch {}
    }

    func stop() {
        player?.stop()
        player = nil
    }
}
