package app.jarvis.ui;

import app.jarvis.R;
import app.jarvis.ui.screens.AgentScreen;
import app.jarvis.ui.screens.FilesScreen;
import app.jarvis.ui.screens.HomeScreen;
import app.jarvis.ui.screens.LogScreen;
import app.jarvis.ui.screens.SettingsScreen;
import app.jarvis.ui.screens.StudioScreen;
import app.jarvis.ui.screens.TerminalScreen;

/** Registry of top-level destinations (order = dock order; settings lives behind the header gear). */
public final class Screens {
    private Screens() { }

    public static final String[] DOCK = {"home", "agent", "files", "terminal", "studio", "log"};
    public static final String[] DOCK_LABELS = {"Главная", "Агент", "Файлы", "Терминал", "Голос", "Журнал"};
    public static final int[] DOCK_ICONS = {R.drawable.ic_home, R.drawable.ic_bot, R.drawable.ic_folder, R.drawable.ic_terminal, R.drawable.ic_voice, R.drawable.ic_log};

    public static Screen create(String id, Host h) {
        switch (id) {
            case "home": return new HomeScreen(h);
            case "agent": return new AgentScreen(h);
            case "files": return new FilesScreen(h);
            case "terminal": return new TerminalScreen(h);
            case "studio": return new StudioScreen(h);
            case "log": return new LogScreen(h);
            case "settings": return new SettingsScreen(h);
            default: return null;
        }
    }
}
