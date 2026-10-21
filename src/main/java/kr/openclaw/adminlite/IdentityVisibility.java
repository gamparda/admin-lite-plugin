package kr.openclaw.adminlite;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

final class IdentityVisibility implements Listener {
    interface Transport extends AutoCloseable {
        void refresh();
        @Override void close();
    }
    private final AdminLitePlugin plugin;
    final VisibilitySettings settings;
    private final Transport transport;
    private final BukkitTask task;
    private volatile Map<UUID, VisibilitySettings.Flags> policy = Map.of();
    IdentityVisibility(AdminLitePlugin plugin) throws Exception {
        this.plugin = plugin;
        settings = new VisibilitySettings(plugin.getDataFolder().toPath().resolve("visibility.yml"));
        transport = Bukkit.getPluginManager().isPluginEnabled("packetevents") ? new VisibilityPackets(this) : null;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1, 20);
    }
    Map<UUID, VisibilitySettings.Flags> policy() { return policy; }
    boolean available() { return transport != null; }
    VisibilitySettings.Flags get(UUID uuid) { return settings.get(uuid); }
    void change(Player player, String scope, String requested) throws Exception {
        plugin.checkPermission(player, "visibility");
        VisibilitySettings.Flags before = get(player.getUniqueId());
        boolean current = scope.equals("hidetab") ? before.tab() : scope.equals("hidename") ? before.name() : before.tab() && before.name();
        boolean on = VisibilitySettings.requested(requested, current);
        if (on) {
            plugin.feature("visibility");
            if (!available()) throw new IllegalArgumentException(plugin.cfg("visibility-unavailable"));
        }
        VisibilitySettings.Flags updated = new VisibilitySettings.Flags(scope.equals("hidename") ? before.tab() : on,
                scope.equals("hidetab") ? before.name() : on);
        settings.set(player.getUniqueId(), updated);
        refresh();
        plugin.tell(player, "visibility-status", "%tab%", updated.tab() ? "ON" : "OFF", "%name%", updated.name() ? "ON" : "OFF");
    }
    void refresh() {
        Map<UUID, VisibilitySettings.Flags> updated = new HashMap<>();
        if (plugin.enabled("visibility") && transport != null) for (Player player : Bukkit.getOnlinePlayers()) {
            VisibilitySettings.Flags flags = get(player.getUniqueId());
            if (flags.any() && plugin.allowed(player, "use") && plugin.allowed(player, "visibility")) updated.put(player.getUniqueId(), flags);
        }
        Map<UUID, VisibilitySettings.Flags> next = Map.copyOf(updated);
        if (!next.equals(policy)) { policy = next; transport.refresh(); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { refresh(); }
    void shutdown() { task.cancel(); policy = Map.of(); if (transport != null) transport.close(); }
}
