package kr.openclaw.adminlite;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AdminLitePlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Set<UUID> frozen = new HashSet<>();
    private final Set<UUID> muted = new HashSet<>();
    private final Map<UUID, SpectateState> spectating = new HashMap<>();
    private final Deque<ChatRecord> chatRecords = new ArrayDeque<>();
    private int nextChatId = 1;
    private int chatLogSize;
    private int clearchatLines;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("adm") != null) {
            getCommand("adm").setExecutor(this);
            getCommand("adm").setTabCompleter(this);
        }
        getLogger().info("AdminLite enabled.");
    }

    private void loadSettings() {
        reloadConfig();
        chatLogSize = Math.max(1, getConfig().getInt("chat-log-size", 50));
        clearchatLines = Math.max(20, getConfig().getInt("clearchat-lines", 120));
        trimChatLog();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "invsee" -> invsee(sender, args);
                case "warn" -> warn(sender, args);
                case "freeze" -> freeze(sender, args);
                case "mute" -> mute(sender, args, true);
                case "unmute" -> mute(sender, args, false);
                case "spectate", "spec" -> spectate(sender, args);
                case "tp" -> teleportTo(sender, args);
                case "tphere" -> teleportHere(sender, args);
                case "broadcast", "bc" -> broadcast(sender, args);
                case "chatlog" -> chatlog(sender);
                case "deletechat", "delchat" -> deleteChat(sender, args);
                case "clearchat" -> clearChat(sender);
                case "reload" -> reload(sender);
                default -> sendHelp(sender, label);
            }
        } catch (IllegalArgumentException ex) {
            msg(sender, ex.getMessage());
        }
        return true;
    }

    private void invsee(CommandSender sender, String[] args) {
        require(sender, "adminlite.invsee");
        Player staff = requirePlayer(sender);
        Player target = requireTarget(args, 1);
        Inventory inv = target.getInventory();
        staff.openInventory(inv);
        msg(sender, "&a" + target.getName() + "의 인벤토리를 열었습니다.");
    }

    private void warn(CommandSender sender, String[] args) {
        require(sender, "adminlite.warn");
        Player target = requireTarget(args, 1);
        String reason = args.length >= 3 ? join(args, 2) : cfg("warn-default-reason");
        target.sendMessage(color(cfg("warn-message")));
        target.sendMessage(color(cfg("warn-reason-format").replace("%reason%", reason)));
        msg(sender, cfg("warn-staff-message").replace("%player%", target.getName()).replace("%reason%", reason));
    }

    private void freeze(CommandSender sender, String[] args) {
        require(sender, "adminlite.freeze");
        Player target = requireTarget(args, 1);
        UUID id = target.getUniqueId();
        if (frozen.remove(id)) {
            target.sendMessage(color(cfg("freeze-off")));
            msg(sender, cfg("freeze-staff-off").replace("%player%", target.getName()));
        } else {
            frozen.add(id);
            target.sendMessage(color(cfg("freeze-on")));
            msg(sender, cfg("freeze-staff-on").replace("%player%", target.getName()));
        }
    }

    private void mute(CommandSender sender, String[] args, boolean enabled) {
        require(sender, "adminlite.mute");
        Player target = requireTarget(args, 1);
        UUID id = target.getUniqueId();
        if (enabled) {
            muted.add(id);
            target.sendMessage(color(cfg("mute-on")));
            msg(sender, cfg("mute-staff-on").replace("%player%", target.getName()));
        } else {
            muted.remove(id);
            target.sendMessage(color(cfg("mute-off")));
            msg(sender, cfg("mute-staff-off").replace("%player%", target.getName()));
        }
    }

    private void spectate(CommandSender sender, String[] args) {
        require(sender, "adminlite.spectate");
        Player staff = requirePlayer(sender);
        UUID staffId = staff.getUniqueId();

        if (args.length == 1) {
            SpectateState state = spectating.remove(staffId);
            if (state == null) {
                msg(sender, "&c관전 중이 아닙니다. 사용법: /adm spectate <플레이어>");
                return;
            }
            staff.setGameMode(state.gameMode());
            staff.teleport(state.location());
            staff.setAllowFlight(state.allowFlight());
            staff.setFlying(state.flying());
            msg(sender, "&a관전을 종료했습니다.");
            return;
        }

        Player target = requireTarget(args, 1);
        spectating.putIfAbsent(staffId, new SpectateState(staff.getLocation(), staff.getGameMode(), staff.getAllowFlight(), staff.isFlying()));
        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(target.getLocation());
        msg(sender, "&a" + target.getName() + " 관전을 시작했습니다. 종료: /adm spectate");
    }

    private void teleportTo(CommandSender sender, String[] args) {
        require(sender, "adminlite.teleport");
        Player staff = requirePlayer(sender);
        Player target = requireTarget(args, 1);
        staff.teleport(target.getLocation());
        msg(sender, "&a" + target.getName() + "에게 이동했습니다.");
    }

    private void teleportHere(CommandSender sender, String[] args) {
        require(sender, "adminlite.teleport");
        Player staff = requirePlayer(sender);
        Player target = requireTarget(args, 1);
        target.teleport(staff.getLocation());
        msg(sender, "&a" + target.getName() + "을(를) 소환했습니다.");
    }

    private void broadcast(CommandSender sender, String[] args) {
        require(sender, "adminlite.broadcast");
        if (args.length < 2) throw usage("/adm broadcast <메시지>");
        String text = join(args, 1);
        Bukkit.broadcastMessage(color(cfg("broadcast-format").replace("%message%", text)));
    }

    private void chatlog(CommandSender sender) {
        require(sender, "adminlite.chatlog");
        msg(sender, cfg("chatlog-header"));
        if (chatRecords.isEmpty()) {
            msg(sender, cfg("chatlog-empty"));
        } else {
            for (ChatRecord record : chatRecords) {
                sendChatRecord(sender, record);
            }
        }
        msg(sender, cfg("chatlog-footer"));
    }

    private void sendChatRecord(CommandSender sender, ChatRecord record) {
        if (sender instanceof Player player) {
            Component delete = Component.text("[X] ", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/adm deletechat #" + record.id()))
                    .hoverEvent(HoverEvent.showText(Component.text("이 메시지 기록 삭제")));
            Component id = Component.text("[#" + record.id() + "] ", NamedTextColor.GRAY);
            Component name = Component.text(record.playerName(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/adm deletechat " + record.playerName()))
                    .hoverEvent(HoverEvent.showText(Component.text(record.playerName() + "의 최근 채팅 전체 삭제")));
            Component msg = Component.text(": " + record.message(), NamedTextColor.WHITE);
            player.sendMessage(delete.append(id).append(name).append(msg));
        } else {
            sender.sendMessage("[X] [#" + record.id() + "] " + record.playerName() + ": " + record.message());
        }
    }

    private void deleteChat(CommandSender sender, String[] args) {
        require(sender, "adminlite.deletechat");
        if (args.length < 2) throw usage("/adm deletechat #<ID> 또는 /adm deletechat <플레이어>");
        String target = args[1];
        if (target.startsWith("#")) {
            int id;
            try {
                id = Integer.parseInt(target.substring(1));
            } catch (NumberFormatException ex) {
                throw usage("/adm deletechat #<ID>");
            }
            boolean removed = chatRecords.removeIf(record -> record.id() == id);
            if (removed) msg(sender, cfg("deletechat-one").replace("%id%", String.valueOf(id)));
            else msg(sender, cfg("deletechat-not-found"));
            return;
        }

        int before = chatRecords.size();
        chatRecords.removeIf(record -> record.playerName().equalsIgnoreCase(target));
        int count = before - chatRecords.size();
        if (count > 0) msg(sender, cfg("deletechat-player").replace("%player%", target).replace("%count%", String.valueOf(count)));
        else msg(sender, cfg("deletechat-not-found"));
    }

    private void clearChat(CommandSender sender) {
        require(sender, "adminlite.clearchat");
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < clearchatLines; i++) player.sendMessage(" ");
            player.sendMessage(color(cfg("clearchat-message")));
        }
        msg(sender, cfg("clearchat-message"));
    }

    private void reload(CommandSender sender) {
        require(sender, "adminlite.reload");
        loadSettings();
        msg(sender, "&a설정을 다시 불러왔습니다.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (muted.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(color(cfg("mute-deny-message")));
            return;
        }
        ChatRecord record = new ChatRecord(nextChatId++, player.getUniqueId(), player.getName(), event.getMessage());
        synchronized (chatRecords) {
            chatRecords.addLast(record);
            trimChatLog();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        event.setTo(from);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        frozen.remove(id);
        muted.remove(id);
        spectating.remove(id);
    }

    private void trimChatLog() {
        while (chatRecords.size() > chatLogSize) chatRecords.removeFirst();
    }

    private void sendHelp(CommandSender sender, String label) {
        msg(sender, "&7─── &bAdminLite 명령어 &7───");
        msg(sender, "&e/" + label + " invsee <플레이어> &7- 인벤토리 열기");
        msg(sender, "&e/" + label + " warn <플레이어> [사유] &7- 단순 경고");
        msg(sender, "&e/" + label + " freeze <플레이어> &7- 이동 동결 토글");
        msg(sender, "&e/" + label + " mute|unmute <플레이어> &7- 채팅 차단/해제");
        msg(sender, "&e/" + label + " spectate [플레이어] &7- 관전 시작/종료");
        msg(sender, "&e/" + label + " tp|tphere <플레이어> &7- 이동/소환");
        msg(sender, "&e/" + label + " broadcast <메시지> &7- 전체 공지");
        msg(sender, "&e/" + label + " chatlog &7- 최근 채팅 보기");
        msg(sender, "&e/" + label + " deletechat #<ID>|<플레이어> &7- 채팅 기록 삭제");
        msg(sender, "&e/" + label + " clearchat &7- 전체 채팅 초기화");
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException(cfg("player-only"));
        return player;
    }

    private Player requireTarget(String[] args, int index) {
        if (args.length <= index) throw usage("플레이어 이름이 필요합니다.");
        Player target = Bukkit.getPlayerExact(args[index]);
        if (target == null) target = Bukkit.getPlayer(args[index]);
        if (target == null) throw new IllegalArgumentException(cfg("player-not-found"));
        return target;
    }

    private void require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) throw new IllegalArgumentException(cfg("no-permission"));
    }

    private IllegalArgumentException usage(String message) {
        return new IllegalArgumentException(cfg("usage-color") + message);
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private String cfg(String path) {
        return getConfig().getString(path, "");
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(color(cfg("general-prefix") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("invsee", "warn", "freeze", "mute", "unmute", "spectate", "tp", "tphere", "broadcast", "chatlog", "deletechat", "clearchat", "reload"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("invsee", "warn", "freeze", "mute", "unmute", "spectate", "tp", "tphere", "deletechat").contains(sub)) {
                List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                if (sub.equals("deletechat")) {
                    List<String> withIds = new ArrayList<>(names);
                    chatRecords.forEach(record -> withIds.add("#" + record.id()));
                    return filter(withIds, args[1]);
                }
                return filter(names, args[1]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

    private record ChatRecord(int id, UUID uuid, String playerName, String message) {}
    private record SpectateState(Location location, GameMode gameMode, boolean allowFlight, boolean flying) {}
}
