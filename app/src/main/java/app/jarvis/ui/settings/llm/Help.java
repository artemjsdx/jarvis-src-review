package app.jarvis.ui.settings.llm;

import android.view.View;
import app.jarvis.R;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;

/** A small "?" button that explains a setting in a plain dialog. */
public final class Help {
    private Help() { }

    public static View button(final Host host, final String title, final String text) {
        return Ui.iconButton(host.context(), R.drawable.ic_info, 28, Ui.P.text3, new Runnable() { @Override public void run() {
            Dialogs.info(host, title, text);
        } });
    }
}
