package app.jarvis.core.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SqlGuardTest {
    @Test public void allowsReads() {
        assertNull(SqlGuard.check("SELECT * FROM item WHERE conv_id=1 ORDER BY seq DESC LIMIT 5"));
        assertNull(SqlGuard.check("select count(*) from log;"));
        assertNull(SqlGuard.check("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertNull(SqlGuard.check("PRAGMA table_info(item)"));
        assertNull(SqlGuard.check("pragma user_version"));
        assertNull(SqlGuard.check("PRAGMA integrity_check"));
    }

    @Test public void keywordsInsideLiteralsAreFine() {
        assertNull(SqlGuard.check("SELECT * FROM log WHERE msg LIKE '%delete; drop table x%'"));
        assertNull(SqlGuard.check("SELECT 'it''s; insert' AS a -- update x\n"));
        assertNull(SqlGuard.check("SELECT /* drop */ 1"));
    }

    @Test public void rejectsWrites() {
        for (String s : new String[]{"DELETE FROM item", "insert into conv values (1)", "UPDATE log SET msg=''", "DROP TABLE log", "ALTER TABLE log ADD x",
            "CREATE TABLE t(a)", "REPLACE INTO conv VALUES(1)", "ATTACH DATABASE 'x' AS y", "VACUUM", "BEGIN", "WITH x AS (SELECT 1) DELETE FROM item",
            "SELECT 1; DELETE FROM item", "SELECT 1; SELECT 2", "PRAGMA user_version = 5", "PRAGMA writable_schema=1", "PRAGMA journal_mode=DELETE", "", "   ", ";",
            "SELECT * FROM item WHERE 1 UNION SELECT 1 /* ok */; drop table x"}) {
            assertNotNull("must reject: " + s, SqlGuard.check(s));
        }
    }
}
