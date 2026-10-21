package kr.openclaw.adminlite;

import java.time.*;
import java.util.*;
import static kr.openclaw.adminlite.WhitelistStore.*;

/** No login listener: this is a staff registration workflow, not a new admission policy. */
final class WhitelistService {
    interface NativeList { void set(Identity player, boolean added) throws Exception; }
    private final WhitelistStore store;
    private final NativeList nativeList;
    private final Clock clock;
    WhitelistService(WhitelistStore store, NativeList nativeList, Clock clock) { this.store=store; this.nativeList=nativeList; this.clock=clock; }
    Entry add(Identity player, String nickname, Actor actor, ZoneId zone) throws Exception {
        String clean=WhitelistStore.nickname(nickname); recover();
        var entry=store.append(player,clean,true,clock.instant(),zone,actor); apply(entry); return store.latest(player.uuid()).orElseThrow();
    }
    Entry remove(Identity player, Actor actor, ZoneId zone) throws Exception {
        recover(); var previous=store.latest(player.uuid());
        var entry=store.append(player,previous.map(Entry::chzzk).orElse(""),false,clock.instant(),zone,actor); apply(entry); return store.latest(player.uuid()).orElseThrow();
    }
    void recover() throws Exception { for(var entry:store.pending()) apply(entry); }
    private void apply(Entry entry) throws Exception { nativeList.set(entry.player(),entry.added()); store.applied(entry.id()); }
}
