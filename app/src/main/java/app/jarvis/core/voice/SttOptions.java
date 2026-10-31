package app.jarvis.core.voice;

public final class SttOptions {
    public String language = "ru";
    /** Optional vocabulary hint, e.g. "Джарвис". Providers that support prompts use it. */
    public String hint = "";
    /** Upload container preference: auto|opus|flac|wav (setting stt.format). */
    public String format = "auto";
    public AudioPacker packer = AudioPacker.WAV_ONLY;
}
