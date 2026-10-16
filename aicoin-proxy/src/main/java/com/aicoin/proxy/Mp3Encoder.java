package com.aicoin.proxy;

import de.sciss.jump3r.lowlevel.LameEncoder;
import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFormat;

/**
 * Raw 16-bit PCM in, MP3 out.
 *
 * <p>Exists for one provider: Gemini's text-to-speech answers with bare little-endian PCM samples
 * (24 kHz mono, no header at all), where every other audio provider here answers with MP3. Relaying
 * the samples as they are would make {@code POST /audio}'s {@code media_type} depend on who answered
 * — the one thing that endpoint exists to stop a caller caring about — and would send a minute of
 * speech as ~2.9 MB instead of ~0.5 MB, to a phone, base64-inflated on top.
 *
 * <p>Mono at 64 kbps CBR: this is a voice reading a passage, not music, and a constant rate keeps
 * {@code AVAudioPlayer}'s seeking (the app resumes a passage part-way through) exact.
 */
final class Mp3Encoder {

    static final int SPEECH_BIT_RATE_KBPS = 64;

    private Mp3Encoder() {
    }

    /**
     * @param pcm        signed 16-bit little-endian samples, interleaved if {@code channels > 1}
     * @param sampleRate samples per second per channel
     */
    static byte[] fromPcm16(byte[] pcm, int sampleRate, int channels) {
        AudioFormat source = new AudioFormat(sampleRate, 16, channels, true, false);
        LameEncoder encoder = new LameEncoder(source, SPEECH_BIT_RATE_KBPS,
                channels == 1 ? LameEncoder.CHANNEL_MODE_MONO : LameEncoder.CHANNEL_MODE_JOINT_STEREO,
                LameEncoder.QUALITY_MIDDLE, false);
        try {
            ByteArrayOutputStream mp3 = new ByteArrayOutputStream(Math.max(pcm.length / 8, 1024));
            byte[] buffer = new byte[encoder.getMP3BufferSize()];
            int chunk = encoder.getPCMBufferSize();
            for (int offset = 0; offset < pcm.length; offset += chunk) {
                int written = encoder.encodeBuffer(pcm, offset, Math.min(chunk, pcm.length - offset), buffer);
                mp3.write(buffer, 0, written);
            }
            int tail = encoder.encodeFinish(buffer);
            mp3.write(buffer, 0, tail);
            return mp3.toByteArray();
        } finally {
            encoder.close();
        }
    }
}
