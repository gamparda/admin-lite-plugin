package kr.openclaw.adminlite;
import kr.openclaw.adminlite.recovery.RecoveryYaml;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.ban.IpBanList;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.profile.PlayerProfile;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

/** Tells staff when a banned account or IP is refused at login, and when a banned account is whitelisted. Never changes who may join. */
final class BanAlerts implements Listener {
    record Attempt(String ip, String name, long at) {}
    static final int KEEP = 50;
    static final long REPEAT_MILLIS = 60_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final AdminLitePlugin plugin;
    private final Path file;
    final Deque<String> pending = new ArrayDeque<>();
    final Map<UUID, Attempt> bannedIpAccounts = new LinkedHashMap<>();
    private final Map<String, Long> recent = new HashMap<>();

    BanAlerts(AdminLitePlugin plugin, Path file) throws Exception {
        this.plugin = plugin; this.file = file;
        YamlConfiguration yaml = RecoveryYaml.data(file, BanAlerts::normalize, plugin == null ? s -> {} : plugin.getLogger()::warning);
        pending.addAll(yaml.getStringList("pending"));
        var section = yaml.getConfigurationSection("banned-ip-accounts");
        if (section != null) for (String id : section.getKeys(false))
            bannedIpAccounts.put(UUID.fromString(id), new Attempt(section.getString(id + ".ip"), section.getString(id + ".name"), section.getLong(id + ".at")));
    }

    /** Which check refused the login, in the order the server applies them: account ban, whitelist, IP ban. */
    static String cause(boolean accountBanned, boolean whitelistBlocked, boolean ipBanned, String kick) {
        if (accountBanned) return "계정 밴";
        if (whitelistBlocked) return "화이트리스트 미등록";
        if (ipBanned) return "IP 밴";
        return "기타 (" + kick + ")";
    }

    /** True when the same account and IP was already reported within the last minute. */
    boolean repeated(UUID uuid, String ip, long now) {
        recent.values().removeIf(at -> now - at >= REPEAT_MILLIS);
        return recent.putIfAbsent(uuid + "|" + ip, now) != null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void validate(PlayerConnectionValidateLoginEvent event) {
        if (event.isAllowed()) return;
        var profile = switch (event.getConnection()) {
            case PlayerLoginConnection login -> login.getAuthenticatedProfile();
            case PlayerConfigurationConnection configuration -> configuration.getProfile();
            default -> null;
        };
        if (profile == null || profile.getId() == null) return;
        var address = event.getConnection().getClientAddress();
        InetAddress ip = address == null ? null : address.getAddress();
        String kick = event.getKickMessage() == null ? "" : PlainTextComponentSerializer.plainText().serialize(event.getKickMessage());
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> refused(profile, ip, kick));
    }

    private void refused(PlayerProfile profile, InetAddress ip, String kick) {
        UUID uuid = profile.getUniqueId(); String name = profile.getName() == null ? uuid.toString() : profile.getName();
        var account = plugin.bans().getBanEntry(profile);
        IpBanList ipList = Bukkit.getBanList(BanList.Type.IP);
        var ipBan = ip == null ? null : ipList.getBanEntry(ip);
        if (account == null && ipBan == null) return;
        String address = ip == null ? "알 수 없음" : ip.getHostAddress();
        long now = System.currentTimeMillis();
        if (ipBan != null) { bannedIpAccounts.remove(uuid); bannedIpAccounts.put(uuid, new Attempt(address, name, now)); }
        boolean whitelistBlocked = Bukkit.hasWhitelist() && !Bukkit.getOfflinePlayer(uuid).isWhitelisted();
        if (repeated(uuid, address, now)) { save(); return; }
        var lines = new ArrayList<String>();
        lines.add("&c[밴 접속 시도] &f" + name + " &7(" + address + ") · 차단: &f" + cause(account != null, whitelistBlocked, ipBan != null, kick));
        if (account != null) lines.add("&7  계정 밴 · " + detail(account));
        if (ipBan != null) lines.add("&7  IP 밴 · " + detail(ipBan));
        deliver(lines);
    }

    /** Warns the staff member who just whitelisted an account that is banned or tried to join from a banned IP. */
    void whitelisted(CommandSender sender, OfflinePlayer player, String account) {
        try {
            var ban = plugin.bans().getBanEntry(player.getPlayerProfile());
            if (ban != null) {
                plugin.tellRaw(sender, "&c[주의] &f" + account + "&c은(는) 밴된 계정입니다. 밴을 풀기 전까지는 접속할 수 없습니다.");
                plugin.tellRaw(sender, "&7  " + detail(ban));
            }
            var attempt = bannedIpAccounts.get(player.getUniqueId());
            if (attempt != null) {
                InetAddress ip = InetAddress.ofLiteral(attempt.ip());
                IpBanList ipList = Bukkit.getBanList(BanList.Type.IP);
                var ipBan = ipList.getBanEntry(ip);
                plugin.tellRaw(sender, "&c[주의] &f" + account + "&c은(는) " + TIME.format(Instant.ofEpochMilli(attempt.at()).atZone(ZoneId.systemDefault()))
                        + "에 밴된 IP(" + attempt.ip() + ")로 접속을 시도한 계정입니다." + (ipBan == null ? " &7(지금은 IP 밴이 풀려 있습니다)" : ""));
                if (ipBan != null) plugin.tellRaw(sender, "&7  IP 밴 · " + detail(ipBan));
            }
            if (ban != null || attempt != null) plugin.getLogger().warning("밴 관련 계정을 화이트리스트에 등록했습니다: " + account + " (" + player.getUniqueId() + ") · 처리자: " + sender.getName());
        } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "화이트리스트 밴 확인 실패", error); }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (pending.isEmpty() || !plugin.allowed(player, "alerts")) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || pending.isEmpty()) return;
            plugin.tellRaw(player, "&e부재 중 밴 접속 시도 알림 " + pending.size() + "건");
            pending.forEach(line -> player.sendMessage(plugin.text(line)));
            pending.clear(); save();
        }, 60);
    }

    private void deliver(List<String> lines) {
        String at = "&8" + TIME.format(LocalDateTime.now()) + " ";
        lines.forEach(line -> plugin.getLogger().warning(org.bukkit.ChatColor.stripColor(line.replace('&', '§'))));
        var staff = Bukkit.getOnlinePlayers().stream().filter(p -> plugin.allowed(p, "alerts")).toList();
        if (staff.isEmpty()) {
            pending.addLast(at + lines.getFirst());
            for (String line : lines.subList(1, lines.size())) pending.addLast(line);
            while (pending.size() > KEEP) pending.removeFirst();
        } else for (Player player : staff) { plugin.tellRaw(player, lines.getFirst()); for (String line : lines.subList(1, lines.size())) player.sendMessage(plugin.text(line)); }
        save();
    }

    private static String detail(BanEntry<?> entry) {
        String reason = entry.getReason() == null || entry.getReason().isBlank() ? "사유 없음" : entry.getReason();
        String until = entry.getExpiration() == null ? "영구" : TIME.format(entry.getExpiration().toInstant().atZone(ZoneId.systemDefault())) + "까지";
        return "사유: " + reason + " · 처리자: " + entry.getSource() + " · " + until;
    }

    void save() {
        try {
            var yaml = new YamlConfiguration();
            yaml.set("pending", new ArrayList<>(pending));
            bannedIpAccounts.forEach((uuid, attempt) -> {
                yaml.set("banned-ip-accounts." + uuid + ".ip", attempt.ip());
                yaml.set("banned-ip-accounts." + uuid + ".name", attempt.name());
                yaml.set("banned-ip-accounts." + uuid + ".at", attempt.at());
            });
            AtomicFiles.text(file, yaml.saveToString());
        } catch (Exception error) { if (plugin != null) plugin.getLogger().log(Level.SEVERE, "밴 알림 기록 저장 실패", error); }
    }

    private static boolean normalize(YamlConfiguration yaml) {
        String before = yaml.saveToString();
        if (yaml.get("pending") != null && !yaml.isList("pending")) yaml.set("pending", null);
        var section = yaml.getConfigurationSection("banned-ip-accounts");
        if (yaml.get("banned-ip-accounts") != null && section == null) yaml.set("banned-ip-accounts", null);
        if (section != null) for (String id : List.copyOf(section.getKeys(false)))
            if (!RecoveryYaml.uuid(id) || !section.isString(id + ".ip")) section.set(id, null);
        return !before.equals(yaml.saveToString());
    }
}
