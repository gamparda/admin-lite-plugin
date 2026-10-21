package kr.openclaw.adminlite;

import net.kyori.adventure.text.Component;
import java.util.*;

/** Connection-local projection: never modifies a server scoreboard or a shared packet entry. */
final class ClientIdentityView {
    enum Mode { CREATE, REMOVE, UPDATE, ADD_ENTITIES, REMOVE_ENTITIES }
    record Style(Component display, Component prefix, Component suffix, String visibility, String collision, String color, String options) {
        static final Style DEFAULT = new Style(Component.empty(), Component.empty(), Component.empty(), "ALWAYS", "ALWAYS", "WHITE", "NONE");
        Style hidden() { return new Style(display, prefix, suffix, "NEVER", collision, color, options); }
    }
    record TeamPacket(String team, Mode mode, Style style, List<String> members) {}
    record Listing(UUID uuid, boolean listed) {}
    record Changes(List<TeamPacket> teams, List<Listing> listing) {}
    private static final class Team {
        Style style;
        final Set<String> members = new LinkedHashSet<>();
        Team(Style style) { this.style = style; }
    }
    private record Overlay(String team, String name, Style style) {}
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, String> profiles = new LinkedHashMap<>();
    private final Map<UUID, String> retiredNames = new HashMap<>();
    private final Map<UUID, Boolean> baseListing = new HashMap<>(), sentListing = new HashMap<>();
    private final Map<UUID, Overlay> overlays = new LinkedHashMap<>();
    private final String connectionKey = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    private int nextTeam;

    void profile(UUID uuid, String name, boolean add, Boolean listed, boolean hiddenTab) {
        if (add && name != null) {
            String old = profiles.put(uuid, name);
            if (old == null) old = retiredNames.remove(uuid);
            if (old != null && !old.equals(name)) for (Team team : teams.values()) if (team.members.remove(old)) team.members.add(name);
        }
        if (listed != null) { baseListing.put(uuid, listed); sentListing.put(uuid, listed && !hiddenTab); }
        else if (add) { baseListing.putIfAbsent(uuid, false); sentListing.putIfAbsent(uuid, false); }
    }
    void remove(UUID uuid) {
        String old = profiles.remove(uuid);
        if (old != null && membership(old) != null) retiredNames.put(uuid, old);
        baseListing.remove(uuid); sentListing.remove(uuid);
    }

    TeamPacket team(TeamPacket packet, Map<UUID, VisibilitySettings.Flags> policy) {
        Team team = teams.get(packet.team());
        switch (packet.mode()) {
            case CREATE -> { team = new Team(packet.style() == null ? Style.DEFAULT : packet.style()); teams.put(packet.team(), team); }
            case REMOVE -> teams.remove(packet.team());
            case UPDATE -> { if (team != null && packet.style() != null) team.style = packet.style(); }
            default -> { }
        }
        if (team != null) for (String member : packet.members()) {
            if (packet.mode() == Mode.REMOVE_ENTITIES) team.members.remove(member);
            else if (packet.mode() == Mode.CREATE || packet.mode() == Mode.ADD_ENTITIES) {
                for (Team other : teams.values()) other.members.remove(member);
                team.members.add(member);
            }
        }
        Set<String> hidden = new HashSet<>();
        policy.forEach((uuid, flags) -> { if (flags.name() && profiles.containsKey(uuid)) hidden.add(profiles.get(uuid)); });
        return new TeamPacket(packet.team(), packet.mode(), packet.style(), packet.members().stream().filter(name -> !hidden.contains(name)).toList());
    }

    Changes repaint(Map<UUID, VisibilitySettings.Flags> policy) {
        List<TeamPacket> output = new ArrayList<>(); List<Listing> listing = new ArrayList<>();
        for (var entry : List.copyOf(overlays.entrySet())) {
            UUID uuid = entry.getKey(); Overlay old = entry.getValue();
            if (!policy.getOrDefault(uuid, VisibilitySettings.Flags.VISIBLE).name() || !old.name().equals(profiles.get(uuid))) {
                output.add(new TeamPacket(old.team(), Mode.REMOVE, null, List.of()));
                if (old.name().equals(profiles.get(uuid))) {
                    String original = membership(old.name());
                    if (original != null) output.add(new TeamPacket(original, Mode.ADD_ENTITIES, null, List.of(old.name())));
                }
                overlays.remove(uuid);
            }
        }
        profiles.forEach((uuid, name) -> {
            VisibilitySettings.Flags flags = policy.getOrDefault(uuid, VisibilitySettings.Flags.VISIBLE);
            boolean listed = baseListing.getOrDefault(uuid, false) && !flags.tab();
            if (!Objects.equals(sentListing.get(uuid), listed)) { listing.add(new Listing(uuid, listed)); sentListing.put(uuid, listed); }
            if (!flags.name()) return;
            String original = membership(name);
            Style style = (original == null ? Style.DEFAULT : teams.get(original).style).hidden();
            Overlay old = overlays.get(uuid);
            if (old == null) {
                String key;
                do { key = "al_" + connectionKey + "_" + Integer.toHexString(++nextTeam); } while (teams.containsKey(key));
                overlays.put(uuid, new Overlay(key, name, style));
                output.add(new TeamPacket(key, Mode.CREATE, style, List.of(name)));
            } else {
                if (!old.style().equals(style)) {
                    overlays.put(uuid, new Overlay(old.team(), name, style));
                    output.add(new TeamPacket(old.team(), Mode.UPDATE, style, List.of()));
                }
                output.add(new TeamPacket(old.team(), Mode.ADD_ENTITIES, null, List.of(name)));
            }
        });
        return new Changes(List.copyOf(output), List.copyOf(listing));
    }

    private String membership(String name) {
        return teams.entrySet().stream().filter(entry -> entry.getValue().members.contains(name)).map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
