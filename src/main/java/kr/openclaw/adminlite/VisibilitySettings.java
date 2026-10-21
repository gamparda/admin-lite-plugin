package kr.openclaw.adminlite;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

/** Only the current display preferences; no audit history. */
final class VisibilitySettings {
    record Flags(boolean tab, boolean name) {
        static final Flags VISIBLE = new Flags(false, false);
        boolean any() { return tab || name; }
    }
    private final Path file;
    private final Map<UUID, Flags> values = new HashMap<>();
    VisibilitySettings(Path file) throws Exception {
        this.file = file;
        if (!Files.exists(file)) return;
        var yaml = new YamlConfiguration(); yaml.load(file.toFile());
        for (String key : yaml.getKeys(false)) {
            Flags flags = new Flags(yaml.getBoolean(key + ".hide-tab"), yaml.getBoolean(key + ".hide-name"));
            if (flags.any()) values.put(UUID.fromString(key), flags);
        }
    }
    Flags get(UUID uuid) { return values.getOrDefault(uuid, Flags.VISIBLE); }
    void set(UUID uuid, Flags flags) throws Exception {
        Flags before = values.get(uuid);
        if (flags.any()) values.put(uuid, flags); else values.remove(uuid);
        try {
            var yaml = new YamlConfiguration();
            values.forEach((id, value) -> { yaml.set(id + ".hide-tab", value.tab()); yaml.set(id + ".hide-name", value.name()); });
            AtomicFiles.text(file, yaml.saveToString());
        } catch (Exception e) { if (before == null) values.remove(uuid); else values.put(uuid, before); throw e; }
    }
    static boolean requested(String value, boolean current) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on" -> true; case "off" -> false; case "toggle" -> !current;
            default -> throw new IllegalArgumentException("on, off 또는 toggle을 입력하세요.");
        };
    }
}
