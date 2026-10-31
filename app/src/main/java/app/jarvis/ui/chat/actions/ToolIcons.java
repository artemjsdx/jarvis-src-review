package app.jarvis.ui.chat.actions;

/** Maps a tool name to the name of its vector drawable (resolved by the view; unknown tools get the wrench). */
public final class ToolIcons {
    private ToolIcons() { }

    public static String iconName(String tool) {
        String t = tool == null ? "" : tool;
        if (t.startsWith("fs_")) return "ic_folder";
        if (t.startsWith("web_search")) return "ic_search";
        if (t.startsWith("http_") || t.startsWith("web_")) return "ic_globe";
        if (t.startsWith("shell_") || t.startsWith("termux_")) return "ic_terminal";
        if (t.startsWith("memory_")) return "ic_brain";
        if (t.startsWith("music_")) return "ic_music";
        if (t.startsWith("tts_") || t.startsWith("voice_")) return "ic_voice";
        if (t.startsWith("note_")) return "ic_file_text";
        if (t.startsWith("download_")) return "ic_download";
        if (t.startsWith("notify")) return "ic_bell";
        return "ic_wrench";
    }
}
