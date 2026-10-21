package kr.openclaw.adminlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BerryNamesCompatibilityTest {
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private PlayerNames.Entry entry(UUID uuid, long number, String display) {
        return new PlayerNames.Entry(uuid, number, "ReusedAccount", display, number == 1 ? "베리일" : "베리이");
    }

    @Test void resolvesCurrentAndStableAliasesByUuidEvenWhenAccountsAreReused() {
        var names = new PlayerNames(() -> List.of(entry(first, 1, "딸기대장"), entry(second, 2, "베리이")));
        for (String input : List.of("딸기대장", "베리일", "#1")) assertEquals(first, names.resolveAlias(input));
        assertEquals(second, names.resolveAlias("베리이"));
        assertNull(names.resolveAlias("ReusedAccount"));
        assertNull(names.resolveAlias("딸기"));
    }
    @Test void changesAndProviderRemovalAreVisibleWithoutReadingYamlOrRestarting() {
        var source = new AtomicReference<>(List.of(entry(first, 1, "딸기대장")));
        var names = new PlayerNames(source::get);
        assertEquals("딸기대장", names.display(first, "Account"));
        source.set(List.of(entry(first, 1, "새대장")));
        assertEquals("새대장", names.display(first, "Account"));
        assertNull(names.resolveAlias("딸기대장"));
        assertEquals(first, names.resolveAlias("베리일"));
        source.set(List.of());
        assertEquals("Account", names.display(first, "Account"));
        assertEquals(first.toString(), names.display(first, null));
    }
    @Test void searchUsesDisplayDefaultAccountNumberAndUuid() {
        var names = new PlayerNames(() -> List.of(entry(first, 1, "딸기대장")));
        for (String filter : List.of("딸기", "베리일", "REUSED", "#1", first.toString().substring(0, 12))) assertTrue(names.matches(first, null, filter));
        assertFalse(names.matches(first, "Account", "없는이름"));
        assertEquals("ReusedAccount", names.account(first, null));
        assertEquals("CurrentAccount", names.account(first, "CurrentAccount"));
    }
    @Test void suggestionsUseCurrentAliasesAndKeepChatMessageIdsSeparate() {
        var names = new PlayerNames(() -> List.of(entry(first, 1, "딸기대장")));
        assertEquals(List.of("딸기대장"), names.suggestions(first, "Account", "딸", false));
        assertEquals(List.of("베리일"), names.suggestions(first, "Account", "베", false));
        assertEquals(List.of("#1"), names.suggestions(first, "Account", "#", false));
        assertTrue(names.suggestions(first, "Account", "#", true).isEmpty());
        assertEquals(List.of("Account"), names.suggestions(first, "Account", "Acc", false));
        assertEquals(1, new PlayerNames(() -> List.of(entry(first, 1, "베리일"))).suggestions(first, "Account", "베", false).size());
    }
    @Test void ambiguousDirectoryDoesNotChooseAPlayer() {
        var names = new PlayerNames(() -> List.of(entry(first, 1, "대장"), entry(second, 2, "대장")));
        assertThrows(IllegalArgumentException.class, () -> names.resolveAlias("대장"));
    }
    @Test void excludedPlayersKeepTheirNamesWithoutAZeroNumberTarget() {
        var names = new PlayerNames(() -> List.of(new PlayerNames.Entry(first,0,"Admin","운영자","Admin"), entry(second,1,"베리일")));
        assertEquals(first,names.resolveAlias("운영자")); assertEquals(first,names.resolveAlias("Admin"));
        assertNull(names.resolveAlias("#0")); assertEquals(second,names.resolveAlias("#1"));
        assertTrue(names.suggestions(first,"Admin","#",false).isEmpty());
        assertEquals("운영자",names.display(first,"Admin"));
    }

    @ParameterizedTest @ValueSource(strings={"menu","info","invsee","endersee","ecsee","warn","freeze","mute","unmute","kick","ban","unban","spectate","spec","tp","tphere","deletechat","delchat"})
    void targetCommandsHaveExplicitPlayerSyntax(String sub) { assertNotNull(AdminTargets.target(sub)); }

    @ParameterizedTest @ValueSource(strings={"broadcast","bc","reload","help","chatlog","clearchat","vanish","players"})
    void contentAndUtilityCommandsHaveNoPlayerTarget(String sub) { assertNull(AdminTargets.target(sub)); assertTrue(AdminTargets.reserved(sub)); }

    @Test void offlineAndPermissionRulesMatchAdminActions() {
        assertTrue(AdminTargets.target("kick").online());
        assertFalse(AdminTargets.target("ban").online());
        assertFalse(AdminTargets.target("invsee").online());
        assertTrue(AdminTargets.target("deletechat").messageId());
        assertEquals("teleport", AdminTargets.target("tp").permission());
        assertEquals("invsee", AdminTargets.target("ecsee").permission());
    }
    @ParameterizedTest @ValueSource(strings={"/w Alpha hi","/minecraft:w Alpha hi","/berrynames:berrycommand w Alpha hi","/berrycommand minecraft:w Alpha hi"})
    void muteRecognizesDirectAndTranslatedWhispers(String input) { assertEquals("w", AdminTargets.commandRoot(input)); }
}
