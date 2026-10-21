# Whitelist registration checks

Run only on a disposable Paper 26.3-206 server with Java 25.0.4. Never install the
fixture on production: it registers/removes fixture accounts, injects a SQLite
failure trigger, toggles the native whitelist, and stops the server automatically.

Verified with AdminLite 2.3.0, BerryNames 1.4.0 and PacketEvents 2.14.0. Paper supplies
SQLite JDBC 3.49.1.0. Existing AdminLite tests plus whitelist store/service tests
total 89 passing unit cases (`./build.ps1`).

The main runtime fixture passed 34 initial checks and 12 full-restart checks. It exercises
actual Bukkit/Brigadier command dispatch, `minecraft:` and plugin namespaces, nested
`execute`, real native whitelist persistence and a real SQLite file. Permission and
captured output tests call the same registered executor with synthetic staff sender
objects. It does not use real Minecraft clients; first-time profile handling in the
main fixture uses the server's offline UUID mode.

A separate online-mode fixture passed 5 checks against Paper's actual external
profile lookup. It resolves the public Minecraft account `Notch` asynchronously,
checks that its real version-4 UUID and canonical account are saved, and verifies
native whitelist persistence. The CHZZK nickname in this fixture is synthetic,
manually entered data; no CHZZK account authentication is performed.

1. Start from an isolated server with `online-mode=false`, `white-list=false` and a
   loopback Minecraft bind address. It must contain a known offline-mode profile
   `SeqSecond`, UUID `69e952ed-3ef2-3506-a34d-3cb551a330d4` (a previous join or cached
   fixture identity is sufficient). Retain the other test suite's BerryNames fixture
   if reusing that environment. Start with an empty whitelist and no whitelist.db.
2. Install the plugins above and their normal data folders. Keep `op-only=true` and
   the default `whitelist.time-zone: Asia/Seoul`.
3. Run `./build.ps1 -ServerRoot <absolute isolated server path>` in this directory.
   Copy `target/whitelist-checks.jar` into that server's plugins folder.
4. Start the server. It writes `whitelist-first.ok` and stops. Start it again;
   it writes `whitelist-restart.ok` and stops. Failures write `whitelist-failure.txt`.
5. The fixture simulates an interrupted, committed registration before stopping.
   The next startup must apply it without creating duplicate history.
6. Preserve both reports. Use a fresh server fixture and database for each repeat.

Missing or blank CHZZK nicknames cause no database/native changes. A trigger raises
an actual SQLite write failure before an insert, and native addition must not run.
The fixture verifies historical nicknames, removal records, actual execution time,
UTC offset, original time zone, console actor and staff UUID. Existing on/off/reload
behavior is tested, and the plugin never enables an additional admission policy.

Windows OSHI performance counter warnings are environment diagnostics. One SEVERE
whitelist SQL message during the first run is expected from the deliberate failure
trigger; it must leave the target outside the whitelist and the previous rows intact.

To run the optional online-mode fixture, stop the isolated server and remove
`whitelist-checks.jar`. Enable `online-mode=true`, choose a fresh DB with no
`온라인프로필검증` record, and build with `./build.ps1 -ServerRoot <path> -OnlineProfile`.
Install `target/online-profile-checks.jar` and start the server. It writes
`whitelist-online.ok` and stops. External Minecraft profile endpoints must be
reachable. Never install either fixture on the production server.
