package kr.openclaw.adminlite;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreLogicTest {
    @TempDir Path temporary;

    @Test void durationsAreExplicitAndCompound() {
        assertEquals(1_800_000, TimeSpec.parse("30m"));
        assertEquals(5_400_000, TimeSpec.parse("1h30m"));
        assertEquals(86_400_000, TimeSpec.parse("1D"));
        assertEquals(0, TimeSpec.parse("perm"));
        assertEquals(0, TimeSpec.parse("영구"));
        assertEquals("1시간 30분", TimeSpec.label(5_400_000));
    }

    @ParameterizedTest @ValueSource(strings = {"", "0m", "-1h", "30", "1mjunk", "a1h", "1h 2m", "999999999999999999999999d", "3651d", "1.5h"})
    void invalidDurationsCannotTurnIntoPermanentSanctions(String input) {
        assertThrows(IllegalArgumentException.class, () -> TimeSpec.parse(input));
    }

    @Test void currentMutesAndFreezeSurviveRestartAndRemovedMutesLeaveNoHistory() throws Exception {
        Path file = temporary.resolve("active.yml"); UUID uuid = UUID.randomUUID();
        ActiveState state = new ActiveState(file);
        state.setMute(uuid, new ActiveState.Mute("Player", "test", 0));
        state.toggleFrozen(uuid);
        ActiveState restored = new ActiveState(file);
        assertNotNull(restored.mute(uuid)); assertEquals(0, restored.mute(uuid).until()); assertTrue(restored.frozen.contains(uuid));
        restored.setMute(uuid, null); restored.toggleFrozen(uuid);
        assertFalse(Files.readString(file).contains(uuid.toString()));
        assertNull(new ActiveState(file).mute(uuid));
    }

    @Test void expiredMutesDoNotBlockAndAreRemoved() throws Exception {
        ActiveState state = new ActiveState(temporary.resolve("active.yml")); UUID uuid = UUID.randomUUID();
        state.setMute(uuid, new ActiveState.Mute("Player", "test", System.currentTimeMillis() - 1));
        assertNull(state.mute(uuid)); assertEquals(List.of(uuid), state.expire()); assertTrue(state.mutes.isEmpty());
        assertNull(new ActiveState(temporary.resolve("active.yml")).mute(uuid));
    }

    @Test void unreadableStateFailsInsteadOfSilentlyLosingCurrentSanctions() throws Exception {
        Path file = temporary.resolve("active.yml");
        Files.writeString(file, "mutes: [broken\n");
        assertThrows(Exception.class, () -> new ActiveState(file));
    }

    @Test void failedStateWriteRevertsInMemoryMute() throws Exception {
        Path file = temporary.resolve("active.yml"); ActiveState state = new ActiveState(file);
        Files.createDirectory(file); UUID uuid = UUID.randomUUID();
        assertThrows(Exception.class, () -> state.setMute(uuid, new ActiveState.Mute("Player", "test", 0)));
        assertNull(state.mute(uuid));
    }

    @Test void nbtWritePreservesUnrelatedDataAndRejectsConcurrentChanges() throws Exception {
        Path file = temporary.resolve("player.dat");
        CompoundBinaryTag root = CompoundBinaryTag.builder().putString("unrelated", "keep")
                .put("equipment", CompoundBinaryTag.builder().putString("saddle", "keep").build())
                .putInt("DataVersion", 5023).build();
        BinaryTagIO.writer().write(root, file, BinaryTagIO.Compression.GZIP);
        PlayerDataStore.Document before = PlayerDataStore.read(file);
        PlayerDataStore.Document after = PlayerDataStore.write(before, root.putInt("SelectedItemSlot", 2));
        assertEquals("keep", after.root().getString("unrelated"));
        assertEquals("keep", after.root().getCompound("equipment").getString("saddle"));
        assertEquals(2, PlayerDataStore.read(file).root().getInt("SelectedItemSlot"));
        assertThrows(Exception.class, () -> PlayerDataStore.write(before, root));
        assertEquals(2, PlayerDataStore.read(file).root().getInt("SelectedItemSlot"));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }

    @Test void findsOnlyKnownPlayerDataLocations() throws Exception {
        PlayerDataStore store = new PlayerDataStore(temporary); UUID uuid = UUID.randomUUID();
        assertNull(store.find(uuid));
        Path legacy = temporary.resolve("playerdata").resolve(uuid + ".dat"); Files.createDirectories(legacy.getParent()); Files.write(legacy, new byte[0]);
        assertEquals(legacy, store.find(uuid));
        Path modern = temporary.resolve("players/data").resolve(uuid + ".dat"); Files.createDirectories(modern.getParent()); Files.write(modern, new byte[0]);
        assertEquals(modern, store.find(uuid));
    }
}
