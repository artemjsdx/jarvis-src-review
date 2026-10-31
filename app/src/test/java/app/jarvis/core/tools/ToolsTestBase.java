package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.nio.file.Files;

public abstract class ToolsTestBase {
    protected File root;
    protected Sandbox sb;
    protected ToolRegistry reg;

    protected void init() throws Exception {
        root = Files.createTempDirectory("jv-sb").toFile();
        sb = new Sandbox(root);
        reg = new ToolRegistry();
        FsTools.register(reg, sb);
    }

    protected ToolResult call(String name, String argsJson) {
        return reg.execute(new ToolCall("t", name, argsJson), "run", new CancelToken(), Journal.NOP, null);
    }
}
