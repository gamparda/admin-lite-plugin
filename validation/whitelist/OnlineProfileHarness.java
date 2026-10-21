package verification.whitelist;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Optional online-mode test against Paper's actual external profile lookup. */
public final class OnlineProfileHarness extends JavaPlugin {
    final List<String> checks=new ArrayList<>(); int attempts;
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                check(Bukkit.getOnlineMode(),"online-mode=true fixture");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"whitelist add Notch 온라인프로필검증");
                Bukkit.getScheduler().runTaskTimer(this,()->{
                    try { poll(); } catch(Throwable error) { failure(error); }
                },5,5);
            } catch(Throwable error) { failure(error); }
        },30);
    }
    void poll() throws Exception {
        var admin=(JavaPlugin)Bukkit.getPluginManager().getPlugin("AdminLite");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+admin.getDataFolder().toPath().resolve("whitelist.db").toAbsolutePath());
            var statement=connection.createStatement();var result=statement.executeQuery("SELECT * FROM whitelist_events WHERE chzzk='온라인프로필검증' ORDER BY id DESC LIMIT 1")) {
            if(!result.next()) { if(++attempts>80) throw new AssertionError("online profile registration did not complete"); return; }
            UUID uuid=UUID.fromString(result.getString("uuid"));
            check(uuid.version()==4,"new online account uses real version-4 Mojang UUID");
            check(result.getString("account").equalsIgnoreCase("Notch"),"resolved canonical account name saved");
            check(result.getInt("applied")==1 && Bukkit.getOfflinePlayer(uuid).isWhitelisted(),"asynchronously resolved online profile added to native whitelist");
            String json=Files.readString(Path.of("whitelist.json"));
            check(json.contains(uuid.toString()) && json.contains("Notch"),"online registration persisted with canonical UUID and account");
            Files.writeString(Path.of("whitelist-online.ok"),String.join("\n",checks)+"\n"+checks.size()+" checks passed\n");
            getLogger().info("WHITELIST_ONLINE_SUCCESS "+checks.size()); Bukkit.getScheduler().cancelTasks(this); Bukkit.shutdown();
        }
    }
    void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); checks.add("PASS "+label); getLogger().info(checks.getLast()); }
    void failure(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE,"Online profile fixture failed",error);
        try { Files.writeString(Path.of("whitelist-online-failure.txt"),error.toString()); } catch(Exception ignored){}
        Bukkit.getScheduler().cancelTasks(this); Bukkit.shutdown();
    }
}
