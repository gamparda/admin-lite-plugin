package kr.openclaw.adminlite;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import static kr.openclaw.adminlite.WhitelistStore.*;

final class WhitelistCommands implements TabExecutor, BasicCommand, AutoCloseable {
    private static final List<String> ACTIONS=List.of("add","remove","info","search","history","records","list","on","off","reload");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX '['VV']'");
    private final AdminLitePlugin plugin;
    private final Map<String,Command> previous=new HashMap<>();
    private final PluginCommand registered;
    private final Set<String> resolving=new HashSet<>();
    private WhitelistStore store;
    private WhitelistService service;
    private ZoneId zone;
    private volatile boolean closed;
    WhitelistCommands(AdminLitePlugin plugin) {
        this.plugin=plugin; configure();
        registered=Objects.requireNonNull(plugin.getCommand("whitelist"));
        registered.setExecutor(this); registered.setTabCompleter(this);
        var commands=Bukkit.getCommandMap().getKnownCommands();
        for(String label:List.of("whitelist","minecraft:whitelist")) previous.put(label,commands.put(label,registered));
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event ->
                event.registrar().register("whitelist","Register whitelist entries with a required CHZZK nickname",List.of("minecraft:whitelist"),this));
        try {
            store=new WhitelistStore(plugin.getDataFolder().toPath().resolve("whitelist.db"));
            service=new WhitelistService(store,(identity,added) -> {
                OfflinePlayer player=offline(identity); player.setWhitelisted(added);
                if(player.isWhitelisted()!=added) throw new IllegalStateException("서버 화이트리스트 반영에 실패했습니다.");
            },Clock.systemUTC());
            service.recover();
        } catch(Exception error) {
            plugin.getLogger().log(Level.SEVERE,"화이트리스트 DB 연결/미완료 작업 반영 실패. 파일을 초기화하지 않습니다. 등록 명령은 DB 저장 없이 진행하지 않습니다.",error);
        }
    }
    void configure() { zone=ZoneId.of(plugin.getConfig().getString("whitelist.time-zone","Asia/Seoul")); }
    @Override public boolean canUse(CommandSender sender) { return !closed && plugin.isEnabled() && plugin.allowed(sender,"whitelist"); }
    @Override public String permission() { return "adminlite.whitelist"; }
    @Override public void execute(CommandSourceStack source,String[] args) { run(source.getSender(),args); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) { run(sender,args); return true; }
    private void run(CommandSender sender,String[] args) {
        try {
            if(!canUse(sender)) { plugin.tell(sender,"no-permission"); return; }
            if(args.length==0) { help(sender); return; }
            String action=args[0].toLowerCase(Locale.ROOT);
            switch(action) {
                case "add" -> {
                    // Validate required data before resolving an identity or touching either whitelist.
                    if(args.length<3) throw new IllegalArgumentException("치지직 닉네임을 반드시 입력하세요. /whitelist add <마크닉네임> <치지직닉네임>");
                    String nickname=WhitelistStore.nickname(AdminLitePlugin.join(args,2)); requireStore();
                    Identity identity=known(args[1]);
                    if(identity!=null) add(sender,identity,nickname);
                    else resolveNew(sender,args[1],nickname);
                }
                case "remove" -> {
                    exact(args,2,"remove <마크닉네임>"); requireStore(); Identity identity=required(args[1]);
                    var entry=service.remove(identity,actor(sender),zone); tell(sender,"화이트리스트 해제: "+identity.account()+" · 등록 기록은 보관합니다."); row(sender,entry);
                }
                case "info" -> {
                    exact(args,2,"info <마크닉네임>"); requireStore(); Identity identity=required(args[1]);
                    tell(sender,"서버 화이트리스트: "+(offline(identity).isWhitelisted()?"등록":"미등록")+" · "+identity.account()+" · "+identity.uuid());
                    var entry=store.latest(identity.uuid()); if(entry.isEmpty()) tell(sender,"저장된 수동 등록 기록이 없습니다."); else row(sender,entry.get());
                }
                case "history" -> {
                    if(args.length<2 || args.length>3) throw new IllegalArgumentException("사용법: /whitelist history <마크닉네임> [페이지]");
                    requireStore(); int page=page(args,2); page(sender,store.history(required(args[1]).uuid(),page),page);
                }
                case "search" -> {
                    if(args.length<2) throw new IllegalArgumentException("사용법: /whitelist search <치지직닉네임> [--page=2]");
                    requireStore(); int page=1, end=args.length;
                    if(args[args.length-1].startsWith("--page=")) { page=page(new String[]{args[args.length-1].substring(7)},0); end--; }
                    page(sender,store.search(String.join(" ",Arrays.copyOfRange(args,1,end)),page),page);
                }
                case "records" -> {
                    if(args.length>2) throw new IllegalArgumentException("사용법: /whitelist records [페이지]");
                    requireStore(); int page=page(args,1); page(sender,store.current(page),page);
                }
                case "list" -> {
                    exact(args,1,"list");
                    var players=Bukkit.getWhitelistedPlayers(); tell(sender,"서버 화이트리스트: "+players.size()+"명");
                    for(var player:players.stream().sorted(Comparator.comparing(OfflinePlayer::getUniqueId)).toList()) {
                        var entry=store==null?Optional.<Entry>empty():store.latest(player.getUniqueId());
                        tell(sender,entry.map(e -> e.player().account()+" · 치지직: "+e.chzzk()).orElseGet(() -> plugin.accountName(player)+" · 수동 등록 기록 없음"));
                    }
                }
                case "on", "off" -> {
                    exact(args,1,action); Bukkit.setWhitelist(action.equals("on")); tell(sender,"서버 화이트리스트: "+(Bukkit.hasWhitelist()?"ON":"OFF"));
                }
                case "reload" -> {
                    exact(args,1,"reload"); Bukkit.reloadWhitelist(); if(service!=null) service.recover(); tell(sender,"서버 화이트리스트를 다시 불러왔습니다.");
                }
                default -> help(sender);
            }
        } catch(Exception error) { failure(sender,error); }
    }
    private void add(CommandSender sender,Identity identity,String nickname) throws Exception {
        plugin.checkPermission(sender,"whitelist");
        var entry=service.add(identity,nickname,actor(sender),zone);
        tell(sender,"화이트리스트 등록 완료: "+identity.account()+" · 치지직: "+entry.chzzk()); row(sender,entry);
    }
    private void resolveNew(CommandSender sender,String name,String nickname) throws Exception {
        if(!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("처음 등록하는 이용자는 마인크래프트 실제 ID를 입력하세요.");
        if(!Bukkit.getOnlineMode()) {
            // A cached profile may belong to an online-mode proxy. Never replace a known UUID.
            add(sender,new Identity(UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)),name),nickname); return;
        }
        String key=name.toLowerCase(Locale.ROOT);
        if(!resolving.add(key)) throw new IllegalArgumentException("해당 이용자의 계정 정보를 확인 중입니다. 잠시 기다려 주세요.");
        tell(sender,"마인크래프트 계정을 확인 중입니다: "+name);
        Bukkit.createPlayerProfile(name).update().orTimeout(12,TimeUnit.SECONDS).whenComplete((profile,error) -> {
            if(closed || !plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin,() -> {
                resolving.remove(key);
                try {
                    if(closed || !canUse(sender)) return;
                    if(error!=null || profile==null || profile.getUniqueId()==null || profile.getUniqueId().equals(new UUID(0,0)) || profile.getName()==null)
                        throw new IllegalArgumentException("마인크래프트 계정을 확인하지 못했습니다. 실제 ID와 네트워크 상태를 확인하세요. 화이트리스트는 변경하지 않았습니다.");
                    add(sender,new Identity(profile.getUniqueId(),profile.getName()),nickname);
                } catch(Exception failure) { failure(sender,failure); }
            });
        });
    }
    private Identity known(String input) throws Exception {
        try {
            UUID uuid=UUID.fromString(input); var entry=store.latest(uuid); if(entry.isPresent()) return entry.get().player();
            var berry=plugin.names.find(uuid); if(berry!=null) return new Identity(uuid,berry.account());
            var player=Bukkit.getOfflinePlayer(uuid); if(player.getName()!=null && !player.getName().isBlank()) return new Identity(uuid,player.getName());
            return null;
        } catch(IllegalArgumentException ignored) { }
        var matches=new LinkedHashMap<UUID,Identity>();
        for(var entry:store.findAccount(input)) matches.put(entry.player().uuid(),entry.player());
        for(var entry:plugin.names.entries()) if(entry.account().equalsIgnoreCase(input) || entry.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(input)))
            matches.put(entry.uuid(),new Identity(entry.uuid(),entry.account()));
        var player=Bukkit.getOfflinePlayerIfCached(input);
        if(player!=null && player.getName()!=null) matches.put(player.getUniqueId(),new Identity(player.getUniqueId(),player.getName()));
        for(var whitelisted:Bukkit.getWhitelistedPlayers()) if(input.equalsIgnoreCase(whitelisted.getName())) matches.put(whitelisted.getUniqueId(),new Identity(whitelisted.getUniqueId(),whitelisted.getName()));
        if(matches.size()>1) throw new IllegalArgumentException("입력한 이름이 여러 UUID 기록과 일치합니다. UUID를 지정하세요.");
        return matches.values().stream().findFirst().orElse(null);
    }
    private Identity required(String input) throws Exception {
        Identity result=known(input); if(result==null) throw new IllegalArgumentException("저장된 이용자를 찾지 못했습니다. 실제 ID 또는 UUID를 확인하세요."); return result;
    }
    private static OfflinePlayer offline(Identity identity) {
        OfflinePlayer cached=Bukkit.getOfflinePlayerIfCached(identity.account());
        if(cached!=null && cached.getUniqueId().equals(identity.uuid())) return cached;
        if(!Bukkit.getOnlineMode() && identity.uuid().equals(UUID.nameUUIDFromBytes(("OfflinePlayer:"+identity.account()).getBytes(StandardCharsets.UTF_8))))
            return Bukkit.getOfflinePlayer(identity.account());
        return Bukkit.getOfflinePlayer(identity.uuid());
    }
    private static Actor actor(CommandSender sender) { return new Actor(sender instanceof Player player?player.getUniqueId():null,sender.getName()); }
    private void requireStore() { if(store==null || service==null) throw new IllegalArgumentException("화이트리스트 DB를 사용할 수 없습니다. 서버 로그를 확인하세요. DB 저장 없이 등록하지 않습니다."); }
    private void row(CommandSender sender,Entry entry) {
        tell(sender,"#"+entry.id()+" "+(entry.added()?"등록":"해제")+" · 마크: "+entry.player().account()+" · 치지직: "+(entry.chzzk().isEmpty()?"기록 없음":entry.chzzk()));
        tell(sender,TIME.format(entry.at().atZone(entry.zone()))+" · 처리자: "+entry.actor().name()+" · "+(entry.applied()?"반영 완료":"반영 대기"));
    }
    private void page(CommandSender sender,List<Entry> entries,int page) {
        tell(sender,"등록 기록 · "+page+"페이지 (최신순)");
        if(entries.isEmpty()) tell(sender,"조회 결과가 없습니다.");
        entries.stream().limit(10).forEach(entry -> row(sender,entry));
        if(entries.size()>10) tell(sender,"다음 페이지가 있습니다. 페이지 번호를 "+(page+1)+"로 지정하세요.");
    }
    private static int page(String[] args,int index) { if(args.length<=index) return 1; try{return Integer.parseInt(args[index]);}catch(NumberFormatException error){throw new IllegalArgumentException("페이지는 숫자로 입력하세요.");} }
    private static void exact(String[] args,int size,String usage) { if(args.length!=size) throw new IllegalArgumentException("사용법: /whitelist "+usage); }
    private void help(CommandSender sender) {
        tell(sender,"/whitelist add <마크닉네임> <치지직닉네임> · 치지직 닉네임 필수");
        tell(sender,"/whitelist remove|info <마크닉네임> / history <마크닉네임> [페이지]");
        tell(sender,"/whitelist search <치지직닉네임> [--page=2] / records [페이지]");
        tell(sender,"/whitelist list|on|off|reload");
    }
    private void tell(CommandSender sender,String line) { sender.sendMessage(plugin.text(plugin.cfg("general-prefix")).append(Component.text(line))); }
    private void failure(CommandSender sender,Exception error) {
        if(error instanceof IllegalArgumentException) { tell(sender,error.getMessage()); return; }
        tell(sender,"화이트리스트 처리를 완료하지 못했습니다. 서버 로그를 확인하세요. 저장된 반영 대기 작업은 다음 등록/재시작 시 재시도합니다.");
        plugin.getLogger().log(Level.SEVERE,"화이트리스트 등록/조회 실패",error);
    }
    @Override public Collection<String> suggest(CommandSourceStack source,String[] args) { return complete(source.getSender(),args); }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args) { return complete(sender,args); }
    private List<String> complete(CommandSender sender,String[] args) {
        if(!canUse(sender)) return List.of();
        String prefix=args.length==0?"":args[args.length-1]; var result=new LinkedHashSet<String>();
        if(args.length<=1) result.addAll(ACTIONS);
        else if(args.length==2 && Set.of("add","remove","info","history").contains(args[0].toLowerCase(Locale.ROOT))) {
            for(var player:plugin.knownPlayers(sender,false)) result.addAll(plugin.names.suggestions(player.getUniqueId(),player.getName(),prefix,false));
            Bukkit.getWhitelistedPlayers().forEach(player -> { if(player.getName()!=null) result.add(player.getName()); });
        }
        return result.stream().filter(value -> value.regionMatches(true,0,prefix,0,prefix.length())).limit(80).toList();
    }
    @Override public void close() throws Exception {
        closed=true;
        var commands=Bukkit.getCommandMap().getKnownCommands();
        previous.forEach((label,original) -> { if(commands.get(label)==registered) { if(original==null) commands.remove(label); else commands.put(label,original); } });
        if(store!=null) store.close();
    }
}
