package kr.openclaw.adminlite;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class InventoryEditor implements Listener {
    private final AdminLitePlugin plugin;
    final PlayerDataStore storage;
    private final Map<UUID, Session> viewers = new HashMap<>();
    private final Set<UUID> offlineEditors = ConcurrentHashMap.newKeySet();
    private final Set<String> editLocks = new HashSet<>();

    final class Session implements InventoryHolder {
        final UUID viewer, target;
        final String name;
        final PlayerDataStore.Kind kind;
        final boolean online, edit;
        PlayerDataStore.Document document;
        ItemStack[] offlineItems;
        Inventory inventory;
        Session(Player viewer, org.bukkit.OfflinePlayer target, PlayerDataStore.Kind kind, boolean edit) {
            this.viewer = viewer.getUniqueId(); this.target = target.getUniqueId(); this.name = plugin.name(target);
            this.kind = kind; this.edit = edit; this.online = target.isOnline();
        }
        String key() { return target + ":" + kind; }
        int count() { return kind == PlayerDataStore.Kind.INVENTORY ? 41 : 27; }
        @Override public Inventory getInventory() { return inventory; }
    }

    InventoryEditor(AdminLitePlugin plugin) {
        this.plugin = plugin;
        storage = new PlayerDataStore(Bukkit.getWorlds().getFirst().getWorldFolder().toPath());
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 5, 5);
    }

    void open(Player viewer, OfflinePlayer target, PlayerDataStore.Kind kind, boolean requestedEdit) throws Exception {
        plugin.require(viewer, "invsee");
        if (!target.isOnline()) plugin.feature("offline-inventory");
        boolean edit = requestedEdit && plugin.allowed(viewer, "invsee.edit");
        if (requestedEdit && !edit) throw new IllegalArgumentException(plugin.cfg("no-permission"));
        if (viewer.getUniqueId().equals(target.getUniqueId()) && edit) throw new IllegalArgumentException("자신의 인벤토리는 직접 수정하세요. 조회 모드는 사용할 수 있습니다.");
        viewer.closeInventory();
        Session session = new Session(viewer, target, kind, edit);
        if (edit && editLocks.contains(session.key())) throw new IllegalArgumentException("이미 편집 중인 인벤토리입니다.");
        if (!session.online) {
            session.document = storage.read(session.target);
            if (session.document.root().getInt("DataVersion") != Bukkit.getUnsafe().getDataVersion()) {
                throw new IllegalArgumentException("이전 버전의 플레이어 데이터입니다. 대상이 현재 서버에 한 번 접속한 뒤 다시 시도하세요.");
            }
            session.offlineItems = PlayerDataStore.decode(session.document.root(), kind);
        }
        session.inventory = Bukkit.createInventory(session, kind == PlayerDataStore.Kind.INVENTORY ? 54 : 36,
                plugin.text("&8" + (edit ? "편집" : "조회") + " | " + session.name + (kind == PlayerDataStore.Kind.INVENTORY ? " 인벤토리" : " 엔더 상자")));
        if (edit) { editLocks.add(session.key()); if (!session.online) offlineEditors.add(session.target); }
        viewers.put(session.viewer, session);
        try {
            render(session);
            if (viewer.openInventory(session.inventory) == null) throw new IllegalArgumentException("다른 플러그인이 인벤토리 열기를 취소했습니다.");
            plugin.tell(viewer, edit ? "inventory-edit-open" : "inventory-view-open", "%player%", session.name);
        } catch (Exception e) { release(session); throw e; }
    }

    private ItemStack[] contents(Session session) {
        if (!session.online) return session.offlineItems;
        Player target = Bukkit.getPlayer(session.target);
        if (target == null || !target.isOnline()) throw new IllegalArgumentException("대상이 접속을 종료했습니다. 편집 창을 다시 여세요.");
        ItemStack[] items = new ItemStack[session.count()];
        Inventory source = session.kind == PlayerDataStore.Kind.INVENTORY ? target.getInventory() : target.getEnderChest();
        for (int i = 0; i < items.length; i++) items[i] = PlayerDataStore.copy(source.getItem(i));
        return items;
    }

    private void set(Session session, int slot, ItemStack value) throws Exception {
        if (session.online) {
            Player target = Bukkit.getPlayer(session.target);
            if (target == null || !target.isOnline()) throw new IllegalArgumentException("대상이 접속을 종료했습니다.");
            Inventory targetInventory = session.kind == PlayerDataStore.Kind.INVENTORY ? target.getInventory() : target.getEnderChest();
            targetInventory.setItem(slot, PlayerDataStore.copy(value));
        } else {
            if (Bukkit.getPlayer(session.target) != null) throw new IllegalArgumentException("대상이 접속했습니다. 편집 창을 다시 여세요.");
            ItemStack[] updated = session.offlineItems.clone(); updated[slot] = PlayerDataStore.copy(value);
            var root = PlayerDataStore.encode(session.document.root(), updated, session.kind);
            session.document = PlayerDataStore.write(session.document, root);
            session.offlineItems = updated;
        }
    }

    private void render(Session session) {
        ItemStack[] current = contents(session);
        for (int i = 0; i < session.count(); i++) session.inventory.setItem(i, PlayerDataStore.copy(current[i]));
        for (int i = session.count(); i < session.inventory.getSize(); i++) {
            session.inventory.setItem(i, plugin.item(Material.GRAY_STAINED_GLASS_PANE, "&8 "));
        }
        int info = session.kind == PlayerDataStore.Kind.INVENTORY ? 45 : 27;
        session.inventory.setItem(info, plugin.item(Material.PAPER, "&b" + session.name,
                "&7" + (session.online ? "온라인 · 실시간 반영" : "오프라인 · 변경 즉시 저장"),
                "&7" + (session.edit ? "편집 모드: 좌/우클릭 사용" : "조회 전용"),
                "&7Shift·숫자키·드래그는 사용하지 않습니다.",
                session.kind == PlayerDataStore.Kind.INVENTORY ? "&7슬롯 36~39: 신발·바지·흉갑·투구 / 40: 보조 손" : "&7엔더 상자 슬롯 0~26"));
        session.inventory.setItem(session.inventory.getSize() - 1, plugin.item(Material.BARRIER, "&c닫기"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        if (!(event.getWhoClicked() instanceof Player viewer) || !session.viewer.equals(viewer.getUniqueId()) || !canView(viewer, session)) {
            event.setCancelled(true); return;
        }
        int slot = event.getRawSlot();
        boolean top = slot >= 0 && slot < session.inventory.getSize();
        if (top && slot == session.inventory.getSize() - 1) {
            event.setCancelled(true); Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory()); return;
        }
        if (!session.edit || !plugin.allowed(viewer, "invsee.edit")) { event.setCancelled(true); return; }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) { event.setCancelled(true); return; }
        if (!top) return; // Normal cursor interactions with the viewer's own inventory remain vanilla.
        event.setCancelled(true);
        if (slot >= session.count()) return;
        try {
            ItemStack target = PlayerDataStore.copy(contents(session)[slot]);
            ItemStack cursor = PlayerDataStore.copy(viewer.getItemOnCursor());
            Transfer transfer = transfer(target, cursor, event.getClick() == ClickType.RIGHT);
            // Persist the target change before handing any item to the viewer.
            set(session, slot, transfer.target());
            viewer.setItemOnCursor(transfer.cursor());
            render(session);
        } catch (Exception e) {
            plugin.error(viewer, e);
            Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
        }
    }

    record Transfer(ItemStack target, ItemStack cursor) {}
    static Transfer transfer(ItemStack target, ItemStack cursor, boolean right) {
        target = PlayerDataStore.copy(target); cursor = PlayerDataStore.copy(cursor);
        if (PlayerDataStore.empty(cursor)) {
            if (PlayerDataStore.empty(target)) return new Transfer(null, null);
            if (!right) return new Transfer(null, target);
            int take = (target.getAmount() + 1) / 2;
            cursor = target.asQuantity(take); target.setAmount(target.getAmount() - take);
        } else if (PlayerDataStore.empty(target)) {
            int count = right ? 1 : Math.min(cursor.getAmount(), cursor.getMaxStackSize());
            target = cursor.asQuantity(count); cursor.setAmount(cursor.getAmount() - count);
        } else if (target.isSimilar(cursor)) {
            int move = Math.min(right ? 1 : cursor.getAmount(), Math.max(0, target.getMaxStackSize() - target.getAmount()));
            target.setAmount(target.getAmount() + move); cursor.setAmount(cursor.getAmount() - move);
        } else {
            ItemStack old = target; target = cursor; cursor = old;
        }
        return new Transfer(PlayerDataStore.copy(target), PlayerDataStore.copy(cursor));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }

    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) release(session);
    }

    private void release(Session session) {
        viewers.remove(session.viewer, session);
        if (session.edit) {
            editLocks.remove(session.key());
            if (!session.online && viewers.values().stream().noneMatch(s -> s.edit && !s.online && s.target.equals(session.target))) offlineEditors.remove(session.target);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void prelogin(AsyncPlayerPreLoginEvent event) {
        if (offlineEditors.contains(event.getUniqueId())) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.text(plugin.cfg("inventory-login-locked")));
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void login(PlayerLoginEvent event) {
        if (offlineEditors.contains(event.getPlayer().getUniqueId())) event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.text(plugin.cfg("inventory-login-locked")));
        else closeTarget(event.getPlayer().getUniqueId()); // Close any old read-only view before the online transition.
    }
    @EventHandler(priority = EventPriority.LOWEST) public void quit(PlayerQuitEvent event) { closeTarget(event.getPlayer().getUniqueId()); }

    private void closeTarget(UUID uuid) {
        for (Session session : List.copyOf(viewers.values())) if (session.target.equals(uuid) || session.viewer.equals(uuid)) {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer != null) viewer.closeInventory();
            release(session);
        }
    }

    private void refresh() {
        for (Session session : List.copyOf(viewers.values())) {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer == null || !canView(viewer, session) || session.edit && !plugin.allowed(viewer, "invsee.edit")) {
                if (viewer != null) viewer.closeInventory(); release(session); continue;
            }
            try { if (session.online) render(session); }
            catch (Exception e) { viewer.closeInventory(); plugin.error(viewer, e); }
        }
    }

    private boolean canView(Player player, Session session) {
        return plugin.allowed(player, "use") && plugin.allowed(player, "invsee") && plugin.enabled("inventory")
                && (session.online || plugin.enabled("offline-inventory"));
    }

    void shutdown() {
        for (Session session : List.copyOf(viewers.values())) {
            Player viewer = Bukkit.getPlayer(session.viewer);
            if (viewer != null) viewer.closeInventory(); release(session);
        }
    }
}
