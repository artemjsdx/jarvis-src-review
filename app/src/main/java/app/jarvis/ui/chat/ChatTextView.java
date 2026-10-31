package app.jarvis.ui.chat;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

/**
 * Message text: selectable (double-tap selects a word, handles are movable) but a long press opens the message menu
 * instead of starting drag-selection, so both behaviors live on one bubble. TextView.performLongClick would otherwise
 * start selection and never call the listener.
 */
public final class ChatTextView extends TextView {
    private View.OnLongClickListener menu;

    public ChatTextView(Context c) {
        super(c);
        setTextIsSelectable(true);
        setLongClickable(true);
    }

    public void setMenuListener(View.OnLongClickListener l) { menu = l; }

    @Override public boolean performLongClick() {
        if (menu != null && menu.onLongClick(this)) return true;
        return super.performLongClick();
    }
}
