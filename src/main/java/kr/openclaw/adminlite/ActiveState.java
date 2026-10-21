package kr.openclaw.adminlite;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class ActiveState {
    record Mute(String name, String reason, long until) {
        boolean active(long now) { return until == 0 || until > now; }
        String remaining() { return TimeSpec.label(until == 0 ? 0 : Math.max(1, until - System.currentTimeMillis())); }
    }
    private final Path file;
    final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    ActiveState(Path file) throws Exception {
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        var section = yaml.getConfigurationSection("mutes");
        if (section != null) for (String key : section.getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            long until = section.getLong(key + ".until");
            if (until < 0) throw new IllegalStateException("Invalid mute expiry: " + key);
            Mute mute = new Mute(section.getString(key + ".name", uuid.toString()), section.getString(key + ".reason", "사유 없음"), until);
            if (mute.active(System.currentTimeMillis())) mutes.put(uuid, mute);
        }
        for (String uuid : yaml.getStringList("frozen")) frozen.add(UUID.fromString(uuid));
    }

    Mute mute(UUID uuid) {
        Mute mute = mutes.get(uuid);
        return mute != null && mute.active(System.currentTimeMillis()) ? mute : null;
    }

    void setMute(UUID uuid, Mute mute) throws Exception {
        Mute before = mute == null ? mutes.remove(uuid) : mutes.put(uuid, mute);
        try { save(); }
        catch (Exception e) {
            if (before == null) mutes.remove(uuid); else mutes.put(uuid, before);
            throw e;
        }
    }

    boolean toggleFrozen(UUID uuid) throws Exception {
        boolean enabled = !frozen.remove(uuid);
        if (enabled) frozen.add(uuid);
        try { save(); }
        catch (Exception e) { if (enabled) frozen.remove(uuid); else frozen.add(uuid); throw e; }
        return enabled;
    }

    List<UUID> expire() throws Exception {
        List<UUID> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        mutes.forEach((uuid, mute) -> { if (!mute.active(now) && mutes.remove(uuid, mute)) expired.add(uuid); });
        if (!expired.isEmpty()) save();
        return expired;
    }

    void save() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        mutes.forEach((uuid, mute) -> {
            String key = "mutes." + uuid;
            yaml.set(key + ".name", mute.name()); yaml.set(key + ".reason", mute.reason()); yaml.set(key + ".until", mute.until());
        });
        yaml.set("frozen", frozen.stream().map(UUID::toString).sorted().toList());
        AtomicFiles.text(file, yaml.saveToString());
    }
}
