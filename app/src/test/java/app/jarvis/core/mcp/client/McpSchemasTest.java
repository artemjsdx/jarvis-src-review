package app.jarvis.core.mcp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class McpSchemasTest {

    @Test public void exposedNamesUseOnlySafeCharactersAndStayWithin64() {
        assertEquals("tg__send_message", McpSchemas.exposedName("tg", "send_message"));
        assertEquals("my_server__get_chat_info", McpSchemas.exposedName("my.server", "get/chat info"));
        String longName = McpSchemas.exposedName("telegram-account", new String(new char[80]).replace('\0', 'x'));
        assertTrue(longName.length() <= 64);
        assertTrue(longName.matches("[a-zA-Z0-9_-]{1,64}"));
    }

    @Test public void defaultPrefixFromIdIsSafe() {
        assertEquals("telegram_1", McpSchemas.defaultPrefix("telegram-1"));
        assertEquals("x", McpSchemas.clean("", 8));
    }

    @Test public void sanitizeKeepsKnownKeywordsAndDropsTheRest() throws Exception {
        JSONObject in = new JSONObject(
            "{\"type\":\"object\",\"$schema\":\"x\",\"title\":\"T\",\"properties\":{"
            + "\"chat\":{\"type\":[\"string\",\"null\"],\"description\":\"чат\",\"format\":\"uri\",\"$ref\":\"#/x\"},"
            + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"default\":5},"
            + "\"mode\":{\"anyOf\":[{\"type\":\"string\"}],\"enum\":[\"a\",\"b\"]}"
            + "},\"required\":[\"chat\",\"missing\"],\"additionalProperties\":true}");
        JSONObject out = McpSchemas.sanitize(in);
        assertEquals("object", out.getString("type"));
        assertFalse(out.has("$schema"));
        assertFalse(out.has("title"));
        JSONObject props = out.getJSONObject("properties");
        JSONObject chat = props.getJSONObject("chat");
        assertEquals("string", chat.getString("type"));
        assertEquals("чат", chat.getString("description"));
        assertFalse(chat.has("format"));
        assertFalse(chat.has("$ref"));
        JSONObject count = props.getJSONObject("count");
        assertEquals(1, count.getInt("minimum"));
        assertFalse(count.has("default"));
        JSONObject mode = props.getJSONObject("mode");
        assertFalse(mode.has("anyOf"));
        assertEquals(2, mode.getJSONArray("enum").length());
        JSONArray req = out.getJSONArray("required");
        assertEquals(1, req.length());
        assertEquals("chat", req.getString(0));
        assertEquals(false, out.getBoolean("additionalProperties"));
    }

    @Test public void nestedObjectsAndArraysAreSanitizedRecursively() throws Exception {
        JSONObject in = new JSONObject(
            "{\"type\":\"object\",\"properties\":{\"opts\":{\"type\":\"object\",\"format\":\"x\","
            + "\"properties\":{\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"pattern\":\"a+\"}}}}}}");
        JSONObject out = McpSchemas.sanitize(in);
        JSONObject tags = out.getJSONObject("properties").getJSONObject("opts").getJSONObject("properties").getJSONObject("tags");
        JSONObject items = tags.getJSONObject("items");
        assertEquals("string", items.getString("type"));
        assertFalse(items.has("pattern"));
        assertFalse(out.getJSONObject("properties").getJSONObject("opts").has("format"));
    }

    @Test public void missingSchemaBecomesEmptyObjectSchema() {
        JSONObject out = McpSchemas.sanitize(null);
        assertEquals("object", out.optString("type"));
        assertEquals(0, out.getJSONObject("properties").length());
    }

    @Test public void descriptionsAreShortenedAndCarryTheDataNotice() {
        String long300 = new String(new char[300]).replace('\0', 'а');
        String d = McpSchemas.describe(long300);
        assertTrue(d.endsWith(McpSchemas.DATA_NOTICE));
        assertTrue(d.length() <= McpSchemas.DESC_MAX + McpSchemas.DATA_NOTICE.length());
        assertTrue(McpSchemas.describe("  много   пробелов\n\nтут ").startsWith("много пробелов тут"));
    }
}
