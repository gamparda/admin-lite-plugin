package kr.openclaw.adminlite;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Live, read-only directory. Empty data retains AdminLite's standalone account-name behavior. */
final class PlayerNames {
    record Entry(UUID uuid, long number, String account, String display, String defaultName) {
        List<String> aliases() { return List.of(display, defaultName, "#" + number); }
    }
    private final Supplier<List<Entry>> source;
    private final Function<UUID, Entry> lookup;
    PlayerNames(Supplier<List<Entry>> source) {
        this(source, uuid -> source.get().stream().filter(entry -> entry.uuid().equals(uuid)).findFirst().orElse(null));
    }
    PlayerNames(Supplier<List<Entry>> source, Function<UUID, Entry> lookup) { this.source = source; this.lookup = lookup; }
    List<Entry> entries() { return source.get(); }
    Entry find(UUID uuid) { return lookup.apply(uuid); }

    UUID resolveAlias(String input) {
        var matches = new LinkedHashSet<UUID>();
        for (Entry entry : entries()) if (entry.aliases().stream().anyMatch(name -> name.equalsIgnoreCase(input))) matches.add(entry.uuid());
        if (matches.size() > 1) throw new IllegalArgumentException("베리 이름이 여러 기록과 일치합니다. /bn who로 확인한 뒤 UUID를 지정하세요.");
        return matches.isEmpty() ? null : matches.getFirst();
    }
    String display(UUID uuid, String account) {
        Entry entry = find(uuid);
        return entry != null ? entry.display() : account != null ? account : uuid.toString();
    }
    String account(UUID uuid, String account) {
        if (account != null) return account;
        Entry entry = find(uuid);
        return entry == null ? uuid.toString() : entry.account();
    }
    boolean matches(UUID uuid, String account, String filter) {
        String query = filter.toLowerCase(Locale.ROOT);
        if (uuid.toString().contains(query) || account != null && account.toLowerCase(Locale.ROOT).contains(query)) return true;
        Entry entry = find(uuid);
        return entry != null && (entry.account().toLowerCase(Locale.ROOT).contains(query)
                || entry.aliases().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).contains(query)));
    }
    List<String> suggestions(UUID uuid, String account, String prefix, boolean messageId) {
        if (messageId && prefix.startsWith("#")) return List.of();
        var names = new LinkedHashSet<String>();
        Entry entry = find(uuid);
        if (entry != null) {
            names.add(entry.display()); names.add(entry.defaultName());
            if (!messageId && prefix.startsWith("#")) names.add("#" + entry.number());
        }
        if (account != null) names.add(account);
        return names.stream().filter(name -> name.regionMatches(true, 0, prefix, 0, prefix.length())).toList();
    }
}
