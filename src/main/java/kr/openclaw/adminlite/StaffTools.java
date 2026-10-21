package kr.openclaw.adminlite;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import java.nio.file.*;
import java.util.*;

final class StaffTools implements Listener {
    record Origin(Location location, GameMode mode, boolean allowFlight, boolean flying) {}
    private final AdminLitePlugin plugin;
    private final Path file;
    private final Map<UUID, Origin> origins = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> allowedTeleports = new HashSet<>();

    StaffTools(AdminLitePlugin plugin) throws Exception {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("recovery.yml");
        if (Files.exists(file)) {
            YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
            for (String id : yaml.getKeys(false)) {
                Location location = yaml.getLocation(id + ".location");
                if (location == null || location.getWorld() == null) throw new IllegalStateException("관전 복귀 월드를 찾을 수 없습니다: " + id);
                origins.put(UUID.fromString(id), new Origin(location, GameMode.valueOf(yaml.getString(id + ".mode")), yaml.getBoolean(id + ".allow-flight"), yaml.getBoolean(id + ".flying")));
            }
        }
    }

    void spectate(Player staff, Player target) throws Exception {
        if (staff.equals(target)) throw new IllegalArgumentException("자신을 관전할 수 없습니다.");
        if (!origins.containsKey(staff.getUniqueId())) {
            origins.put(staff.getUniqueId(), new Origin(staff.getLocation().clone(), staff.getGameMode(), staff.getAllowFlight(), staff.isFlying()));
            try { save(); } catch (Exception e) { origins.remove(staff.getUniqueId()); throw e; }
        }
        staff.closeInventory();
        staff.setGameMode(GameMode.SPECTATOR);
        if (!teleport(staff, target.getLocation())) throw new IllegalArgumentException("이동이 취소되었습니다. /admin spectate 로 원래 상태로 돌아갈 수 있습니다.");
        staff.setSpectatorTarget(target);
        plugin.tell(staff, "spectate-start", "%player%", plugin.name(target));
    }

    boolean restore(Player staff) throws Exception {
        Origin origin = origins.get(staff.getUniqueId());
        if (origin == null) return false;
        if (staff.getGameMode() == GameMode.SPECTATOR) staff.setSpectatorTarget(null);
        if (!teleport(staff, origin.location())) throw new IllegalArgumentException("복귀 이동이 취소되었습니다. 다시 시도하세요.");
        staff.setGameMode(origin.mode());
        staff.setAllowFlight(origin.allowFlight());
        staff.setFlying(origin.allowFlight() && origin.flying());
        origins.remove(staff.getUniqueId());
        try { save(); } catch (Exception e) { origins.put(staff.getUniqueId(), origin); throw e; }
        plugin.tell(staff, "spectate-end");
        return true;
    }

    boolean teleport(Player player, Location to) {
        allowedTeleports.add(player.getUniqueId());
        try { return player.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        finally { allowedTeleports.remove(player.getUniqueId()); }
    }

    boolean isSpectating(UUID uuid) { return origins.containsKey(uuid); }
    boolean isVanished(UUID uuid) { return vanished.contains(uuid); }

    void vanish(Player staff) {
        boolean on = !vanished.remove(staff.getUniqueId());
        if (on) vanished.add(staff.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) visibility(viewer, staff);
        plugin.tell(staff, on ? "vanish-on" : "vanish-off");
    }

    private void visibility(Player viewer, Player staff) {
        if (viewer.equals(staff)) return;
        if (vanished.contains(staff.getUniqueId()) && !plugin.maySeeHidden(viewer)) viewer.hidePlayer(plugin, staff);
        else viewer.showPlayer(plugin, staff);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (Player viewer : Bukkit.getOnlinePlayers()) visibility(viewer, player);
        for (UUID uuid : vanished) { Player staff = Bukkit.getPlayer(uuid); if (staff != null) visibility(player, staff); }
        if (origins.containsKey(player.getUniqueId())) Bukkit.getScheduler().runTask(plugin, () -> recover(player));
    }

    @EventHandler(priority = EventPriority.LOWEST) public void quit(PlayerQuitEvent event) {
        recover(event.getPlayer());
        vanished.remove(event.getPlayer().getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, event.getPlayer());
    }

    private void recover(Player player) {
        try { restore(player); }
        catch (Exception e) { plugin.getLogger().severe("관전 상태 복구 실패 (다음 접속 시 재시도): " + player.getUniqueId() + " " + e.getMessage()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void move(PlayerMoveEvent event) {
        if (!plugin.state.frozen.contains(event.getPlayer().getUniqueId()) || !event.hasChangedPosition()) return;
        Location back = event.getFrom().clone(); back.setYaw(event.getTo().getYaw()); back.setPitch(event.getTo().getPitch());
        event.setTo(back); event.getPlayer().setFallDistance(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.state.frozen.contains(uuid) && !allowedTeleports.contains(uuid)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && (vanished.contains(p.getUniqueId()) || plugin.state.frozen.contains(p.getUniqueId()))) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack) {
            Player attacker = attack.getDamager() instanceof Player p ? p : attack.getDamager() instanceof org.bukkit.entity.Projectile shot && shot.getShooter() instanceof Player p ? p : null;
            if (attacker != null && (vanished.contains(attacker.getUniqueId()) || plugin.state.frozen.contains(attacker.getUniqueId()))) event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void mount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player p && plugin.state.frozen.contains(p.getUniqueId())) event.setCancelled(true);
    }

    void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            recover(player);
            for (UUID uuid : vanished) { Player staff = Bukkit.getPlayer(uuid); if (staff != null) player.showPlayer(plugin, staff); }
        }
        vanished.clear();
    }

    private void save() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        origins.forEach((uuid, origin) -> {
            String id = uuid.toString(); yaml.set(id + ".location", origin.location()); yaml.set(id + ".mode", origin.mode().name());
            yaml.set(id + ".allow-flight", origin.allowFlight()); yaml.set(id + ".flying", origin.flying());
        });
        AtomicFiles.text(file, yaml.saveToString());
    }
}
