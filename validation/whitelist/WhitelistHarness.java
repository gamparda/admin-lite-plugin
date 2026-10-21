package verification.whitelist;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Destructive fixture for a disposable server only; never install on production. */
public final class WhitelistHarness extends JavaPlugin {
    final String account="NewWhitelist", nickname="테스트 이름 007";
    final UUID target=id(account), second=UUID.fromString("69e952ed-3ef2-3506-a34d-3cb551a330d4");
    final List<String> checks=new ArrayList<>();
    JavaPlugin admin;
    TabExecutor executor;
    Connection db;
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try { run(); } catch(Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE,"Whitelist fixture failed",error);
                try { Files.writeString(Path.of("whitelist-failure.txt"),String.join("\n",checks)+"\n"+error); } catch(Exception ignored){}
                Bukkit.shutdown();
            } finally { if(db!=null) try { db.close(); } catch(Exception ignored){} }
        },30);
    }
    void run() throws Exception {
        check(Bukkit.getVersion().contains("26.3-206"),"exact Paper 26.3-206");
        admin=(JavaPlugin)Bukkit.getPluginManager().getPlugin("AdminLite");
        check(admin.isEnabled() && admin.getPluginMeta().getVersion().equals("2.3.0"),"AdminLite 2.3.0 enabled");
        var field=admin.getClass().getDeclaredField("whitelist"); field.setAccessible(true); executor=(TabExecutor)field.get(admin);
        db=DriverManager.getConnection("jdbc:sqlite:"+admin.getDataFolder().toPath().resolve("whitelist.db").toAbsolutePath());
        check(!Bukkit.hasWhitelist(),"plugin does not enable the server whitelist automatically");
        if(Files.exists(Path.of("whitelist-first.ok"))) { restart(); return; }
        Bukkit.getOfflinePlayer(second).setWhitelisted(false);
        cmd("whitelist add SeqSecond"); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"bare add without CHZZK cannot add player");
        cmd("minecraft:whitelist add SeqSecond"); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"minecraft namespace cannot bypass required nickname");
        cmd("execute run minecraft:whitelist add SeqSecond"); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"nested execute cannot bypass required nickname");
        cmd("adminlite:whitelist add SeqSecond"); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"plugin namespace also requires nickname");
        check(count("SELECT COUNT(*) FROM whitelist_events")==0,"rejected commands leave no registration records");
        var ordinary=new Sender(false,false); executor.onCommand(ordinary.sender,null,"whitelist",new String[]{"add","SeqSecond","치지직"});
        check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"non-admin cannot register player");
        check(executor.onTabComplete(ordinary.sender,null,"whitelist",new String[]{""}).isEmpty(),"non-admin has no target completion");
        Instant before=Instant.now(); cmd("whitelist add "+account+" "+nickname); Instant after=Instant.now();
        check(Bukkit.getOfflinePlayer(target).isWhitelisted(),"first-time offline account added to actual native whitelist");
        try(var statement=db.createStatement();var row=statement.executeQuery("SELECT * FROM whitelist_events ORDER BY id DESC LIMIT 1")) {
            check(row.next() && row.getString("uuid").equals(target.toString()) && row.getString("account").equals(account),"DB keeps exact UUID and real account");
            check(row.getString("chzzk").equals(nickname),"DB keeps full CHZZK nickname including spaces and digits");
            check(row.getString("zone").equals("Asia/Seoul"),"registration timezone is Asia/Seoul");
            var at=Instant.ofEpochMilli(row.getLong("at_ms")); check(!at.isBefore(before) && !at.isAfter(after),"registration timestamp matches actual command execution");
            check(row.getString("actor_name").equals("CONSOLE") && row.getString("actor_uuid")==null,"console administrator recorded accurately");
            check(row.getInt("applied")==1,"successful native mutation marked applied");
        }
        check(Files.readString(Path.of("whitelist.json")).contains(target.toString()) && Files.readString(Path.of("whitelist.json")).contains(account),"native whitelist JSON persists UUID and account");
        var staff=new Sender(true,true);
        query(staff,"info",account);
        check(staff.messages.stream().anyMatch(text->text.contains(nickname)),"info returns CHZZK nickname");
        check(staff.messages.stream().anyMatch(text->text.contains("+09:00") && text.contains("Asia/Seoul")),"info shows time with UTC offset and timezone");
        query(staff,"search","테스트","이름","007"); check(staff.messages.stream().anyMatch(text->text.contains(account)),"search supports exact CHZZK nickname containing spaces");
        query(staff,"add","SeqSecond","   "); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"blank CHZZK nickname also rejected");
        int rows=count("SELECT COUNT(*) FROM whitelist_events");
        try(var statement=db.createStatement()) { statement.execute("CREATE TRIGGER fixture_fail BEFORE INSERT ON whitelist_events BEGIN SELECT RAISE(ABORT,'fixture disk write failure'); END"); }
        try {
            cmd("whitelist add SeqSecond 저장실패");
            check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"forced DB failure never adds native whitelist entry");
            check(count("SELECT COUNT(*) FROM whitelist_events")==rows,"forced DB failure keeps previous records");
        } finally { try(var statement=db.createStatement()) { statement.execute("DROP TRIGGER fixture_fail"); } }
        query(staff,"add",account,"변경닉");
        check(count("SELECT COUNT(*) FROM whitelist_events WHERE chzzk='변경닉' AND actor_uuid='"+staff.uuid+"'")==1,"player administrator UUID and registration update stored");
        query(staff,"search","테스트","이름","007"); check(staff.messages.stream().anyMatch(text->text.contains(nickname)),"old nickname remains searchable after change");
        cmd("whitelist remove "+account); check(!Bukkit.getOfflinePlayer(target).isWhitelisted(),"remove updates native whitelist");
        query(staff,"history",account); check(staff.messages.stream().anyMatch(text->text.contains("해제")) && staff.messages.stream().anyMatch(text->text.contains(nickname)),"history retains removed registration and earlier nickname");
        cmd("whitelist add "+account+" 재등록닉"); check(Bukkit.getOfflinePlayer(target).isWhitelisted(),"re-registration succeeds with mandatory nickname");
        cmd("whitelist reload"); check(Bukkit.getOfflinePlayer(target).isWhitelisted(),"native reload retains successful registration");
        query(staff,"list"); check(staff.messages.stream().anyMatch(text->text.contains("재등록닉")),"native list shows recorded CHZZK nickname");
        query(staff,"records"); check(staff.messages.stream().anyMatch(text->text.contains(account)),"registered records can be listed");
        cmd("whitelist on"); check(Bukkit.hasWhitelist(),"native whitelist on command retained");
        cmd("whitelist off"); check(!Bukkit.hasWhitelist(),"native whitelist off command retained");
        // Simulate a committed registration interrupted before its native mutation.
        try(var statement=db.prepareStatement("INSERT INTO whitelist_events(uuid,account,chzzk,added,at_ms,zone,actor_name) VALUES(?,?,?,1,?,'Asia/Seoul','Fixture')")) {
            statement.setString(1,id("RecoverFixture").toString()); statement.setString(2,"RecoverFixture"); statement.setString(3,"복구닉"); statement.setLong(4,Instant.now().toEpochMilli()); statement.executeUpdate();
        }
        success("whitelist-first.ok");
    }
    void restart() throws Exception {
        check(Bukkit.getOfflinePlayer(target).isWhitelisted(),"native registration survives full restart");
        check(Bukkit.getOfflinePlayer(id("RecoverFixture")).isWhitelisted(),"pending committed registration safely replayed on startup");
        check(count("SELECT COUNT(*) FROM whitelist_events WHERE applied=0")==0,"restart marks recovered record applied");
        check(count("SELECT COUNT(*) FROM whitelist_events WHERE account='NewWhitelist'")==4,"restart preserves complete registration history without duplication");
        var staff=new Sender(true,true); query(staff,"info",target.toString());
        check(staff.messages.stream().anyMatch(text->text.contains("재등록닉")),"info retrieves persisted record by UUID after restart");
        query(staff,"search","테스트","이름","007"); check(staff.messages.stream().anyMatch(text->text.contains(nickname)),"previous CHZZK nickname searchable after restart");
        query(staff,"history",account); check(staff.messages.stream().anyMatch(text->text.contains("해제")),"restart retrieves removal timestamp and history");
        cmd("minecraft:whitelist add SeqSecond"); check(!Bukkit.getOfflinePlayer(second).isWhitelisted(),"namespace omission guard survives restart");
        check(!Bukkit.hasWhitelist(),"restart adds no admission gate or automatic whitelist activation");
        success("whitelist-restart.ok");
    }
    void cmd(String line) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(),line); }
    void query(Sender sender,String... args) { sender.messages.clear(); executor.onCommand(sender.sender,null,"whitelist",args); }
    int count(String sql) throws Exception { try(var statement=db.createStatement();var result=statement.executeQuery(sql)) { if(!result.next()) throw new AssertionError(sql); return result.getInt(1); } }
    void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); checks.add("PASS "+label); getLogger().info(checks.getLast()); }
    void success(String name) throws Exception { Files.writeString(Path.of(name),String.join("\n",checks)+"\n"+checks.size()+" checks passed\n"); getLogger().info("WHITELIST_SUCCESS "+name+" "+checks.size()); Bukkit.shutdown(); }
    static UUID id(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)); }
    final class Sender {
        final UUID uuid=UUID.randomUUID(); final List<String> messages=new ArrayList<>(); final CommandSender sender;
        Sender(boolean op,boolean player) {
            sender=(CommandSender)Proxy.newProxyInstance(getClassLoader(),new Class[]{player?Player.class:CommandSender.class},(proxy,method,args)-> switch(method.getName()) {
                case "isOp", "hasPermission", "isPermissionSet" -> op; case "getName" -> "FixtureAdmin"; case "getUniqueId" -> uuid;
                case "getServer" -> Bukkit.getServer(); case "isOnline", "canSee" -> true;
                case "sendMessage" -> { for(var value:args) if(value instanceof Component component) messages.add(PlainTextComponentSerializer.plainText().serialize(component)); yield null; }
                case "equals" -> proxy==args[0]; case "hashCode" -> uuid.hashCode(); case "toString" -> "FixtureAdmin";
                default -> method.getReturnType()==boolean.class?false:null;
            });
        }
    }
}
