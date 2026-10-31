package app.jarvis.core.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Model list payloads under app/src/test/resources/models, found by walking up from the working directory. */
final class ModelFixtures {
    private ModelFixtures() { }

    static String read(String name) throws IOException {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/models/" + name).isFile()) dir = dir.getParentFile();
        if (dir == null) throw new IOException("fixture not found: " + name);
        return new String(Files.readAllBytes(new File(dir, "app/src/test/resources/models/" + name).toPath()), StandardCharsets.UTF_8);
    }
}
