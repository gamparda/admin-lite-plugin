package kr.openclaw.adminlite;

import java.util.*;

/** Target syntax shared by completion and tests; free-form content is never treated as a player. */
final class AdminTargets {
    record Target(String permission, boolean online, boolean messageId) {}
    private static final Map<String, Target> TARGETS = targets();
    static Target target(String sub) { return TARGETS.get(sub.toLowerCase(Locale.ROOT)); }
    static boolean reserved(String sub) {
        return target(sub) != null || Set.of("players", "vanish", "visibility", "hidetab", "hidename", "hideidentity", "broadcast", "bc", "chatlog", "clearchat", "reload", "help").contains(sub.toLowerCase(Locale.ROOT));
    }
    private static Map<String, Target> targets() {
        var values = new HashMap<String, Target>();
        values.put("menu", new Target("gui", false, false));
        values.put("info", new Target("info", false, false));
        for (String name : List.of("invsee", "endersee", "ecsee")) values.put(name, new Target("invsee", false, false));
        values.put("warn", new Target("warn", true, false));
        values.put("freeze", new Target("freeze", false, false));
        for (String name : List.of("mute", "unmute")) values.put(name, new Target("mute", false, false));
        values.put("kick", new Target("kick", true, false));
        for (String name : List.of("ban", "unban")) values.put(name, new Target("ban", false, false));
        for (String name : List.of("spectate", "spec")) values.put(name, new Target("spectate", true, false));
        for (String name : List.of("tp", "tphere")) values.put(name, new Target("teleport", true, false));
        for (String name : List.of("deletechat", "delchat")) values.put(name, new Target("deletechat", false, true));
        return Map.copyOf(values);
    }

    /** BerryNames' signed-command envelope must retain the original mute restrictions. */
    static String commandRoot(String input) {
        String command = input.stripLeading();
        if (command.startsWith("/")) command = command.substring(1);
        String[] parts = command.split("\\s+", 3);
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.equals("berrynames:berrycommand") || root.equals("berrycommand")) {
            if (parts.length < 2) return "";
            root = parts[1].toLowerCase(Locale.ROOT);
        }
        return root.substring(root.lastIndexOf(':') + 1);
    }
}
