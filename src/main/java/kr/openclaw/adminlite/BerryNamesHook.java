package kr.openclaw.adminlite;

import kr.berrynames.api.AliasAwareCommands;
import kr.berrynames.api.BerryNamesApi;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.ServicePriority;

/** Only loaded when BerryNames is enabled; API classes are supplied by that plugin, never shaded. */
final class BerryNamesHook {
    static PlayerNames connect(AdminLitePlugin plugin) {
        var services = plugin.getServer().getServicesManager();
        if (services.load(BerryNamesApi.class) == null) return new PlayerNames(java.util.List::of);
        services.register(AliasAwareCommands.class, command -> command instanceof PluginIdentifiableCommand owned
                && owned.getPlugin() == plugin, plugin, ServicePriority.Normal);
        plugin.getLogger().info("BerryNames UUID 연동 활성화: 명령어, 자동완성, 관리창 이름·검색.");
        return new PlayerNames(() -> {
            BerryNamesApi api = services.load(BerryNamesApi.class);
            if (api == null) return java.util.List.of();
            return api.identities().stream().map(BerryNamesHook::entry).toList();
        }, uuid -> {
            BerryNamesApi api = services.load(BerryNamesApi.class);
            return api == null ? null : api.find(uuid).map(BerryNamesHook::entry).orElse(null);
        });
    }

    private static PlayerNames.Entry entry(BerryNamesApi.Identity entry) {
        return new PlayerNames.Entry(entry.uuid(), entry.number(), entry.accountName(), entry.displayName(), entry.defaultName());
    }
}
