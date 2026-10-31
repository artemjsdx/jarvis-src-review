package app.jarvis.rt;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.agent.Triggers;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** trigger_save: the agent creates or edits a phrase trigger. Writes the same triggers.json the settings page edits. */
public final class TriggerTools {
    public static final String FILE = "triggers.json";

    private TriggerTools() { }

    public static void register(final ToolRegistry r, final File file) {
        r.add(ToolSpec.of("trigger_save",
                "Сохранить фразу-триггер: «когда пользователь говорит X — сделай Y». mode=direct: готовое действие без нейросети (morning, music.playFavorite, "
                    + "app.open:<раздел>, tools.call:<имя>:<json>). mode=llm: инструкция, которую модель выполнит, когда услышит фразу. "
                    + "Вызывай только после того, как пользователь подтвердил фразу и действие в чате. Тот же id заменяет существующий триггер.",
                Param.str("phrases", "Фразы через «|», например «включи радио|радио». До 10.", true),
                Param.str("mode", "direct — готовое действие; llm — инструкция для модели.", true),
                Param.str("action", "Для mode=direct: morning | music.playFavorite | app.open:<раздел> | tools.call:<имя>:<json>.", false),
                Param.str("instruction", "Для mode=llm: что сделать, например «спроси, нужен ли перерыв».", false),
                Param.str("id", "Id существующего триггера, чтобы изменить его. Пусто — новый.", false),
                Param.bool("enabled", "Включён ли триггер. По умолчанию да.", false)),
            "tools", 10_000, true, (a, ctx) -> {
                List<String> phrases = Triggers.splitPhrases(J.str(a, "phrases"));
                if (phrases.isEmpty()) return ToolResult.fail("Укажите хотя бы одну фразу в phrases (через «|»).");
                if (phrases.size() > Triggers.MAX_PHRASES) return ToolResult.fail("Фраз не больше " + Triggers.MAX_PHRASES + ".");
                String modeText = J.str(a, "mode").trim().toLowerCase(java.util.Locale.ROOT);
                if (!modeText.equals("direct") && !modeText.equals("llm")) return ToolResult.fail("mode: direct или llm.");
                Triggers.Mode mode = Triggers.Mode.parse(modeText);
                String action = J.str(a, "action").trim(), instruction = J.str(a, "instruction").trim();
                if (mode == Triggers.Mode.DIRECT) {
                    HashSet<String> names = new HashSet<>(r.names());
                    String bad = Triggers.checkAction(action, names);
                    if (bad != null) return ToolResult.fail(bad);
                } else {
                    action = "";
                    if (instruction.isEmpty()) return ToolResult.fail("Для mode=llm нужна instruction: что модели сделать.");
                }
                boolean enabled = a.optBoolean("enabled", true);
                try {
                    Triggers all = Triggers.load(file);
                    Triggers.Trigger saved = all.upsert(J.str(a, "id"), phrases, mode, action, instruction, enabled);
                    all.save(file);
                    JSONObject data = J.put(J.put(new JSONObject(), "id", saved.id), "phrases", new JSONArray(saved.phrases));
                    J.put(data, "mode", mode == Triggers.Mode.LLM ? "llm" : "direct");
                    return ToolResult.ok(data, "Триггер сохранён: «" + saved.phrases.get(0) + "»");
                } catch (IOException e) {
                    return ToolResult.fail("Не удалось сохранить триггер: " + e.getMessage());
                }
            });
    }

}
