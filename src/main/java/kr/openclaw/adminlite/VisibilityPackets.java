package kr.openclaw.adminlite;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runs after BerryNames so the projection uses the profile names each viewer actually receives. */
final class VisibilityPackets extends PacketListenerAbstract implements IdentityVisibility.Transport {
    private final IdentityVisibility owner;
    private final Map<User, ClientIdentityView> clients = new ConcurrentHashMap<>();
    private volatile boolean closed;

    VisibilityPackets(IdentityVisibility owner) {
        super(PacketListenerPriority.HIGHEST);
        this.owner = owner;
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override public void onPacketSend(PacketSendEvent event) {
        if (closed || event.isCancelled()) return;
        var type = event.getPacketType();
        if (type != PacketType.Play.Server.PLAYER_INFO_UPDATE && type != PacketType.Play.Server.PLAYER_INFO_REMOVE
                && type != PacketType.Play.Server.TEAMS) return;
        User user = event.getUser();
        ClientIdentityView view = clients.computeIfAbsent(user, ignored -> new ClientIdentityView());
        var policy = owner.policy();
        if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            var packet = new WrapperPlayServerPlayerInfoUpdate(event);
            boolean add = packet.getActions().contains(Action.ADD_PLAYER);
            boolean listed = packet.getActions().contains(Action.UPDATE_LISTED);
            List<PlayerInfo> entries = new ArrayList<>(packet.getEntries().size());
            boolean changed = false;
            for (PlayerInfo original : packet.getEntries()) {
                UUID uuid = original.getProfileId();
                boolean hide = policy.getOrDefault(uuid, VisibilitySettings.Flags.VISIBLE).tab();
                view.profile(uuid, add ? original.getGameProfile().getName() : null, add,
                        listed ? original.isListed() : null, hide);
                if (listed && hide && original.isListed()) {
                    PlayerInfo copy = new PlayerInfo(original);
                    copy.setListed(false); entries.add(copy); changed = true;
                } else entries.add(original);
            }
            if (changed) { packet.setEntries(entries); event.markForReEncode(true); }
        } else if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
            new WrapperPlayServerPlayerInfoRemove(event).getProfileIds().forEach(view::remove);
        } else {
            var packet = new WrapperPlayServerTeams(event);
            var original = new ClientIdentityView.TeamPacket(packet.getTeamName(), ClientIdentityView.Mode.valueOf(packet.getTeamMode().name()),
                    packet.getTeamInfo().map(VisibilityPackets::style).orElse(null), List.copyOf(packet.getPlayers()));
            var projected = view.team(original, policy);
            if (!original.members().equals(projected.members())) {
                packet.setPlayers(projected.members()); event.markForReEncode(true);
            }
        }
        // ADD_PLAYER and the base team must reach the client before dependent team/list updates.
        event.getTasksAfterSend().add(() -> repaint(user, view));
    }

    @Override public void onUserDisconnect(UserDisconnectEvent event) { clients.remove(event.getUser()); }

    private void repaint(User user, ClientIdentityView view) {
        if (!ChannelHelper.isOpen(user.getChannel())) return;
        var changes = view.repaint(owner.policy());
        for (var team : changes.teams()) user.sendPacketSilently(new WrapperPlayServerTeams(team.team(), TeamMode.valueOf(team.mode().name()),
                team.style() == null ? (ScoreBoardTeamInfo) null : info(team.style()), team.members()));
        if (!changes.listing().isEmpty()) {
            List<PlayerInfo> entries = changes.listing().stream().map(value -> {
                var info = new PlayerInfo(value.uuid()); info.setListed(value.listed()); return info;
            }).toList();
            user.sendPacketSilently(new WrapperPlayServerPlayerInfoUpdate(Action.UPDATE_LISTED, entries));
        }
    }

    @Override public void refresh() {
        clients.forEach((user, view) -> {
            if (ChannelHelper.isOpen(user.getChannel())) ChannelHelper.runInEventLoop(user.getChannel(), () -> repaint(user, view));
        });
    }

    @Override public void close() {
        closed = true;
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
        refresh(); // Owner has already cleared its policy; restore each viewer's original client state.
        clients.clear();
    }

    static ClientIdentityView.Style style(ScoreBoardTeamInfo info) {
        return new ClientIdentityView.Style(info.getDisplayName(), info.getPrefix(), info.getSuffix(), info.getTagVisibility().name(),
                info.getCollisionRule().name(), info.getColor() == null ? "reset" : info.getColor().toString(), info.getOptionData().name());
    }
    static ScoreBoardTeamInfo info(ClientIdentityView.Style style) {
        NamedTextColor color = NamedTextColor.NAMES.value(style.color().toLowerCase(Locale.ROOT));
        return new ScoreBoardTeamInfo(style.display(), style.prefix(), style.suffix(), NameTagVisibility.valueOf(style.visibility()),
                CollisionRule.valueOf(style.collision()), color, OptionData.valueOf(style.options()));
    }
}
