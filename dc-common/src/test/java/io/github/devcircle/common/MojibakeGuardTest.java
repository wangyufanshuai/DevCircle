package io.github.devcircle.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MojibakeGuardTest {
    private static final List<String> TEXT_EXTENSIONS = List.of(".java", ".md", ".vue", ".ts", ".http", ".yml", ".yaml");
    private static final List<String> MOJIBAKE_MARKERS = List.of(
            "\u9428", "\u93BA", "\u93C4", "\u7EE0", "\u7039", "\u5A34", "\u9359", "\u6769", "\u9A9E", "\uFFFD"
    );

    @Test
    void repositoryTextFilesDoNotContainCommonChineseMojibake() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        List<Path> offendingFiles;
        try (var paths = Files.walk(root)) {
            offendingFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isTrackedTextCandidate)
                    .filter(this::containsMojibake)
                    .toList();
        }
        assertThat(offendingFiles).isEmpty();
    }

    private boolean isTrackedTextCandidate(Path path) {
        String value = path.toString().replace('\\', '/');
        if (value.contains("/.git/") || value.contains("/node_modules/") || value.contains("/target/") || value.contains("/dist/")) {
            return false;
        }
        return TEXT_EXTENSIONS.stream().anyMatch(value::endsWith);
    }

    private boolean containsMojibake(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return MOJIBAKE_MARKERS.stream().anyMatch(content::contains);
        } catch (IOException ex) {
            return false;
        }
    }
}
