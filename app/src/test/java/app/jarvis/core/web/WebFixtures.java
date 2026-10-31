package app.jarvis.core.web;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Recorded provider payloads under app/src/test/resources/web, found by walking up from the working directory. */
final class WebFixtures {
    private WebFixtures() { }

    static String read(String name) throws IOException {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/web/" + name).isFile()) dir = dir.getParentFile();
        if (dir == null) throw new IOException("missing web fixture " + name);
        return new String(Files.readAllBytes(new File(dir, "app/src/test/resources/web/" + name).toPath()), StandardCharsets.UTF_8);
    }
}
