package kr.openclaw.adminlite;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Append-only registration journal. A pending row is committed before native whitelist changes. */
final class WhitelistStore implements AutoCloseable {
    record Identity(UUID uuid, String account) {
        Identity { Objects.requireNonNull(uuid); if (account == null || !account.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("정확한 마인크래프트 실제 ID가 필요합니다."); }
    }
    record Actor(UUID uuid, String name) { Actor { if (name == null || name.isBlank()) throw new IllegalArgumentException("등록자가 없습니다."); } }
    record Entry(long id, Identity player, String chzzk, boolean added, Instant at, ZoneId zone, Actor actor, boolean applied) {}
    private final Connection connection;
    WhitelistStore(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=2500"); statement.execute("PRAGMA synchronous=FULL");
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) { if(!result.next()) throw new SQLException("DB 버전을 읽을 수 없습니다."); version=result.getInt(1); }
            if (version != 0 && version != 1) throw new SQLException("지원하지 않는 whitelist.db 버전: " + version);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS whitelist_events (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uuid TEXT NOT NULL, account TEXT NOT NULL, chzzk TEXT NOT NULL,
                  added INTEGER NOT NULL CHECK(added IN (0,1)),
                  at_ms INTEGER NOT NULL, zone TEXT NOT NULL,
                  actor_uuid TEXT, actor_name TEXT NOT NULL,
                  applied INTEGER NOT NULL DEFAULT 0 CHECK(applied IN (0,1)),
                  CHECK(added=0 OR length(trim(chzzk))>0)
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS whitelist_events_uuid ON whitelist_events(uuid,id)");
            statement.execute("CREATE INDEX IF NOT EXISTS whitelist_events_chzzk ON whitelist_events(chzzk,id)");
            statement.execute("PRAGMA user_version=1");
            try (var result = statement.executeQuery("PRAGMA quick_check")) {
                if (!result.next() || !"ok".equals(result.getString(1))) throw new SQLException("whitelist.db 무결성 검사 실패. 파일을 초기화하지 않습니다.");
            }
        } catch (Exception error) { connection.close(); throw error; }
    }
    static String nickname(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("치지직 닉네임을 반드시 입력하세요. /whitelist add <마크닉네임> <치지직닉네임>");
        String clean = value.strip();
        if(clean.length()>=2 && (clean.startsWith("\"") && clean.endsWith("\"") || clean.startsWith("'") && clean.endsWith("'")))
            clean=clean.substring(1,clean.length()-1).strip();
        if(clean.isBlank()) throw new IllegalArgumentException("치지직 닉네임을 반드시 입력하세요. /whitelist add <마크닉네임> <치지직닉네임>");
        if (clean.codePointCount(0,clean.length()) > 64 || clean.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("치지직 닉네임은 제어 문자 없이 64자 이내로 입력하세요.");
        return clean;
    }
    synchronized Entry append(Identity player, String chzzk, boolean added, Instant at, ZoneId zone, Actor actor) throws SQLException {
        String value = added ? nickname(chzzk) : chzzk == null ? "" : chzzk;
        try (var statement = connection.prepareStatement("INSERT INTO whitelist_events(uuid,account,chzzk,added,at_ms,zone,actor_uuid,actor_name) VALUES(?,?,?,?,?,?,?,?)")) {
            statement.setString(1,player.uuid().toString()); statement.setString(2,player.account()); statement.setString(3,value);
            statement.setBoolean(4,added); statement.setLong(5,at.toEpochMilli()); statement.setString(6,zone.getId());
            statement.setString(7,actor.uuid()==null?null:actor.uuid().toString()); statement.setString(8,actor.name()); statement.executeUpdate();
        }
        try (var statement = connection.createStatement(); var result=statement.executeQuery("SELECT * FROM whitelist_events WHERE id=last_insert_rowid()")) {
            if (!result.next()) throw new SQLException("저장한 등록 기록을 찾을 수 없습니다."); return read(result);
        }
    }
    synchronized void applied(long id) throws SQLException {
        try (var statement=connection.prepareStatement("UPDATE whitelist_events SET applied=1 WHERE id=?")) {
            statement.setLong(1,id); if(statement.executeUpdate()!=1) throw new SQLException("적용 기록이 없습니다: " + id);
        }
    }
    synchronized Optional<Entry> latest(UUID uuid) throws SQLException {
        var result = query("SELECT * FROM whitelist_events WHERE uuid=? ORDER BY id DESC LIMIT 1",uuid.toString());
        return result.stream().findFirst();
    }
    synchronized List<Entry> findAccount(String name) throws SQLException {
        return query("SELECT * FROM whitelist_events WHERE id IN (SELECT MAX(id) FROM whitelist_events GROUP BY uuid) AND account=? COLLATE NOCASE",name);
    }
    synchronized List<Entry> history(UUID uuid, int page) throws SQLException {
        return query("SELECT * FROM whitelist_events WHERE uuid=? ORDER BY id DESC LIMIT 11 OFFSET ?",uuid.toString(),offset(page));
    }
    synchronized List<Entry> search(String chzzk, int page) throws SQLException {
        return query("SELECT * FROM whitelist_events WHERE chzzk=? ORDER BY id DESC LIMIT 11 OFFSET ?",nickname(chzzk),offset(page));
    }
    synchronized List<Entry> current(int page) throws SQLException {
        return query("SELECT * FROM whitelist_events WHERE id IN (SELECT MAX(id) FROM whitelist_events GROUP BY uuid) AND added=1 ORDER BY id DESC LIMIT 11 OFFSET ?",offset(page));
    }
    synchronized List<Entry> pending() throws SQLException { return query("SELECT * FROM whitelist_events WHERE applied=0 ORDER BY id"); }
    private static int offset(int page) {
        if(page<1 || page>1_000_000) throw new IllegalArgumentException("페이지는 1~1000000 사이의 숫자입니다."); return (page-1)*10;
    }
    private List<Entry> query(String sql,Object... parameters) throws SQLException {
        try (var statement=connection.prepareStatement(sql)) {
            for (int index=0;index<parameters.length;index++) statement.setObject(index+1,parameters[index]);
            var entries = new ArrayList<Entry>(); try(var result=statement.executeQuery()) { while(result.next()) entries.add(read(result)); }
            return List.copyOf(entries);
        }
    }
    private static Entry read(ResultSet result) throws SQLException {
        String actor=result.getString("actor_uuid");
        return new Entry(result.getLong("id"),new Identity(UUID.fromString(result.getString("uuid")),result.getString("account")),
                result.getString("chzzk"),result.getBoolean("added"),Instant.ofEpochMilli(result.getLong("at_ms")),ZoneId.of(result.getString("zone")),
                new Actor(actor==null?null:UUID.fromString(actor),result.getString("actor_name")),result.getBoolean("applied"));
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
