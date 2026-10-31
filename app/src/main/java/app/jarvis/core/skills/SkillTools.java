package app.jarvis.core.skills;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** skills_list, skill_view and skill_manage for the agent. */
public final class SkillTools {
    private SkillTools() { }

    public static void register(ToolRegistry r, final SkillStore store) {
        r.add(ToolSpec.of("skills_list", "Навыки: имя, описание и путь к SKILL.md. Полный текст навыка — через skill_view."),
            "tools", 10_000, false, (a, ctx) -> {
                List<SkillDoc> all = store.listForUi();
                JSONArray arr = new JSONArray();
                for (SkillDoc d : all) {
                    arr.put(J.put(J.put(J.put(new JSONObject(), "name", d.name), "description", d.description), "path", "skills/" + d.name + "/SKILL.md"));
                }
                return ToolResult.ok(J.put(new JSONObject(), "skills", arr), all.size() + " навыков");
            });
        r.add(ToolSpec.of("skill_view", "Открыть навык: SKILL.md (шаги, команды, ловушки) или один его файл из references/, templates/, scripts/, assets/.",
                Param.str("name", "Имя навыка.", true),
                Param.str("file", "Файл внутри навыка, например references/api.md. Пусто — SKILL.md и список его файлов.", false)),
            "tools", 10_000, false, (a, ctx) -> {
                String name = J.str(a, "name"), file = J.str(a, "file");
                try {
                    JSONObject o = J.put(J.put(new JSONObject(), "name", name), "file", file.isEmpty() ? SkillStore.SKILL_MD : file);
                    J.put(o, "content", store.read(name, file));
                    if (file.isEmpty()) J.put(o, "files", new JSONArray(store.supportingFiles(name)));
                    return ToolResult.ok(o, name);
                } catch (IOException e) {
                    return ToolResult.fail(e.getMessage() + available(store));
                }
            });
        r.add(new ToolSpec("skill_manage", MANAGE_DESCRIPTION, manageSchema()),
            "tools", 10_000, true, (a, ctx) -> {
                JSONArray arr = a.optJSONArray("operations");
                if (arr == null || arr.length() == 0) return ToolResult.fail("Нужен operations: непустой массив операций");
                List<SkillStore.Op> ops = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) return ToolResult.fail("Операция " + (i + 1) + " не является объектом");
                    ops.add(parseOp(o));
                }
                try {
                    List<String> res = store.apply(ops);
                    return ToolResult.ok(J.put(new JSONObject(), "results", new JSONArray(res)), String.join("; ", res));
                } catch (SkillException e) {
                    JSONObject d = new JSONObject();
                    if (e.op > 0) J.put(d, "failed_op", e.op);
                    return ToolResult.fail(e.getMessage() + ". Ни одна операция не сохранена: исправьте и отправьте батч заново.", d);
                } catch (IOException e) {
                    return ToolResult.fail("Не удалось сохранить навык: " + e.getMessage());
                }
            });
    }

    static final String MANAGE_DESCRIPTION =
        "Создать, изменить или удалить навык — процедурную память для повторяющихся задач. operations — список операций, "
        + "применяется атомарно: любая ошибка отменяет все изменения. Операции: create (полный SKILL.md; навыка ещё нет), "
        + "patch (точечная замена old_string → new_string — предпочтительно; content переписывает весь SKILL.md, только в крайнем случае; "
        + "сначала прочитайте навык через skill_view), write_file / remove_file (файлы в references/, templates/, scripts/, assets/), "
        + "delete (только единственной операцией). Шапка: description «Используй, когда <триггер>. <поведение>.» до 300 символов, "
        + "не название категории. Пишите уроки, а не журнал: императивное правило и причина; без дат, номеров задач и рассказа об инциденте; "
        + "одно правило на урок; сначала дополняйте существующие файлы, потом создавайте новые.";

    private static String available(SkillStore store) {
        List<String> names = new ArrayList<>();
        for (SkillDoc d : store.listForUi()) names.add(d.name);
        return names.isEmpty() ? " Навыков пока нет." : " Доступные: " + String.join(", ", names) + ".";
    }

    static SkillStore.Op parseOp(JSONObject o) {
        SkillStore.Op op = new SkillStore.Op();
        op.action = J.str(o, "action");
        op.name = J.str(o, "name");
        op.content = opt(o, "content");
        op.oldString = opt(o, "old_string");
        op.newString = opt(o, "new_string");
        op.filePath = opt(o, "file_path");
        op.fileContent = opt(o, "file_content");
        op.replaceAll = J.bool(o, "replace_all", false);
        return op;
    }

    private static String opt(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key) ? o.optString(key) : null;
    }

    /** One branch per action, as in Hermes: a model cannot put a file body into a create, or content into a file write. */
    static JSONObject manageSchema() {
        JSONArray variants = new JSONArray();
        variants.put(branch("create", props("content", prop("Полный текст SKILL.md: шапка «---» с name и description, затем инструкция.")), "content"));
        variants.put(branch("patch", props(
            "old_string", prop("Точный фрагмент, который нужно заменить (должен встречаться один раз, иначе укажите replace_all)."),
            "new_string", prop("Замена; пустая строка удаляет фрагмент."),
            "replace_all", bool("Заменить все вхождения."),
            "file_path", prop("Файл внутри навыка, например references/api.md. По умолчанию SKILL.md.")), "old_string", "new_string"));
        variants.put(branch("patch", props("content", prop("Полный новый SKILL.md (только крайний случай).")), "content"));
        variants.put(branch("write_file", props(
            "file_path", prop("Путь внутри навыка: references/…, templates/…, scripts/… или assets/…"),
            "file_content", prop("Текст файла (UTF-8).")), "file_path", "file_content"));
        variants.put(branch("remove_file", props("file_path", prop("Путь внутри навыка.")), "file_path"));
        variants.put(branch("delete", new JSONObject()));
        JSONObject items = J.put(new JSONObject(), "anyOf", variants);
        JSONObject ops = J.put(J.put(new JSONObject(), "type", "array"), "items", items);
        J.put(ops, "description", "Упорядоченные операции; каждая называет навык (строчные латинские буквы, цифры, «-», «_»).");
        J.put(ops, "minItems", 1);
        JSONObject root = J.put(new JSONObject(), "type", "object");
        J.put(root, "properties", J.put(new JSONObject(), "operations", ops));
        J.put(root, "required", new JSONArray().put("operations"));
        J.put(root, "additionalProperties", false);
        return root;
    }

    private static JSONObject branch(String action, JSONObject extra, String... required) {
        JSONObject p = new JSONObject();
        J.put(p, "action", J.put(new JSONObject(), "enum", new JSONArray().put(action)));
        J.put(p, "name", prop("Имя навыка."));
        Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            J.put(p, k, extra.opt(k));
        }
        JSONArray req = new JSONArray().put("action").put("name");
        for (String r : required) req.put(r);
        JSONObject s = J.put(new JSONObject(), "type", "object");
        J.put(s, "properties", p);
        J.put(s, "required", req);
        J.put(s, "additionalProperties", false);
        return s;
    }

    private static JSONObject props(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) J.put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    private static JSONObject prop(String desc) {
        return J.put(J.put(new JSONObject(), "type", "string"), "description", desc);
    }

    private static JSONObject bool(String desc) {
        return J.put(J.put(new JSONObject(), "type", "boolean"), "description", desc);
    }
}
