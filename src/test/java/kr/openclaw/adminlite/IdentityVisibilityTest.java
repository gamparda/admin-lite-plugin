package kr.openclaw.adminlite;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static kr.openclaw.adminlite.ClientIdentityView.*;

class IdentityVisibilityTest {
    @TempDir Path directory;
    final UUID admin = UUID.randomUUID(), ordinary = UUID.randomUUID();
    final ClientIdentityView view = new ClientIdentityView();
    final Map<UUID, VisibilitySettings.Flags> hideBoth = Map.of(admin, new VisibilitySettings.Flags(true, true));
    final Style styled = new Style(Component.text("Staff"), Component.text("[OP]"), Component.text("!"), "ALWAYS", "NEVER", "gold", "ALL");
    TeamPacket team(String name, Mode mode, Style style, String... members) { return new TeamPacket(name, mode, style, List.of(members)); }
    void profiles() { view.profile(admin, "딸기대장", true, true, false); view.profile(ordinary, "베리이", true, true, false); }

    @Test void hideTabDoesNotRemoveProfileBodyOrOtherPlayer() {
        profiles();
        Changes change = view.repaint(Map.of(admin, new VisibilitySettings.Flags(true, false)));
        assertEquals(List.of(new Listing(admin, false)), change.listing()); assertTrue(change.teams().isEmpty());
        assertEquals(List.of(new Listing(admin, true)), view.repaint(Map.of()).listing());
    }
    @Test void tabUpdatesAreSuppressedAndRestoreMostRecentBaseIntent() {
        profiles(); view.repaint(hideBoth);
        view.profile(admin, null, false, true, true);
        assertTrue(view.repaint(hideBoth).listing().isEmpty());
        view.profile(admin, null, false, false, true);
        assertTrue(view.repaint(Map.of()).listing().isEmpty(), "off must not undo another plugin's unlisted status");
    }
    @Test void nameOnlyLeavesTabAndOtherTeamMembersVisible() {
        profiles();
        var packet = team("staff", Mode.CREATE, styled, "딸기대장", "베리이");
        var projected = view.team(packet, Map.of(admin, new VisibilitySettings.Flags(false, true)));
        assertEquals(List.of("베리이"), projected.members()); assertEquals(2, packet.members().size());
        Changes change = view.repaint(Map.of(admin, new VisibilitySettings.Flags(false, true)));
        assertTrue(change.listing().isEmpty());
        TeamPacket hidden = change.teams().getFirst(); assertEquals(List.of("딸기대장"), hidden.members());
        assertEquals(styled.hidden(), hidden.style()); assertEquals("NEVER", hidden.style().visibility());
        Changes restored = view.repaint(Map.of());
        assertEquals(Mode.REMOVE, restored.teams().getFirst().mode());
        assertEquals(team("staff", Mode.ADD_ENTITIES, null, "딸기대장"), restored.teams().getLast());
    }
    @Test void offRestoresLatestTeamAfterMembershipAndStyleChange() {
        profiles(); view.team(team("staff", Mode.CREATE, styled, "딸기대장"), Map.of()); view.repaint(hideBoth);
        view.team(team("new", Mode.CREATE, Style.DEFAULT, "딸기대장"), hideBoth);
        var refreshed = view.repaint(hideBoth);
        assertEquals(Style.DEFAULT.hidden(), refreshed.teams().getFirst().style());
        assertEquals("new", view.repaint(Map.of()).teams().getLast().team());
    }
    @Test void removedBaseTeamDoesNotGetRestored() {
        profiles(); view.team(team("staff", Mode.CREATE, styled, "딸기대장"), Map.of()); view.repaint(hideBoth);
        view.team(team("staff", Mode.REMOVE, null), hideBoth); view.repaint(hideBoth);
        assertEquals(1, view.repaint(Map.of()).teams().size());
    }
    @Test void nameChangeReplacesShadowAndRestoresCurrentBerryAlias() {
        profiles(); view.team(team("staff", Mode.CREATE, styled, "딸기대장"), Map.of()); view.repaint(hideBoth);
        view.profile(admin, "새대장", true, true, true);
        Changes renamed = view.repaint(hideBoth);
        assertEquals(Mode.REMOVE, renamed.teams().getFirst().mode());
        assertEquals(List.of("새대장"), renamed.teams().getLast().members());
        assertEquals(team("staff", Mode.ADD_ENTITIES, null, "새대장"), view.repaint(Map.of()).teams().getLast());
    }
    @Test void berryHideShowRefreshRebasesTrackedMembershipAcrossRemoveAdd() {
        profiles(); view.team(team("staff", Mode.CREATE, styled, "딸기대장"), Map.of()); view.repaint(hideBoth);
        view.remove(admin); assertEquals(Mode.REMOVE, view.repaint(hideBoth).teams().getFirst().mode());
        view.profile(admin, "변경대장", true, true, true);
        assertEquals(styled.hidden(), view.repaint(hideBoth).teams().getFirst().style());
        assertEquals(List.of("변경대장"), view.repaint(Map.of()).teams().getLast().members());
    }
    @Test void removalNeverReintroducesVanishedOrDisconnectedProfile() {
        profiles(); view.repaint(hideBoth); view.remove(admin);
        Changes change = view.repaint(Map.of());
        assertTrue(change.listing().isEmpty()); assertEquals(1, change.teams().size()); assertEquals(Mode.REMOVE, change.teams().getFirst().mode());
    }
    @Test void futureJoinReceivesPersistentPolicyImmediately() {
        view.profile(admin, "딸기대장", true, true, true);
        Changes change = view.repaint(hideBoth);
        assertTrue(change.listing().isEmpty(), "original packet is already rewritten to unlisted");
        assertEquals("NEVER", change.teams().getFirst().style().visibility());
        assertEquals(List.of(new Listing(admin, true)), view.repaint(Map.of()).listing());
    }
    @Test void originalRemoveMemberCannotStripTheShadowTeam() {
        profiles(); view.team(team("staff", Mode.CREATE, styled, "딸기대장", "베리이"), Map.of()); view.repaint(hideBoth);
        assertTrue(view.team(team("staff", Mode.REMOVE_ENTITIES, null, "딸기대장"), hideBoth).members().isEmpty());
        assertEquals(Style.DEFAULT.hidden(), view.repaint(hideBoth).teams().getFirst().style());
        assertEquals(1, view.repaint(Map.of()).teams().size());
    }
    @Test void preferencesPersistOnlyCurrentFlagsByUuid() throws Exception {
        Path file = directory.resolve("visibility.yml");
        var settings = new VisibilitySettings(file); assertEquals(VisibilitySettings.Flags.VISIBLE, settings.get(admin));
        settings.set(admin, new VisibilitySettings.Flags(true, false));
        assertEquals(new VisibilitySettings.Flags(true, false), new VisibilitySettings(file).get(admin));
        settings.set(admin, new VisibilitySettings.Flags(false, true));
        assertEquals(new VisibilitySettings.Flags(false, true), new VisibilitySettings(file).get(admin));
        settings.set(admin, VisibilitySettings.Flags.VISIBLE);
        assertFalse(Files.readString(file).contains(admin.toString()));
    }
    @Test void failedSaveRollsBackActivePreferences() throws Exception {
        Path file = directory.resolve("occupied"); var settings = new VisibilitySettings(file);
        Files.createDirectories(file); Files.writeString(file.resolve("child"), "prevent replacing nonempty directory");
        assertThrows(Exception.class, () -> settings.set(admin, new VisibilitySettings.Flags(true, true)));
        assertEquals(VisibilitySettings.Flags.VISIBLE, settings.get(admin));
    }
    @Test void inputsAndCompletionHaveNoPlayerTargetAmbiguity() {
        assertTrue(VisibilitySettings.requested("ON", false)); assertFalse(VisibilitySettings.requested("off", true));
        assertFalse(VisibilitySettings.requested("toggle", true)); assertTrue(VisibilitySettings.requested("toggle", false));
        assertThrows(IllegalArgumentException.class, () -> VisibilitySettings.requested("tru", false));
        for (String sub : List.of("visibility", "hidetab", "hidename", "hideidentity")) { assertTrue(AdminTargets.reserved(sub)); assertNull(AdminTargets.target(sub)); }
    }
}
