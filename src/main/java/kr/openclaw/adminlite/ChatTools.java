package kr.openclaw.adminlite;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class ChatTools implements Listener {
    record Entry(int id, UUID uuid, String name, String text, SignedMessage signed) {}
    record Prompt(long expires, Consumer<String> action) {}
    private final AdminLitePlugin plugin;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    volatile int limit = 50;
    volatile Set<String> blockedCommands = Set.of();

    ChatTools(AdminLitePlugin plugin) { this.plugin = plugin; }

    void prompt(Player player, String instruction, Consumer<String> action) {
        player.closeInventory();
        prompts.put(player.getUniqueId(), new Prompt(System.currentTimeMillis() + 60_000, action));
        plugin.tellRaw(player, "&e" + instruction + " &7(취소: 취소 / cancel, 제한 시간 60초)");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) public void input(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Prompt prompt = prompts.remove(uuid);
        if (prompt == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) return;
            if (!plugin.allowed(player, "use")) { plugin.tell(player, "no-permission"); return; }
            if (prompt.expires() < System.currentTimeMillis() || input.equalsIgnoreCase("cancel") || input.equals("취소")) { plugin.tell(player, "input-cancelled"); return; }
            try { prompt.action().accept(input); } catch (Exception e) { plugin.error(player, e); }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void mute(AsyncChatEvent event) {
        ActiveState.Mute mute = plugin.state.mute(event.getPlayer().getUniqueId());
        if (mute != null) {
            event.setCancelled(true);
            plugin.tell(event.getPlayer(), "mute-deny-message", "%remaining%", mute.remaining(), "%reason%", mute.reason());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void record(AsyncChatEvent event) {
        if (!plugin.enabled("chat")) return;
        Entry entry = new Entry(ids.incrementAndGet(), event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                PlainTextComponentSerializer.plainText().serialize(event.message()), event.signedMessage());
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > limit) entries.removeFirst();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) public void commandEarly(PlayerCommandPreprocessEvent event) { command(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void command(PlayerCommandPreprocessEvent event) {
        ActiveState.Mute mute = plugin.state.mute(event.getPlayer().getUniqueId());
        if (mute == null) return;
        String root = AdminTargets.commandRoot(event.getMessage());
        if (blockedCommands.contains(root)) {
            event.setCancelled(true);
            plugin.tell(event.getPlayer(), "mute-deny-message", "%remaining%", mute.remaining(), "%reason%", mute.reason());
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }

    void list(CommandSender sender) {
        plugin.require(sender, "chatlog"); plugin.tell(sender, "chatlog-header");
        List<Entry> snapshot;
        synchronized (entries) { snapshot = List.copyOf(entries); }
        if (snapshot.isEmpty()) plugin.tell(sender, "chatlog-empty");
        for (Entry entry : snapshot) {
            Component delete = Component.text("[X] ", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/adm deletechat #" + entry.id()))
                    .hoverEvent(HoverEvent.showText(Component.text("이 메시지 삭제")));
            Component name = Component.text(plugin.names.display(entry.uuid(), entry.name()), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/adm deletechat " + entry.uuid()))
                    .hoverEvent(HoverEvent.showText(Component.text("실제 계정: " + entry.name() + "\n이 플레이어의 최근 채팅 삭제")));
            sender.sendMessage(delete.append(Component.text("[#" + entry.id() + "] ", NamedTextColor.GRAY))
                    .append(name).append(Component.text(": " + entry.text(), NamedTextColor.WHITE)));
        }
        plugin.tell(sender, "chatlog-footer");
    }

    void delete(CommandSender sender, String query) {
        plugin.require(sender, "deletechat");
        Integer id = null;
        if (query.startsWith("#")) {
            try { id = Integer.valueOf(query.substring(1)); } catch (NumberFormatException e) { throw new IllegalArgumentException("메시지 ID를 확인하세요."); }
        }
        UUID target = null;
        if (id == null) {
            // Keep ambiguity errors outside the legacy account-name fallback.
            target = plugin.names.resolveAlias(query);
            try { if (target == null) target = plugin.resolve(query).getUniqueId(); }
            catch (IllegalArgumentException e) {
                // Disconnected chat authors can outlive the server's player cache.
                synchronized (entries) {
                    if (entries.stream().noneMatch(entry -> entry.name().equalsIgnoreCase(query) || entry.uuid().toString().equalsIgnoreCase(query))) throw e;
                }
            }
        }
        List<Entry> removed = new ArrayList<>();
        synchronized (entries) {
            for (Entry entry : entries) if (id != null ? entry.id() == id : target != null ? entry.uuid().equals(target) : entry.name().equalsIgnoreCase(query) || entry.uuid().toString().equalsIgnoreCase(query)) removed.add(entry);
            entries.removeAll(removed);
        }
        if (removed.isEmpty()) { plugin.tell(sender, "deletechat-not-found"); return; }
        int unsigned = 0;
        for (Entry entry : removed) {
            if (entry.signed() == null || entry.signed().signature() == null) { unsigned++; continue; }
            for (Player player : Bukkit.getOnlinePlayers()) player.deleteMessage(entry.signed());
        }
        plugin.tellRaw(sender, "&a최근 채팅 " + removed.size() + "개를 삭제했습니다.");
        if (unsigned > 0) plugin.tell(sender, "unsigned-delete-note");
    }

    void clear(CommandSender sender) {
        plugin.require(sender, "clearchat");
        List<Entry> snapshot;
        synchronized (entries) { snapshot = List.copyOf(entries); entries.clear(); }
        int lines = Math.clamp(plugin.getConfig().getInt("clearchat-lines", 120), 20, 500);
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Entry entry : snapshot) if (entry.signed() != null && entry.signed().signature() != null) player.deleteMessage(entry.signed());
            for (int i = 0; i < lines; i++) player.sendMessage(Component.text(" "));
            plugin.tell(player, "clearchat-message");
        }
        plugin.tell(sender, "clearchat-message");
    }
}
