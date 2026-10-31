package app.jarvis.rt;

import app.jarvis.core.voice.SttProvider;
import java.util.List;

/** Ordered speech-recognition providers (user settings stt.chain / wake.stt) that are configured and ready. */
public interface SttHub {
    /** forWake=true puts the cheap/fast provider for continuous wake-word screening first. */
    List<SttProvider> chain(boolean forWake);

    SttProvider get(String id);
}
