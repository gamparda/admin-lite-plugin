package kr.openclaw.adminlite;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static kr.openclaw.adminlite.WhitelistStore.*;
import static org.junit.jupiter.api.Assertions.*;

class WhitelistServiceTest {
    @TempDir Path directory;
    final Identity player=new Identity(UUID.randomUUID(),"TestPlayer");
    final Actor actor=new Actor(null,"CONSOLE");
    final Instant now=Instant.parse("2026-10-21T13:22:33Z");
    final ZoneId zone=ZoneId.of("Asia/Seoul");
    Clock clock(){return Clock.fixed(now,ZoneOffset.UTC);}
    @Test void noNicknameNeverTouchesNativeWhitelistOrJournal() throws Exception {
        try(var store=new WhitelistStore(directory.resolve("whitelist.db"))) {
            var changed=new AtomicBoolean(); var service=new WhitelistService(store,(id,added)->changed.set(true),clock());
            for(String missing:List.of(""," ","\t")) assertThrows(IllegalArgumentException.class,()->service.add(player,missing,actor,zone));
            assertFalse(changed.get()); assertTrue(store.history(player.uuid(),1).isEmpty());
        }
    }
    @Test void databaseFailurePreventsNativeAddition() throws Exception {
        var store=new WhitelistStore(directory.resolve("whitelist.db")); store.close();
        var changed=new AtomicBoolean(); var service=new WhitelistService(store,(id,added)->changed.set(true),clock());
        assertThrows(Exception.class,()->service.add(player,"이름",actor,zone)); assertFalse(changed.get());
    }
    @Test void journalIsDurableBeforeNativeMutationAndSuccessIsMarked() throws Exception {
        Path file=directory.resolve("whitelist.db");
        try(var store=new WhitelistStore(file)) {
            var service=new WhitelistService(store,(id,added)->{
                try(var observer=new WhitelistStore(file)) {
                    var pending=observer.latest(id.uuid()).orElseThrow(); assertFalse(pending.applied());
                    assertEquals("확인닉",pending.chzzk()); assertEquals(now,pending.at()); assertTrue(added);
                }
            },clock());
            var result=service.add(player,"확인닉",actor,zone); assertTrue(result.applied()); assertTrue(store.pending().isEmpty());
        }
    }
    @Test void nativeFailureStaysPendingAndRestartRetriesWithoutDuplicateHistory() throws Exception {
        Path file=directory.resolve("whitelist.db");
        try(var store=new WhitelistStore(file)) {
            var service=new WhitelistService(store,(id,added)->{throw new IllegalStateException("native unavailable");},clock());
            assertThrows(IllegalStateException.class,()->service.add(player,"보존닉",actor,zone)); assertEquals(1,store.pending().size());
        }
        try(var store=new WhitelistStore(file)) {
            var nativeList=new HashSet<UUID>(); var service=new WhitelistService(store,(id,added)->{if(added)nativeList.add(id.uuid());else nativeList.remove(id.uuid());},clock());
            service.recover(); service.recover(); assertTrue(nativeList.contains(player.uuid()));
            assertEquals(1,store.history(player.uuid(),1).size()); assertTrue(store.pending().isEmpty());
        }
    }
    @Test void nativeChangeBeforeCrashCanBeSafelyReapplied() throws Exception {
        try(var store=new WhitelistStore(directory.resolve("whitelist.db"))) {
            store.append(player,"이름",true,now,zone,actor); // crash after native add, before applied marker
            var nativeList=new HashSet<>(Set.of(player.uuid()));
            var service=new WhitelistService(store,(id,added)->{if(added)nativeList.add(id.uuid());else nativeList.remove(id.uuid());},clock());
            service.recover(); assertEquals(Set.of(player.uuid()),nativeList); assertTrue(store.pending().isEmpty());
            service.remove(player,actor,zone); assertTrue(nativeList.isEmpty());
            var removed=store.latest(player.uuid()).orElseThrow(); assertEquals("이름",removed.chzzk()); assertFalse(removed.added());
            assertEquals(2,store.history(player.uuid(),1).size());
        }
    }
}
