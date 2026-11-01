package kr.openclaw.adminlite.recovery;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Missing files are intentional resets. No backups, snapshots, or recovery from other files. */
public final class RecoveryYaml {
    @FunctionalInterface public interface Fixer { boolean fix(YamlConfiguration yaml)throws Exception; }
    public static final class UnsupportedFormat extends IOException {public UnsupportedFormat(String message){super(message);}}
    private static final Consumer<String> LOG=s->java.util.logging.Logger.getLogger(RecoveryYaml.class.getPackageName()).warning(s);
    private RecoveryYaml(){}
    public static YamlConfiguration data(Path file,Fixer fixer)throws IOException {return data(file,fixer,LOG);}
    public static YamlConfiguration data(Path file,Fixer fixer,Consumer<String> warning)throws IOException {
        boolean exists=Files.exists(file),changed=!exists;var yaml=new YamlConfiguration();
        if(exists) {
            String text=Files.readString(file,StandardCharsets.UTF_8);
            try{yaml.loadFromString(text);}catch(Exception e){yaml=salvage(text);changed=true;}
        }
        boolean reset=false;
        try{changed|=fixer.fix(yaml);}
        catch(UnsupportedFormat e){throw e;}
        catch(Exception e) {
            yaml=new YamlConfiguration();reset=true;changed=true;
            try{fixer.fix(yaml);}catch(Exception error){throw new IOException("기본 상태를 생성할 수 없습니다: "+file,error);}
        }
        if(changed) {
            write(file,yaml.saveToString(),warning);
            if(exists)warning.accept((reset?"파일을 기본 상태로 초기화했습니다: ":"파일의 누락/손상 항목을 기본값으로 보완했습니다: ")+file);
        }
        return yaml;
    }
    public static void config(JavaPlugin plugin){config(plugin,"config.yml","config.yml");}
    public static YamlConfiguration config(Path file,ConfigurationSection defaults,Consumer<String> warning)throws IOException {
        return data(file,yaml->mergeDefaults(yaml,defaults),warning);
    }
    public static YamlConfiguration config(JavaPlugin plugin,String filename,String resource) {
        try(var stream=plugin.getResource(resource)) {
            if(stream==null)throw new IOException("기본 리소스 없음: "+resource);
            var defaults=new YamlConfiguration();defaults.loadFromString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            return config(plugin.getDataFolder().toPath().resolve(filename),defaults,plugin.getLogger()::warning);
        }catch(Exception e){throw new IllegalStateException("설정 자동 초기화 실패: "+filename,e);}
    }
    public static void write(Path file,String text)throws IOException{write(file,text,LOG);}
    public static void write(Path file,String text,Consumer<String> warning)throws IOException{atomic(file,text.getBytes(StandardCharsets.UTF_8));}
    public static boolean mergeDefaults(YamlConfiguration yaml,ConfigurationSection defaults) {
        boolean changed=false;
        for(String key:defaults.getKeys(true)) {
            Object expected=defaults.get(key),actual=yaml.get(key);
            if(defaults.isConfigurationSection(key)) {
                if(actual!=null&&!yaml.isConfigurationSection(key)){yaml.set(key,null);changed=true;}
                continue;
            }
            boolean wrong=actual==null||expected instanceof Boolean&&!(actual instanceof Boolean)
                    ||expected instanceof Number&&!(actual instanceof Number)||expected instanceof List<?> &&!(actual instanceof List<?>)
                    ||expected instanceof String&&!(actual instanceof String)
                    ||actual instanceof Number n&&!Double.isFinite(n.doubleValue());
            if(wrong){yaml.set(key,expected);changed=true;}
        }
        return changed;
    }

    public static void atomic(Path file,byte[] bytes)throws IOException {
        Path target=file.toAbsolutePath();Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),target.getFileName()+"-",".tmp");
        try {
            Files.write(temporary,bytes);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    public static boolean section(YamlConfiguration yaml,String key) {
        if(yaml.isConfigurationSection(key))return false;yaml.set(key,null);yaml.createSection(key);return true;
    }
    public static boolean schema(YamlConfiguration yaml,int current,int... supported)throws IOException {
        Object raw=yaml.get("schema-version");
        if(raw instanceof Number value) {
            int version=value.intValue();if(value.doubleValue()!=version)throw new UnsupportedFormat("잘못된 저장 버전: "+raw);
            boolean allowed=version==current;for(int other:supported)allowed|=version==other;
            if(allowed){if(raw instanceof Integer||raw instanceof Long)return false;yaml.set("schema-version",version);return true;}
            throw new UnsupportedFormat("지원하지 않는 저장 버전: "+raw);
        }
        if(raw instanceof String text&&text.matches("[0-9]+")) {
            try {int version=Integer.parseInt(text);boolean allowed=version==current;for(int other:supported)allowed|=version==other;
                if(!allowed)throw new UnsupportedFormat("지원하지 않는 저장 버전: "+raw);yaml.set("schema-version",version);return true;
            }catch(NumberFormatException e){throw new UnsupportedFormat("지원하지 않는 저장 버전: "+raw);}
        }
        yaml.set("schema-version",current);return true;
    }
    public static boolean uuid(String value) {
        try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}
    }
    public static YamlConfiguration salvage(String text) {
        var recovered=new YamlConfiguration();var lines=text.lines().toList();
        recoverMap(lines,0,lines.size(),0,"",recovered);return recovered;
    }
    private static void recoverMap(List<String> lines,int start,int end,int indent,String path,YamlConfiguration result) {
        for(int i=start;i<end;) {
            String line=lines.get(i);int space=0;while(space<line.length()&&line.charAt(space)==' ')space++;
            if(space!=indent||line.isBlank()||line.stripLeading().startsWith("#")){i++;continue;}
            int colon=line.indexOf(':',space);if(colon<0){i++;continue;}
            String key=line.substring(space,colon).replaceAll("^['\"]|['\"]$","");
            if(!key.matches("[A-Za-z0-9_:-]+")){i++;continue;}
            int next=i+1;
            while(next<end) {String s=lines.get(next);int n=0;while(n<s.length()&&s.charAt(n)==' ')n++;if(!s.isBlank()&&!s.stripLeading().startsWith("#")&&n<=indent)break;next++;}
            var snippet=new StringBuilder();for(int row=i;row<next;row++){String s=lines.get(row);snippet.append(s.length()>=indent?s.substring(indent):s).append('\n');}
            var parsed=new YamlConfiguration();String full=path+key;
            try{parsed.loadFromString(snippet.toString());copy(parsed,key,result,full);}
            catch(Exception e) {
                int childIndent=-1;for(int row=i+1;row<next;row++){String s=lines.get(row);if(s.isBlank()||s.stripLeading().startsWith("#"))continue;int n=0;while(n<s.length()&&s.charAt(n)==' ')n++;if(n>indent){childIndent=n;break;}}
                if(childIndent>=0)recoverMap(lines,i+1,next,childIndent,full+".",result);
            }
            i=next;
        }
    }
    private static void copy(ConfigurationSection from,String key,YamlConfiguration to,String full) {
        var section=from.getConfigurationSection(key);
        if(section==null){if(from.get(key)!=null)to.set(full,from.get(key));}
        else {to.createSection(full);for(String child:section.getKeys(true))if(!section.isConfigurationSection(child))to.set(full+"."+child,section.get(child));}
    }
}
