package kr.openclaw.adminlite;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

final class AdminMenus implements Listener {
    private final AdminLitePlugin plugin;
    AdminMenus(AdminLitePlugin plugin) { this.plugin = plugin; }

    private static final class Menu implements InventoryHolder {
        final UUID owner;
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inventory;
        Menu(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private Menu create(Player player, int size, String title) {
        plugin.require(player, "gui");
        Menu menu = new Menu(player.getUniqueId()); menu.inventory = Bukkit.createInventory(menu, size, plugin.text(title));
        return menu;
    }
    private void button(Menu menu, int slot, Material icon, String title, Runnable action, String... lore) {
        menu.inventory.setItem(slot, plugin.item(icon, title, lore));
        if (action != null) menu.actions.put(slot, action);
    }

    void players(Player viewer, boolean offline, String filter, int requestedPage) {
        Menu menu = create(viewer, 54, "&8관리 | 플레이어 선택");
        List<OfflinePlayer> players = plugin.knownPlayers(viewer, !offline).stream()
                .filter(player -> plugin.names.matches(player.getUniqueId(), player.getName(), filter))
                .sorted(Comparator.<OfflinePlayer, Boolean>comparing(player -> !player.isOnline()).thenComparing(plugin::name, String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (players.size() + 44) / 45), page = Math.clamp(requestedPage, 0, pages - 1);
        for (int i = page * 45; i < Math.min(players.size(), (page + 1) * 45); i++) {
            OfflinePlayer target = players.get(i);
            button(menu, i % 45, Material.PLAYER_HEAD, "&b" + plugin.name(target), () -> target(viewer, target),
                    target.isOnline() ? "&a온라인" : "&7오프라인", "&7실제 계정: &f" + plugin.accountName(target), "&7클릭하여 관리", "&8" + target.getUniqueId());
        }
        button(menu, 45, Material.ARROW, "&e이전 · " + (page + 1) + "/" + pages, () -> players(viewer, offline, filter, page - 1));
        button(menu, 46, Material.ENDER_EYE, offline ? "&b전체 플레이어 표시 중" : "&a온라인만 표시 중", () -> players(viewer, !offline, filter, 0));
        button(menu, 47, Material.NAME_TAG, "&e플레이어 검색", () -> plugin.chat.prompt(viewer, "검색할 닉네임을 입력하세요. 전체 보기: *", input -> players(viewer, true, input.equals("*") ? "" : input, 0)));
        button(menu, 48, Material.BELL, "&6전체 공지", () -> plugin.chat.prompt(viewer, "공지할 내용을 입력하세요.", input -> run(viewer, "broadcast", input)));
        button(menu, 49, Material.WRITABLE_BOOK, "&e최근 채팅", () -> { viewer.closeInventory(); run(viewer, "chatlog"); });
        button(menu, 50, Material.PAPER, "&e채팅창 정리", () -> { viewer.closeInventory(); run(viewer, "clearchat"); });
        button(menu, 51, Material.COMPASS, "&b관전 종료 · 원위치 복귀", () -> { viewer.closeInventory(); run(viewer, "spectate"); });
        button(menu, 52, Material.GLASS, "&b내 관리자 표시 설정", () -> visibility(viewer), "&7탭 목록 · 머리 위 이름 · 은신");
        button(menu, 53, Material.ARROW, "&e다음", () -> players(viewer, offline, filter, page + 1));
        viewer.openInventory(menu.inventory);
    }

    void target(Player viewer, OfflinePlayer target) {
        Menu menu = create(viewer, 54, "&8관리 | " + plugin.name(target));
        String id = target.getUniqueId().toString();
        String[] info = plugin.allowed(viewer, "info") && plugin.enabled("player-info")
                ? plugin.details(target).toArray(String[]::new) : new String[]{"&7플레이어 정보 조회 기능을 사용할 수 없습니다."};
        button(menu, 10, Material.PLAYER_HEAD, "&b" + plugin.name(target), () -> run(viewer, "info", id), info);
        button(menu, 12, Material.CHEST, "&e인벤토리 조회", () -> run(viewer, "invsee", id, "view"));
        button(menu, 13, Material.ANVIL, "&6인벤토리 수정", () -> run(viewer, "invsee", id, "edit"));
        button(menu, 14, Material.ENDER_CHEST, "&e엔더 상자 조회", () -> run(viewer, "endersee", id, "view"));
        button(menu, 15, Material.SMITHING_TABLE, "&6엔더 상자 수정", () -> run(viewer, "endersee", id, "edit"));
        button(menu, 19, Material.ENDER_PEARL, "&a대상에게 이동", () -> { viewer.closeInventory(); run(viewer, "tp", id); });
        button(menu, 20, Material.ENDER_EYE, "&a대상을 여기로 이동", () -> { viewer.closeInventory(); run(viewer, "tphere", id); });
        button(menu, 21, Material.SPYGLASS, "&b관전 시작", () -> run(viewer, "spectate", id));
        button(menu, 22, Material.COMPASS, "&b관전 종료 · 원위치 복귀", () -> { viewer.closeInventory(); run(viewer, "spectate"); });
        button(menu, 23, Material.GLASS, "&b내 관리자 표시 설정", () -> visibility(viewer), "&7탭 목록 · 머리 위 이름 · 은신");
        button(menu, 24, Material.PACKED_ICE, "&b이동 동결 " + (plugin.state.frozen.contains(target.getUniqueId()) ? "해제" : "켜기"), () -> { run(viewer, "freeze", id); target(viewer, target); });
        button(menu, 28, Material.PAPER, "&e경고", () -> reason(viewer, "warn", id, null));
        button(menu, 29, Material.NOTE_BLOCK, "&6뮤트", () -> durations(viewer, target, "mute"));
        button(menu, 30, Material.JUKEBOX, "&a뮤트 해제", () -> { run(viewer, "unmute", id); target(viewer, target); });
        button(menu, 31, Material.IRON_DOOR, "&c킥", () -> reason(viewer, "kick", id, null));
        button(menu, 32, Material.BARRIER, "&c접속 차단", () -> durations(viewer, target, "ban"));
        button(menu, 33, Material.LIME_DYE, "&a접속 차단 해제", () -> { run(viewer, "unban", id); target(viewer, target); });
        button(menu, 45, Material.ARROW, "&e플레이어 목록", () -> players(viewer, false, "", 0));
        button(menu, 49, Material.CLOCK, "&e정보 새로고침", () -> target(viewer, plugin.resolve(id)));
        button(menu, 53, Material.BARRIER, "&c닫기", viewer::closeInventory);
        viewer.openInventory(menu.inventory);
    }

    void visibility(Player viewer) {
        Menu menu = create(viewer, 27, "&8관리 | 내 표시 설정");
        var flags = plugin.visibility.get(viewer.getUniqueId());
        String effective = !plugin.enabled("visibility") ? "&c설정에서 기능 비활성화됨"
                : !plugin.visibility.available() ? "&cPacketEvents가 필요합니다."
                : !plugin.allowed(viewer, "visibility") ? "&c이름 숨김 권한이 없습니다." : "&7클릭하여 전환";
        button(menu, 10, Material.PLAYER_HEAD, "&b탭 목록 숨김: " + status(flags.tab()),
                () -> { run(viewer, "hidetab"); visibility(viewer); }, effective, "&7ON: 모든 플레이어의 탭 목록에서 숨김");
        button(menu, 12, Material.NAME_TAG, "&b머리 위 이름 숨김: " + status(flags.name()),
                () -> { run(viewer, "hidename"); visibility(viewer); }, effective, "&7ON: 캐릭터 위 닉네임 숨김");
        button(menu, 14, Material.GLASS, "&b두 표시 함께 전환",
                () -> { run(viewer, "hideidentity"); visibility(viewer); }, effective, "&7캐릭터는 보이고 이름만 숨깁니다.");
        button(menu, 16, Material.ENDER_PEARL, "&b캐릭터 은신: " + status(plugin.staff.isVanished(viewer.getUniqueId())),
                () -> { run(viewer, "vanish"); visibility(viewer); }, "&7일반 플레이어에게 캐릭터도 숨김");
        button(menu, 22, Material.ARROW, "&e플레이어 목록", () -> players(viewer, false, "", 0));
        viewer.openInventory(menu.inventory);
    }

    private static String status(boolean hidden) { return hidden ? "&aON" : "&7OFF"; }

    private void durations(Player viewer, OfflinePlayer target, String action) {
        Menu menu = create(viewer, 27, "&8" + plugin.name(target) + " | " + (action.equals("ban") ? "차단" : "뮤트") + " 기간");
        String[] choices = {"5m", "30m", "1h", "1d", "7d", "perm"};
        for (int i = 0; i < choices.length; i++) {
            String duration = choices[i];
            button(menu, 10 + i, duration.equals("perm") ? Material.REDSTONE_BLOCK : Material.CLOCK,
                    "&e" + TimeSpec.label(TimeSpec.parse(duration)), () -> reason(viewer, action, target.getUniqueId().toString(), duration));
        }
        button(menu, 22, Material.NAME_TAG, "&e기간 직접 입력", () -> plugin.chat.prompt(viewer, "기간을 입력하세요. 예: 2h30m, 3d, perm", input -> {
            TimeSpec.parse(input); reason(viewer, action, target.getUniqueId().toString(), input);
        }));
        button(menu, 18, Material.ARROW, "&e뒤로", () -> target(viewer, target));
        viewer.openInventory(menu.inventory);
    }

    private void reason(Player player, String action, String target, String duration) {
        plugin.chat.prompt(player, "사유를 입력하세요. 기본 사유 사용: -", input -> {
            String reason = input.equals("-") ? plugin.cfg("warn-default-reason") : input;
            if (duration == null) run(player, action, target, reason); else run(player, action, target, duration, reason);
        });
    }

    void confirm(Player player, String title, Runnable action) {
        Menu menu = create(player, 27, title);
        button(menu, 11, Material.LIME_CONCRETE, "&a확인 · 실행", () -> { player.closeInventory(); action.run(); });
        button(menu, 15, Material.RED_CONCRETE, "&c취소", player::closeInventory);
        player.openInventory(menu.inventory);
    }

    private void run(Player player, String... args) {
        try { plugin.execute(player, args); } catch (Exception e) { plugin.error(player, e); }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId()) || !plugin.allowed(player, "gui") || !plugin.allowed(player, "use")) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        Runnable action = menu.actions.get(event.getRawSlot());
        if (action == null) return;
        // Remove the action until the scheduled click is handled to prevent duplicate executions.
        menu.actions.remove(event.getRawSlot());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
            try { plugin.require(player, "gui"); action.run(); } catch (Exception e) { plugin.error(player, e); }
            finally { menu.actions.put(event.getRawSlot(), action); }
        });
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
}
