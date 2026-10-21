package kr.openclaw.adminlite;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static kr.openclaw.adminlite.WhitelistStore.*;
import static org.junit.jupiter.api.Assertions.*;

class WhitelistStoreTest {
    @TempDir Path directory;
    final Identity player=new Identity(UUID.randomUUID(),"TestPlayer");
    final Actor staff=new Actor(UUID.randomUUID(),"Admin");
    final Instant at=Instant.parse("2026-10-21T13:22:33.456Z");
    final ZoneId zone=ZoneId.of("Asia/Seoul");
    Path file(){return directory.resolve("whitelist.db");}
    @Test void uuidNicknameAdministratorTimestampAndZoneSurviveRestart() throws Exception {
        try(var store=new WhitelistStore(file())) {
            var entry=store.append(player,"  감파르다  ",true,at,zone,staff); store.applied(entry.id());
        }
        try(var store=new WhitelistStore(file())) {
            var entry=store.latest(player.uuid()).orElseThrow();
            assertEquals(player,entry.player()); assertEquals("감파르다",entry.chzzk()); assertEquals(at,entry.at());
            assertEquals(zone,entry.zone()); assertEquals(staff,entry.actor()); assertTrue(entry.applied());
            assertEquals("2026-10-21T22:22:33.456+09:00[Asia/Seoul]",entry.at().atZone(entry.zone()).toString());
        }
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t","\n","이름\n다음","이름\u0000","\"\"","\" \"","''"})
    void missingOrControlCharacterNicknameNeverCreatesRegistration(String nickname) throws Exception {
        try(var store=new WhitelistStore(file())) {
            assertThrows(IllegalArgumentException.class,()->store.append(player,nickname,true,at,zone,staff));
            assertTrue(store.latest(player.uuid()).isEmpty()); assertTrue(store.pending().isEmpty());
        }
    }
    @Test void nicknamesAreBoundSqlValuesAndExactSearchDoesNotMatchWildcards() throws Exception {
        String text="' OR 1=1; -- %_";
        try(var store=new WhitelistStore(file())) {
            store.append(player,text,true,at,zone,staff);
            store.append(new Identity(UUID.randomUUID(),"Other"),"다른이름",true,at,zone,staff);
            assertEquals(text,store.search(text,1).getFirst().chzzk()); assertEquals(1,store.search(text,1).size());
            assertTrue(store.search("%",1).isEmpty()); assertEquals(2,store.current(1).size());
        }
    }
    @Test void registrationChangesAndRemovalsRemainQueryableByOldChzzkName() throws Exception {
        try(var store=new WhitelistStore(file())) {
            store.append(player,"이전닉",true,at,zone,staff);
            store.append(player,"현재닉",true,at.plusSeconds(1),zone,new Actor(null,"CONSOLE"));
            store.append(player,"현재닉",false,at.plusSeconds(2),zone,staff);
            assertTrue(store.current(1).isEmpty()); assertEquals(3,store.history(player.uuid(),1).size());
            assertEquals("이전닉",store.search("이전닉",1).getFirst().chzzk());
            assertFalse(store.latest(player.uuid()).orElseThrow().added());
            store.append(player,"재등록",true,at.plusSeconds(3),zone,staff);
            assertEquals(1,store.current(1).size()); assertEquals(4,store.history(player.uuid(),1).size());
        }
    }
    @Test void sameDisplayNicknameDoesNotMergeDifferentMinecraftUuids() throws Exception {
        try(var store=new WhitelistStore(file())) {
            store.append(player,"같은치지직닉",true,at,zone,staff);
            var other=new Identity(UUID.randomUUID(),player.account()); store.append(other,"같은치지직닉",true,at,zone,staff);
            assertEquals(2,store.findAccount(player.account().toLowerCase(Locale.ROOT)).size());
            assertEquals(2,store.search("같은치지직닉",1).size());
            assertEquals(1,store.history(player.uuid(),1).size());
        }
    }
    @Test void pagesHaveStableNewestFirstOrderAndOneLookahead() throws Exception {
        try(var store=new WhitelistStore(file())) {
            for(int index=0;index<22;index++) store.append(player,"기록",true,at.plusSeconds(index),zone,staff);
            var first=store.history(player.uuid(),1); var second=store.history(player.uuid(),2);
            assertEquals(11,first.size()); assertEquals(11,second.size());
            assertEquals(first.get(10).id(),second.getFirst().id());
            assertEquals(2,store.history(player.uuid(),3).size()); assertTrue(store.history(player.uuid(),4).isEmpty());
            assertThrows(IllegalArgumentException.class,()->store.current(0));
            assertThrows(IllegalArgumentException.class,()->store.current(Integer.MAX_VALUE));
        }
    }
    @Test void futureOrCorruptDatabaseIsNeverReset() throws Exception {
        byte[] bad="not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file(),bad);
        assertThrows(Exception.class,()->new WhitelistStore(file())); assertArrayEquals(bad,Files.readAllBytes(file()));
        Files.delete(file());
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement()) { statement.execute("PRAGMA user_version=99"); }
        assertThrows(SQLException.class,()->new WhitelistStore(file()));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file());var statement=connection.createStatement();var result=statement.executeQuery("PRAGMA user_version")) { assertTrue(result.next()); assertEquals(99,result.getInt(1)); }
    }
    @Test void closedDatabaseCannotProduceSuccessOrEraseData() throws Exception {
        var store=new WhitelistStore(file()); store.append(player,"보존",true,at,zone,staff); store.close();
        assertThrows(SQLException.class,()->store.append(player,"변경",true,at,zone,staff));
        try(var restored=new WhitelistStore(file())) { assertEquals("보존",restored.latest(player.uuid()).orElseThrow().chzzk()); }
    }
}
