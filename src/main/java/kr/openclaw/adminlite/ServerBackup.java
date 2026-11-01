package kr.openclaw.adminlite;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.*;

/** Zips worlds, plugin data and server settings into backups/ on demand. CoreProtect's large database is included only for "full". */
final class ServerBackup {
    record Result(Path file, long bytes, int files, List<String> skipped) {}
    /** Regenerable or temporary folders at the server root. */
    static final Set<String> SKIP_ROOT = Set.of("backups", "bluemap", "logs", "cache", "libraries", "versions", "crash-reports", "debug", "staged-updates", "plugin-backups");
    static final String COREPROTECT_DB = "plugins/CoreProtect/database.db";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss");
    private final AdminLitePlugin plugin;
    private final Path root;
    private volatile boolean running;

    ServerBackup(AdminLitePlugin plugin, Path root) { this.plugin = plugin; this.root = root; }

    void start(CommandSender sender, boolean full) {
        if (running) throw new IllegalArgumentException("이미 백업이 진행 중입니다. 끝나면 알려 드립니다.");
        running = true;
        // Flush everything, then keep worlds from rewriting region files while they are copied.
        Bukkit.savePlayers();
        var autosave = new LinkedHashMap<World, Boolean>();
        for (World world : Bukkit.getWorlds()) { autosave.put(world, world.isAutoSave()); world.save(); world.setAutoSave(false); }
        int keep = Math.clamp(plugin.getConfig().getInt("backup.keep", 10), 1, 100);
        Path zip = root.resolve("backups").resolve("backup-" + STAMP.format(LocalDateTime.now()) + (full ? "-full" : "") + ".zip");
        plugin.tellRaw(sender, "&e서버 백업을 시작합니다" + (full ? " (CoreProtect 기록 포함)" : "") + ". 끝나면 알려 드립니다.");
        long started = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result = null; Exception failure = null;
            try { result = write(root, zip, full); prune(zip.getParent(), keep); }
            catch (Exception error) { failure = error; }
            Result done = result; Exception failed = failure;
            if (!plugin.isEnabled()) { running = false; return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                autosave.forEach(World::setAutoSave); running = false;
                if (failed != null) {
                    plugin.tellRaw(sender, "&c백업에 실패했습니다. 서버 콘솔을 확인하세요. 자동 저장은 다시 켰습니다.");
                    plugin.getLogger().log(Level.SEVERE, "서버 백업 실패", failed); return;
                }
                long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
                plugin.tellRaw(sender, "&a백업 완료: &fbackups/" + done.file().getFileName() + " &7· " + (done.bytes() / (1024 * 1024)) + "MB · 파일 " + done.files() + "개 · " + seconds + "초");
                if (!done.skipped().isEmpty()) {
                    plugin.tellRaw(sender, "&e읽지 못해 건너뛴 파일 " + done.skipped().size() + "개: &7" + String.join(", ", done.skipped().subList(0, Math.min(3, done.skipped().size()))));
                    plugin.getLogger().warning("백업에서 건너뛴 파일: " + done.skipped());
                }
                plugin.getLogger().info("서버 백업 완료: " + done.file() + " (" + done.bytes() + " bytes, " + done.files() + " files)");
            });
        });
    }

    /** Which server files go into a backup. {@code relative} uses '/' separators. */
    static boolean include(String relative, boolean full) {
        String first = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
        if (relative.contains("/") && SKIP_ROOT.contains(first)) return false;
        if (relative.endsWith("session.lock") || relative.endsWith(".part")) return false;
        if (!relative.contains("/")) return !relative.endsWith(".jar") && !relative.endsWith(".log");
        return full || !relative.startsWith(COREPROTECT_DB);
    }

    static Result write(Path root, Path zip, boolean full) throws IOException {
        Files.createDirectories(zip.getParent());
        Path part = zip.resolveSibling(zip.getFileName() + ".part");
        var skipped = new ArrayList<String>(); int[] files = {0};
        try (var out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part)))) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String relative = name(root, dir);
                    return relative.isEmpty() || !SKIP_ROOT.contains(relative) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    String relative = name(root, file);
                    if (!attributes.isRegularFile() || !include(relative, full)) return FileVisitResult.CONTINUE;
                    InputStream in;
                    // Files the server holds exclusively (Windows locks) are reported instead of failing the whole backup.
                    try { in = Files.newInputStream(file); } catch (IOException error) { skipped.add(relative); return FileVisitResult.CONTINUE; }
                    try (in) {
                        var entry = new ZipEntry(relative); entry.setLastModifiedTime(attributes.lastModifiedTime());
                        out.putNextEntry(entry); in.transferTo(out); out.closeEntry(); files[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) { skipped.add(name(root, file)); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException | RuntimeException error) { Files.deleteIfExists(part); throw error; }
        Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
        return new Result(zip, Files.size(zip), files[0], List.copyOf(skipped));
    }

    /** Deletes the oldest backups so at most {@code keep} remain. Only files this class names are touched. */
    static List<Path> prune(Path folder, int keep) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Path> backups;
        try (var list = Files.list(folder)) {
            backups = list.filter(p -> p.getFileName().toString().matches("backup-\\d{8}-\\d{6}(-full)?\\.zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().substring(7, 22)).reversed()).toList();
        }
        var removed = new ArrayList<Path>();
        for (Path old : backups.subList(Math.min(keep, backups.size()), backups.size())) { Files.deleteIfExists(old); removed.add(old); }
        return removed;
    }

    private static String name(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
}
