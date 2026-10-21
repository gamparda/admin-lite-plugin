package kr.openclaw.adminlite;

import net.kyori.adventure.nbt.*;
import org.bukkit.inventory.ItemStack;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class PlayerDataStore {
    enum Kind { INVENTORY, ENDER }
    record Document(Path path, byte[] fingerprint, CompoundBinaryTag root) {}
    private static final String[] EQUIPMENT = {"feet", "legs", "chest", "head", "offhand"};
    private static final BinaryTagIO.Reader READER = BinaryTagIO.reader(64L * 1024 * 1024);
    private final Path world;

    PlayerDataStore(Path world) { this.world = world; }

    Path find(UUID uuid) {
        for (String folder : List.of("players/data", "playerdata", "players")) {
            Path path = world.resolve(folder).resolve(uuid + ".dat");
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    Document read(UUID uuid) throws Exception {
        Path path = find(uuid);
        if (path == null) throw new IllegalArgumentException("저장된 플레이어 데이터가 없습니다. 서버에 접속한 적이 있는지 확인하세요.");
        return read(path);
    }

    static Document read(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new Document(path, hash(bytes), READER.read(new ByteArrayInputStream(bytes), BinaryTagIO.Compression.GZIP));
    }

    static Document write(Document before, CompoundBinaryTag replacement) throws Exception {
        if (!MessageDigest.isEqual(before.fingerprint(), hash(Files.readAllBytes(before.path())))) {
            throw new IOException("플레이어 데이터가 외부에서 변경되었습니다. 편집 창을 다시 여세요.");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(replacement, output, BinaryTagIO.Compression.GZIP);
        byte[] bytes = output.toByteArray();
        // Validate the complete replacement before touching the original file.
        READER.read(new ByteArrayInputStream(bytes), BinaryTagIO.Compression.GZIP);
        AtomicFiles.write(before.path(), bytes);
        return new Document(before.path(), hash(bytes), replacement);
    }

    static ItemStack[] decode(CompoundBinaryTag root, Kind kind) throws Exception {
        String listName = kind == Kind.INVENTORY ? "Inventory" : "EnderItems";
        if (root.contains(listName) && !(root.get(listName) instanceof ListBinaryTag)) throw new IOException("잘못된 플레이어 인벤토리 형식입니다: " + listName);
        if (kind == Kind.INVENTORY && root.contains("equipment") && !(root.get("equipment") instanceof CompoundBinaryTag)) throw new IOException("잘못된 방어구 데이터 형식입니다.");
        ItemStack[] items = new ItemStack[kind == Kind.INVENTORY ? 41 : 27];
        for (BinaryTag tag : root.getList(kind == Kind.INVENTORY ? "Inventory" : "EnderItems", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag item = (CompoundBinaryTag) tag;
            int slot = item.getByte("Slot");
            if (slot >= 0 && slot < (kind == Kind.INVENTORY ? 36 : 27)) items[slot] = decodeItem(item, root.getInt("DataVersion"));
        }
        if (kind == Kind.INVENTORY) {
            CompoundBinaryTag equipment = root.getCompound("equipment");
            for (int i = 0; i < EQUIPMENT.length; i++) {
                CompoundBinaryTag item = equipment.getCompound(EQUIPMENT[i]);
                if (!item.isEmpty()) items[36 + i] = decodeItem(item, root.getInt("DataVersion"));
            }
        }
        return items;
    }

    static CompoundBinaryTag encode(CompoundBinaryTag root, ItemStack[] items, Kind kind) throws Exception {
        var list = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        int size = kind == Kind.INVENTORY ? 36 : 27;
        for (int slot = 0; slot < size; slot++) if (!empty(items[slot])) {
            list.add(encodeItem(items[slot]).putByte("Slot", (byte) slot));
        }
        CompoundBinaryTag result = root.put(kind == Kind.INVENTORY ? "Inventory" : "EnderItems", list.build());
        if (kind == Kind.INVENTORY) {
            CompoundBinaryTag equipment = root.getCompound("equipment");
            for (int i = 0; i < EQUIPMENT.length; i++) {
                equipment = empty(items[36 + i]) ? equipment.remove(EQUIPMENT[i]) : equipment.put(EQUIPMENT[i], encodeItem(items[36 + i]));
            }
            result = result.put("equipment", equipment);
        }
        return result;
    }

    private static ItemStack decodeItem(CompoundBinaryTag item, int version) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(item.remove("Slot").putInt("DataVersion", version), output, BinaryTagIO.Compression.GZIP);
        return ItemStack.deserializeBytes(output.toByteArray());
    }

    private static CompoundBinaryTag encodeItem(ItemStack item) throws Exception {
        return READER.read(new ByteArrayInputStream(item.serializeAsBytes()), BinaryTagIO.Compression.GZIP).remove("DataVersion");
    }

    static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }
    static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
}
