package app.jarvis.core.voice;

import java.util.ArrayList;
import java.util.List;

public final class SttResult {
    public String text = "";
    /** 0..1, -1 unknown. */
    public float confidence = -1f;
    public final List<String> alternatives = new ArrayList<>();
    public String providerId = "";
    public long latencyMs;
    /** Size of the request body that carried the audio; 0 when no audio was uploaded (local recognition). */
    public long uploadedBytes;
    /** Container of the upload: wav, flac, opus; "" when nothing was uploaded. */
    public String uploadFormat = "";
}
