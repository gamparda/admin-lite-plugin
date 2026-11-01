package kr.openclaw.adminlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class ServerBackupTest {
    @TempDir Path dir;

    @Test void keepsWorldsPluginDataAndSettingsButNotRegenerableFiles() {
        for (String kept : List.of("world/level.dat", "world/dimensions/minecraft/the_end/region/r.0.0.mca", "plugins/AdminLite/config.yml",
                "plugins/admin-lite-2.3.3.jar", "server.properties", "banned-players.json", "start.bat", "config/paper-global.yml"))
            assertTrue(ServerBackup.include(kept, false), kept);
        for (String dropped : List.of("world/session.lock", "paper-26.3-222.jar", "logs/latest.log", "bluemap/web/tiles/x.png",
                "backups/backup-20261101-120000.zip", "cache/mojang.jar", "libraries/a.jar", "plugin-backups/x.jar", "plugins/CoreProtect/database.db"))
            assertFalse(ServerBackup.include(dropped, false), dropped);
        assertTrue(ServerBackup.include("plugins/CoreProtect/database.db", true));
        assertTrue(ServerBackup.include("plugins/CoreProtect/config.yml", false));
    }

    @Test void writesAZipWithForwardSlashPathsAndNoPartFileLeft() throws Exception {
        Path root = dir.resolve("server");
        for (String file : List.of("world/level.dat", "world/session.lock", "plugins/CoreProtect/database.db", "plugins/CoreProtect/config.yml", "logs/latest.log", "server.properties")) {
            Files.createDirectories(root.resolve(file).getParent()); Files.writeString(root.resolve(file), file);
        }
        Path zip = root.resolve("backups/backup-20261101-120000.zip");
        var result = ServerBackup.write(root, zip, false);
        try (var archive = new ZipFile(zip.toFile())) {
            var names = archive.stream().map(e -> e.getName()).sorted().toList();
            assertEquals(List.of("plugins/CoreProtect/config.yml", "server.properties", "world/level.dat"), names);
        }
        assertEquals(3, result.files()); assertTrue(result.skipped().isEmpty());
        assertFalse(Files.exists(zip.resolveSibling(zip.getFileName() + ".part")));
        var full = ServerBackup.write(root, root.resolve("backups/backup-20261101-120001-full.zip"), true);
        assertEquals(4, full.files());
    }

    @Test void pruneKeepsTheNewestBackupsAndIgnoresOtherFiles() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("backups"));
        for (String name : List.of("backup-20261101-090000.zip", "backup-20261102-090000-full.zip", "backup-20261103-090000.zip", "notes.txt", "backup-old.zip"))
            Files.writeString(folder.resolve(name), name);
        var removed = ServerBackup.prune(folder, 2);
        assertEquals(List.of(folder.resolve("backup-20261101-090000.zip")), removed);
        assertTrue(Files.exists(folder.resolve("backup-20261102-090000-full.zip")));
        assertTrue(Files.exists(folder.resolve("notes.txt"))); assertTrue(Files.exists(folder.resolve("backup-old.zip")));
    }
}
