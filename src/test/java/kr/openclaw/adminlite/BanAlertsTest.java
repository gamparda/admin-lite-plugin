package kr.openclaw.adminlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BanAlertsTest {
    @TempDir Path dir;

    @Test void causeFollowsTheServersCheckOrder() {
        assertEquals("계정 밴", BanAlerts.cause(true, true, true, "x"));
        assertEquals("화이트리스트 미등록", BanAlerts.cause(false, true, true, "x"));
        assertEquals("IP 밴", BanAlerts.cause(false, false, true, "x"));
        assertEquals("기타 (서버 점검 중)", BanAlerts.cause(false, false, false, "서버 점검 중"));
    }

    @Test void sameAccountAndIpIsReportedOncePerMinute() throws Exception {
        var alerts = new BanAlerts(null, dir.resolve("ban-alerts.yml")); UUID id = UUID.randomUUID();
        assertFalse(alerts.repeated(id, "1.2.3.4", 0));
        assertTrue(alerts.repeated(id, "1.2.3.4", BanAlerts.REPEAT_MILLIS - 1));
        assertFalse(alerts.repeated(id, "5.6.7.8", 10));
        assertFalse(alerts.repeated(id, "1.2.3.4", BanAlerts.REPEAT_MILLIS + 1));
    }

    @Test void pendingAlertsAndBannedIpAccountsSurviveARestart() throws Exception {
        Path file = dir.resolve("ban-alerts.yml"); UUID id = UUID.randomUUID();
        var alerts = new BanAlerts(null, file);
        alerts.pending.add("&c[밴 접속 시도] &falt"); alerts.bannedIpAccounts.put(id, new BanAlerts.Attempt("1.2.3.4", "alt", 42));
        alerts.save();
        var reloaded = new BanAlerts(null, file);
        assertEquals(List.of("&c[밴 접속 시도] &falt"), List.copyOf(reloaded.pending));
        assertEquals(new BanAlerts.Attempt("1.2.3.4", "alt", 42), reloaded.bannedIpAccounts.get(id));
    }

    @Test void damagedEntriesAreDroppedInsteadOfBreakingStartup() throws Exception {
        Path file = dir.resolve("ban-alerts.yml");
        Files.writeString(file, "pending: 3\nbanned-ip-accounts:\n  not-a-uuid:\n    ip: 1.2.3.4\n  " + UUID.randomUUID() + ":\n    name: no-ip\n");
        var alerts = new BanAlerts(null, file);
        assertTrue(alerts.pending.isEmpty()); assertTrue(alerts.bannedIpAccounts.isEmpty());
    }
}
