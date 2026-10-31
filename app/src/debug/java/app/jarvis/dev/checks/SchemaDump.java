package app.jarvis.dev.checks;

import app.jarvis.core.llm.ToolSpec;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import java.io.File;

/** Prints every registered tool spec as one JSON line ("name" + serialized OpenAI tool object); input for tools/bench/schema_tokens.py. args: <apk> <outDir> */
public final class SchemaDump {
    public static void main(String[] a) {
        try {
            PreviewHost.init(a[0], new File(a[1]));
            Jarvis j = PreviewKit.jarvis();
            for (ToolSpec s : j.tools.specs()) System.out.println("SPEC\t" + s.name + "\t" + s.toJson(false));
            System.out.println("SPECS DONE " + j.tools.specs().size());
        } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); }
        System.out.flush();
        System.exit(0);
    }
}
