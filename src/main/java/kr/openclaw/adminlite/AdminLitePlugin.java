package kr.openclaw.adminlite;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class AdminLitePlugin extends org.bukkit.plugin.java.JavaPlugin implements TabExecutor {
    static final List<String> SUBCOMMANDS = List.of("menu", "players", "info", "invsee", "endersee", "warn", "freeze", "mute", "unmute", "kick", "ban", "unban", "spectate", "vanish", "visibility", "hidetab", "hidename", "hideidentity", "tp", "tphere", "broadcast", "chatlog", "deletechat", "clearchat", "reload", "help");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').extractUrls().build();
    private volatile Map<String, String> messages = Map.of();
    private volatile Set<String> disabledFeatures = Set.of();
    private volatile boolean opOnly = true;
    ActiveState state;
    InventoryEditor editor;
    StaffTools staff;
    IdentityVisibility visibility;
    ChatTools chat;
    AdminMenus menus;
    PlayerNames names = new PlayerNames(List::of);

    @Override public void onEnable() {
        try {
            if (getServer().getPluginManager().getPlugin("InvSee") != null) throw new IllegalStateException("기존 InvSee JAR를 plugins 폴더 밖으로 옮긴 뒤 시작하세요. AdminLite 2에 통합되어 있습니다.");
            saveDefaultConfig(); loadSettings();
            var berryNames = getServer().getPluginManager().getPlugin("BerryNames");
            if (berryNames != null && berryNames.isEnabled()) {
                try { names = BerryNamesHook.connect(this); }
                catch (LinkageError e) { getLogger().warning("베리 이름 연동에는 BerryNames 1.3.0 이상이 필요합니다. 현재는 실제 계정명을 사용합니다."); }
            }
            state = new ActiveState(getDataFolder().toPath().resolve("active.yml"));
            staff = new StaffTools(this); chat = new ChatTools(this); configureChat(); editor = new InventoryEditor(this); menus = new AdminMenus(this);
            visibility = new IdentityVisibility(this);
            for (var listener : List.of(staff, chat, editor, menus, visibility)) getServer().getPluginManager().registerEvents(listener, this);
            for (String command : List.of("admin", "adm", "invsee", "endersee")) {
                PluginCommand registered = Objects.requireNonNull(getCommand(command));
                registered.setExecutor(this); registered.setTabCompleter(this);
            }
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    for (UUID uuid : state.expire()) { Player player = Bukkit.getPlayer(uuid); if (player != null) tell(player, "mute-off"); }
                } catch (Exception e) { getLogger().log(Level.SEVERE, "현재 제재 상태 저장 실패", e); }
            }, 20, 20);
            getLogger().info("AdminLite 2.2.0 enabled (Paper 26.3 build 206). BerryNames integration; identity visibility " + (visibility.available() ? "available." : "requires PacketEvents."));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "AdminLite 시작 실패", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (visibility != null) visibility.shutdown();
        if (editor != null) editor.shutdown();
        if (staff != null) staff.shutdown();
        if (state != null) try { state.save(); } catch (Exception e) { getLogger().log(Level.SEVERE, "현재 제재 상태 저장 실패", e); }
    }

    private void loadSettings() {
        reloadConfig();
        Map<String, String> loaded = new HashMap<>();
        Set<String> keys = new HashSet<>(getConfig().getKeys(true));
        if (getConfig().getDefaults() != null) keys.addAll(getConfig().getDefaults().getKeys(true));
        for (String key : keys) if (getConfig().isString(key)) loaded.put(key, getConfig().getString(key));
        messages = Map.copyOf(loaded);
        opOnly = getConfig().getBoolean("op-only", true);
        Set<String> disabled = new HashSet<>();
        for (String feature : List.of("gui", "player-info", "warn", "mute", "kick", "ban", "freeze", "spectate", "vanish", "visibility", "teleport", "inventory", "offline-inventory", "broadcast", "chat")) {
            if (!getConfig().getBoolean("features." + feature, true)) disabled.add(feature);
        }
        disabledFeatures = Set.copyOf(disabled);
        if (chat != null) configureChat();
        if (visibility != null) visibility.refresh();
    }

    private void configureChat() {
        chat.limit = Math.clamp(getConfig().getInt("chat-log-size", 50), 1, 500);
        chat.blockedCommands = Set.copyOf(getConfig().getStringList("mute-blocked-commands").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            checkPermission(sender, "use");
            if (!command.getName().equals("admin") && !command.getName().equals("adm")) {
                String action = command.getName().equals("invsee") ? "invsee" : "endersee";
                String[] forwarded = new String[args.length + 1]; forwarded[0] = action; System.arraycopy(args, 0, forwarded, 1, args.length);
                execute(sender, forwarded);
            } else execute(sender, args);
        } catch (Exception e) { error(sender, e); }
        return true;
    }

    void execute(CommandSender sender, String... args) throws Exception {
        checkPermission(sender, "use");
        if (args.length == 0) {
            if (sender instanceof Player player) { require(player, "gui"); menus.players(player, false, "", 0); }
            else help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "players" -> {
                Player player = player(sender); require(sender, "gui");
                if (sub.equals("menu") && args.length > 1) menus.target(player, resolve(args[1]));
                else menus.players(player, false, "", 0);
            }
            case "info" -> { require(sender, "info"); info(sender, resolve(arg(args, 1, "/admin info <플레이어>"))); }
            case "invsee", "endersee", "ecsee" -> {
                require(sender, "invsee");
                if (args.length > 3 || args.length == 3 && !List.of("view", "edit").contains(args[2].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("사용법: /admin " + sub + " <플레이어> [view|edit]");
                boolean edit = args.length > 2 ? args[2].equalsIgnoreCase("edit") : allowed(sender, "invsee.edit");
                editor.open(player(sender), resolve(arg(args, 1, "/admin " + sub + " <플레이어> [view|edit]")), sub.equals("invsee") ? PlayerDataStore.Kind.INVENTORY : PlayerDataStore.Kind.ENDER, edit);
            }
            case "warn" -> {
                require(sender, "warn"); Player target = online(arg(args, 1, "/admin warn <플레이어> [사유]")); String reason = reason(args, 2);
                tell(target, "warn-message"); tell(target, "warn-reason-format", "%reason%", reason);
                tell(sender, "warn-staff-message", "%player%", name(target), "%reason%", reason);
            }
            case "freeze" -> {
                require(sender, "freeze"); OfflinePlayer target = resolve(arg(args, 1, "/admin freeze <플레이어>"));
                boolean enabled = state.toggleFrozen(target.getUniqueId());
                Player online = target.getPlayer();
                if (online != null) { if (enabled) { online.leaveVehicle(); online.setVelocity(new org.bukkit.util.Vector()); online.setFallDistance(0); } tell(online, enabled ? "freeze-on" : "freeze-off"); }
                tell(sender, enabled ? "freeze-staff-on" : "freeze-staff-off", "%player%", name(target));
            }
            case "mute" -> {
                require(sender, "mute"); OfflinePlayer target = resolve(arg(args, 1, "/admin mute <플레이어> [기간|perm] [사유]"));
                long duration = args.length > 2 ? TimeSpec.parse(args[2]) : 0;
                String reason = reason(args, 3);
                state.setMute(target.getUniqueId(), new ActiveState.Mute(accountName(target), reason, duration == 0 ? 0 : Math.addExact(System.currentTimeMillis(), duration)));
                if (target.getPlayer() != null) tell(target.getPlayer(), "mute-on", "%duration%", TimeSpec.label(duration), "%reason%", reason);
                tell(sender, "mute-staff-on", "%player%", name(target), "%duration%", TimeSpec.label(duration));
            }
            case "unmute" -> {
                require(sender, "mute"); OfflinePlayer target = resolve(arg(args, 1, "/admin unmute <플레이어>"));
                state.setMute(target.getUniqueId(), null);
                if (target.getPlayer() != null) tell(target.getPlayer(), "mute-off");
                tell(sender, "mute-staff-off", "%player%", name(target));
            }
            case "kick" -> {
                require(sender, "kick"); Player target = online(arg(args, 1, "/admin kick <플레이어> [사유]"));
                target.kick(text(fill(cfg("kick-message"), "%reason%", reason(args, 2))));
                tell(sender, "kick-success", "%player%", name(target));
            }
            case "ban" -> {
                require(sender, "ban"); OfflinePlayer target = resolve(arg(args, 1, "/admin ban <플레이어> [기간|perm] [사유]"));
                long duration = args.length > 2 ? TimeSpec.parse(args[2]) : 0;
                String reason = reason(args, 3);
                if (duration == 0 && sender instanceof Player player && enabled("gui")) menus.confirm(player, "&c" + name(target) + " 영구 차단", () -> banNow(sender, target, duration, reason));
                else banNow(sender, target, duration, reason);
            }
            case "unban" -> {
                require(sender, "ban"); OfflinePlayer target = resolve(arg(args, 1, "/admin unban <플레이어>"));
                bans().pardon(target.getPlayerProfile()); tell(sender, "unban-success", "%player%", name(target));
            }
            case "spectate", "spec" -> {
                checkPermission(sender, "spectate"); Player player = player(sender);
                if (args.length < 2) { if (!staff.restore(player)) throw new IllegalArgumentException("관전 중이 아닙니다. /admin spectate <플레이어>"); }
                else { feature("spectate"); staff.spectate(player, online(args[1])); }
            }
            case "vanish" -> { checkPermission(sender, "vanish"); Player player = player(sender); if (!staff.isVanished(player.getUniqueId())) feature("vanish"); staff.vanish(player); }
            case "visibility" -> { checkPermission(sender, "visibility"); menus.visibility(player(sender)); }
            case "hidetab", "hidename", "hideidentity" -> {
                if (args.length > 2) throw new IllegalArgumentException("사용법: /admin " + sub + " [on|off|toggle]");
                visibility.change(player(sender), sub, args.length == 2 ? args[1] : "toggle");
            }
            case "tp", "tphere" -> {
                require(sender, "teleport"); Player player = player(sender), target = online(arg(args, 1, "/admin " + sub + " <플레이어>"));
                boolean success = sub.equals("tp") ? staff.teleport(player, target.getLocation()) : staff.teleport(target, player.getLocation());
                if (!success) throw new IllegalArgumentException("이동이 취소되었습니다."); tell(sender, "teleport-success", "%player%", name(target));
            }
            case "broadcast", "bc" -> {
                require(sender, "broadcast"); arg(args, 1, "/admin broadcast <내용>");
                Component message = text(fill(cfg("broadcast-format"), "%message%", join(args, 1)));
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message)); Bukkit.getConsoleSender().sendMessage(message);
            }
            case "chatlog" -> chat.list(sender);
            case "deletechat", "delchat" -> chat.delete(sender, arg(args, 1, "/admin deletechat #<ID> 또는 <플레이어>"));
            case "clearchat" -> chat.clear(sender);
            case "reload" -> { checkPermission(sender, "reload"); loadSettings(); tell(sender, "reload-message"); }
            case "help" -> help(sender);
            default -> {
                if (args.length == 1 && sender instanceof Player player) { require(sender, "gui"); menus.target(player, resolve(args[0])); }
                else help(sender);
            }
        }
    }

    void banNow(CommandSender sender, OfflinePlayer target, long duration, String reason) {
        require(sender, "ban");
        bans().addBan(target.getPlayerProfile(), reason, duration == 0 ? (Instant) null : Instant.now().plusMillis(duration), sender.getName());
        if (target.getPlayer() != null) target.getPlayer().kick(text(fill(cfg("ban-message"), "%reason%", reason, "%duration%", TimeSpec.label(duration))));
        tell(sender, "ban-success", "%player%", name(target), "%duration%", TimeSpec.label(duration));
    }

    ProfileBanList bans() { return Bukkit.getBanList(BanList.Type.PROFILE); }

    OfflinePlayer resolve(String input) {
        UUID alias = names.resolveAlias(input);
        if (alias != null) return Bukkit.getOfflinePlayer(alias);
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getName().equalsIgnoreCase(input) || player.getUniqueId().toString().equalsIgnoreCase(input)) return player;
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            if (target.hasPlayedBefore() || editor.storage.find(uuid) != null || state.mutes.containsKey(uuid) || bans().isBanned(target.getPlayerProfile())) return target;
        } catch (IllegalArgumentException ignored) { }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null && (cached.hasPlayedBefore() || editor.storage.find(cached.getUniqueId()) != null)) return cached;
        for (OfflinePlayer target : Bukkit.getOfflinePlayers()) if (target.getName() != null && target.getName().equalsIgnoreCase(input)) return target;
        for (var entry : bans().getEntries()) {
            if (entry.getBanTarget() instanceof org.bukkit.profile.PlayerProfile profile
                    && profile.getName() != null && profile.getName().equalsIgnoreCase(input) && profile.getUniqueId() != null) return Bukkit.getOfflinePlayer(profile.getUniqueId());
        }
        for (var entry : state.mutes.entrySet()) if (entry.getValue().name().equalsIgnoreCase(input)) return Bukkit.getOfflinePlayer(entry.getKey());
        throw new IllegalArgumentException(cfg("player-not-found"));
    }

    Player online(String name) { Player player = resolve(name).getPlayer(); if (player == null || !player.isOnline()) throw new IllegalArgumentException("현재 접속 중인 플레이어가 아닙니다."); return player; }
    String name(OfflinePlayer target) { return names.display(target.getUniqueId(), target.getName()); }
    String accountName(OfflinePlayer target) { return names.account(target.getUniqueId(), target.getName()); }

    List<OfflinePlayer> knownPlayers(CommandSender sender, boolean onlineOnly) {
        var players = new LinkedHashMap<UUID, OfflinePlayer>();
        if (!onlineOnly) {
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) players.put(player.getUniqueId(), player);
            for (var entry : names.entries()) players.putIfAbsent(entry.uuid(), Bukkit.getOfflinePlayer(entry.uuid()));
        }
        for (Player player : Bukkit.getOnlinePlayers()) players.put(player.getUniqueId(), player);
        return players.values().stream().filter(target -> {
            Player online = target.getPlayer();
            return online == null || !(sender instanceof Player viewer) || viewer.canSee(online);
        }).toList();
    }
    Player player(CommandSender sender) { if (sender instanceof Player player) return player; throw new IllegalArgumentException(cfg("player-only")); }

    boolean allowed(CommandSender sender, String action) {
        return !(sender instanceof Player player && opOnly && !player.isOp()) && sender.hasPermission("adminlite." + action);
    }
    boolean maySeeHidden(Player player) { return allowed(player, "see-hidden"); }
    void checkPermission(CommandSender sender, String action) { if (!allowed(sender, action)) throw new IllegalArgumentException(cfg("no-permission")); }
    void require(CommandSender sender, String action) {
        checkPermission(sender, action);
        feature(switch (action) { case "invsee", "invsee.edit" -> "inventory"; case "chatlog", "deletechat", "clearchat" -> "chat"; case "info" -> "player-info"; default -> action; });
    }
    boolean enabled(String feature) { return !disabledFeatures.contains(feature); }
    void feature(String feature) { if (!enabled(feature)) throw new IllegalArgumentException(cfg("feature-disabled")); }
    String cfg(String key) { return messages.getOrDefault(key, key); }
    Component text(String text) { return LEGACY.deserialize(text.replace('§', '&')); }
    void tell(CommandSender sender, String key, String... pairs) { tellRaw(sender, fill(cfg(key), pairs)); }
    void tellRaw(CommandSender sender, String value) { sender.sendMessage(text(cfg("general-prefix") + value)); }
    static String fill(String text, String... pairs) { for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace(pairs[i], pairs[i + 1]); return text; }

    void error(CommandSender sender, Exception error) {
        if (error instanceof IllegalArgumentException) tellRaw(sender, "&c" + error.getMessage());
        else { tell(sender, "operation-failed"); getLogger().log(Level.SEVERE, "관리 작업 실패", error); }
    }

    ItemStack item(Material material, String title, String... lines) {
        ItemStack item = ItemStack.of(material); var meta = item.getItemMeta();
        meta.displayName(text(title)); meta.lore(Stream.of(lines).map(this::text).toList()); item.setItemMeta(meta); return item;
    }

    List<String> details(OfflinePlayer target) {
        List<String> lines = new ArrayList<>(); Player player = target.getPlayer();
        lines.add("&7표시 이름: &f" + name(target));
        lines.add("&7실제 계정: &f" + accountName(target));
        lines.add("&7UUID: &f" + target.getUniqueId());
        lines.add("&7접속: " + (player == null ? "&8오프라인" : "&a온라인"));
        if (player != null) {
            Location loc = player.getLocation(); lines.add("&7위치: &f" + loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            lines.add("&7핑: &f" + player.getPing() + "ms · " + player.getGameMode().name());
        }
        ActiveState.Mute mute = state.mute(target.getUniqueId());
        lines.add("&7뮤트: &f" + (mute == null ? "없음" : mute.remaining() + " / " + mute.reason()));
        lines.add("&7차단: &f" + (bans().isBanned(target.getPlayerProfile()) ? "적용 중" : "없음"));
        lines.add("&7이동 동결: &f" + (state.frozen.contains(target.getUniqueId()) ? "적용 중" : "없음"));
        return lines;
    }
    private void info(CommandSender sender, OfflinePlayer target) { tellRaw(sender, "&b" + name(target)); details(target).forEach(line -> tellRaw(sender, line)); }
    private String reason(String[] args, int index) { return args.length > index ? join(args, index) : cfg("warn-default-reason"); }
    static String join(String[] args, int from) { return String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    static String arg(String[] args, int index, String usage) { if (args.length <= index) throw new IllegalArgumentException("사용법: " + usage); return args[index]; }

    private void help(CommandSender sender) {
        for (String line : List.of("&b/admin &7- 관리 메뉴 / 별칭 /adm", "/admin <플레이어> 또는 menu <플레이어>",
                "/admin info <플레이어>", "/invsee <플레이어> [view|edit] / /endersee <플레이어> [view|edit]", "/admin warn <플레이어> [사유]",
                "/admin mute <플레이어> [30m|1h|1d|perm] [사유]", "/admin unmute <플레이어>", "/admin kick <플레이어> [사유]",
                "/admin ban <플레이어> [30m|1d|perm] [사유] / unban <플레이어>", "/admin freeze <플레이어>", "/admin spectate [플레이어] / vanish",
                "/admin visibility · 내 표시 설정", "/admin hidetab / hidename / hideidentity [on|off|toggle] · ON이면 숨김",
                "/admin tp <플레이어> / tphere <플레이어>", "/admin broadcast <내용>", "/admin chatlog / deletechat #<ID> 또는 <플레이어> / clearchat", "/admin reload")) tellRaw(sender, "&e" + line);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!allowed(sender, "use")) return List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> candidates;
        boolean direct = !List.of("admin", "adm").contains(command.getName());
        if (!direct && args.length == 1) {
            var suggestions = new LinkedHashSet<>(SUBCOMMANDS);
            if (allowed(sender, "gui")) for (OfflinePlayer target : knownPlayers(sender, false)) {
                names.suggestions(target.getUniqueId(), target.getName(), prefix, false).stream()
                        .filter(name -> !AdminTargets.reserved(name)).forEach(suggestions::add);
            }
            candidates = List.copyOf(suggestions);
        } else if (!direct && args.length == 2 && List.of("hidetab", "hidename", "hideidentity").contains(args[0].toLowerCase(Locale.ROOT))) {
            candidates = allowed(sender, "visibility") ? List.of("on", "off", "toggle") : List.of();
        } else if (direct && args.length == 1 || !direct && args.length == 2 && AdminTargets.target(args[0]) != null) {
            var syntax = direct ? AdminTargets.target("invsee") : AdminTargets.target(args[0]);
            if (!allowed(sender, syntax.permission())) return List.of();
            var suggestions = new LinkedHashSet<String>();
            for (OfflinePlayer target : knownPlayers(sender, syntax.online())) suggestions.addAll(names.suggestions(
                    target.getUniqueId(), target.getName(), prefix, syntax.messageId()));
            candidates = List.copyOf(suggestions);
        } else if (!direct && args.length == 3 && List.of("mute", "ban").contains(args[0])) candidates = List.of("5m", "30m", "1h", "1d", "7d", "perm");
        else if (direct && args.length == 2 || !direct && args.length == 3 && List.of("invsee", "endersee", "ecsee").contains(args[0])) candidates = List.of("view", "edit");
        else candidates = List.of();
        return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(80).toList();
    }
}
