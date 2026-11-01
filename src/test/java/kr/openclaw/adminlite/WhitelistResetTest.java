package kr.openclaw.adminlite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WhitelistResetTest {
    @TempDir Path dir;
    @Test void deletingTheJournalStartsEmptyAndLeavesNativeWhitelistAlone()throws Exception {
        Path file=dir.resolve("whitelist.db"),nativeFile=dir.resolve("whitelist.json");Files.writeString(nativeFile,"[kept-native-list]");
        UUID uuid=UUID.randomUUID();
        try(var store=new WhitelistStore(file)){store.append(new WhitelistStore.Identity(uuid,"Alpha"),"치지직",true,Instant.now(),ZoneId.of("Asia/Seoul"),new WhitelistStore.Actor(null,"CONSOLE"));}
        Files.delete(file);
        try(var store=new WhitelistStore(file)){assertTrue(store.latest(uuid).isEmpty());assertTrue(store.pending().isEmpty());}
        assertEquals("[kept-native-list]",Files.readString(nativeFile));
        try(var files=Files.list(dir)){assertEquals(2,files.count());}
    }
    @Test void unreadableJournalBecomesAnEmptyDatabaseWithoutCreatingABackup()throws Exception {
        Path file=dir.resolve("whitelist.db");Files.writeString(file,"not a sqlite database");
        var warnings=new ArrayList<String>();
        try(var store=new WhitelistStore(file,warnings::add)){assertTrue(store.current(1).isEmpty());}
        assertEquals(1,warnings.size());
        try(var store=new WhitelistStore(file,warnings::add)){assertTrue(store.pending().isEmpty());}
        assertEquals(1,warnings.size());try(var files=Files.list(dir)){assertEquals(1,files.count());}
    }
}
