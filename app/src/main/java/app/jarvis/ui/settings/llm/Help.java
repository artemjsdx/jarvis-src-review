package app.jarvis.ui.settings.llm;

import android.view.View;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Host;

/** All LLM settings question marks use the same illustrated, paginated guide as the rest of Jarvis. */
public final class Help {
    private Help() { }

    public static View button(final Host host, final String title, final String text) {
        String body = text == null ? "" : text.trim();
        // Existing LLM descriptions are individually authored. Split long paragraphs into steps
        // rather than displaying them in an unscrollable plain dialog.
        String[] pages = body.isEmpty() ? new String[]{"Настройка: " + title}
            : body.split("\\n\\s*\\n+");
        return HelpPopup.button(host.context(), host, title, pages);
    }
}
