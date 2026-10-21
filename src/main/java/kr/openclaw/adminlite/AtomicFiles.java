package kr.openclaw.adminlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class AtomicFiles {
    static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void text(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }
}
