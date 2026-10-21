# AdminLite 2.2.0

Paper 26.3 build 206 / Java 25용 1인 운영 관리 플러그인입니다. AdminLite와 InvSee의 관리 기능을 하나의 JAR로 통합했습니다.

## 2.2.0 관리자 표시 ON/OFF

`/admin` → 하단 **내 관리자 표시 설정**, 또는 `/admin visibility`에서 본인의 표시를 변경합니다.
기본값은 탭·머리 위 이름 모두 표시(숨김 OFF)입니다. ON은 숨김, OFF는 표시이며 다른 관리자와 본인에게도 적용합니다.

| 명령어 | 기능 |
| --- | --- |
| `/admin hidetab on` / `off` | 탭 목록에서 내 항목 숨김/복원 |
| `/admin hidename on` / `off` | 내 머리 위 닉네임 숨김/복원 |
| `/admin hideidentity on` / `off` | 두 표시를 함께 숨김/복원 |
| `/admin visibility` | 현재 설정과 전환 버튼이 있는 메뉴 |

인수를 생략하거나 `toggle`을 입력하면 현재 상태를 전환합니다. 두 표시 중 하나만 켜져 있을 때 `hideidentity toggle`은 둘 다 ON으로 맞춥니다.
캐릭터는 그대로 보이며, 캐릭터까지 숨기려면 기존 `/admin vanish`를 함께 사용합니다. 은신의 OP 보기 권한과 상관없이 이름 숨김은 모든 접속자에게 적용됩니다.

- 이름 숨김에는 **PacketEvents 2.14.0**이 필요합니다. 현재 서버에 설치된 PacketEvents와 BerryNames 1.3.2를 그대로 사용합니다.
- BerryNames의 바뀐 닉네임을 따라가며, OFF 시 최신 닉네임과 기존 팀 표시를 복원합니다. 다른 플러그인에서 숨긴 탭 항목은 임의로 표시하지 않습니다.
- 실제 서버 스코어보드 팀은 변경하지 않습니다. 전송하는 표시용 팀만 조정합니다.
- 개인별 현재 설정을 `plugins/AdminLite/visibility.yml`에 UUID로 저장해 재접속과 서버 재시작 후 유지합니다. 변경 이력은 저장하지 않습니다.
- `adminlite.visibility` 권한이 필요하며 기본값은 OP입니다. `op-only: true`일 때는 OP만 사용할 수 있습니다.
- 전체 기능 스위치는 `features.visibility: true`입니다. false로 바꾸고 `/admin reload`하면 표시가 복원되며 개인별 설정은 보존됩니다. 다시 true로 켜면 설정이 재적용됩니다.
- OP 또는 관련 권한을 잃으면 최대 1초 내에 숨김 적용이 해제됩니다. PacketEvents가 없으면 기존 관리 기능은 실행되며 이름 숨김 ON에 필요한 플러그인을 안내합니다.

이 기능은 게임 내 탭 목록과 머리 위 이름 표시를 제어합니다. 채팅·명령어 검색·서버 목록·웹 지도에는 각 기능의 기존 표시 방식이 적용됩니다.


## BerryNames 연동

BerryNames 1.3.0 이상과 AdminLite 2.2.0을 함께 설치하면 이름 서비스를 자동 연결합니다. 이번 버전은 설치된 BerryNames 1.3.2와 검증했습니다. AdminLite 단독 설치도 지원합니다. 구버전 BerryNames에서는 실제 계정명으로 작동하며 콘솔에 업데이트 안내를 남깁니다.

- `/admin`·`/adm`의 대상 지정 하위 명령과 `/admin <이름>`, `/admin menu <이름>` 지원.
- `/invsee`, `/endersee`, `/ecsee` 및 `adminlite:` 명령 접두사 지원.
- 지정 이름, 기본 순번 이름, `#순번`을 UUID로 조회하며, 다른 UUID가 같은 마지막 계정명을 가져도 베리 이름 대상은 UUID로 구분.
- 관리창에 베리 이름과 실제 계정 표시. 이름·기본 이름·실제 계정·UUID·순번으로 검색.
- 명령별 권한과 온라인 대상 필요 여부를 지키는 자동완성. 숨겨진 온라인 플레이어는 제외.
- 이름 변경 후 다음 조회부터 표시·검색·자동완성 반영. 기존에 열려 있던 창은 새로고침하거나 다시 열면 반영.
- 최근 채팅은 UUID를 유지하며 현재 베리 이름 표시. `/admin deletechat 베리삼`은 해당 UUID의 채팅을 삭제.
- `/admin deletechat #3`은 채팅 메시지 ID 3이며 플레이어 순번으로 바꾸지 않음.
- 명령 사유·공지 내용은 그대로 전달. 기존 권한·영구 차단 확인창·오프라인 편집 보호 유지.

플레이어 지정 이름이 하위 명령어와 같은 경우 `/admin menu <이름>`처럼 대상을 명시하세요. `commands.extra-targets`에 AdminLite 명령을 추가할 필요가 없습니다. BerryNames의 일반 명령 변환 설정을 꺼도 AdminLite의 직접 이름 조회 연동은 유지됩니다.
## 기능

- `/admin` 관리 GUI: 온라인/전체 플레이어 전환, 닉네임 검색, 플레이어별 작업, 최근 채팅과 전체 공지.
- 플레이어 조회: UUID, 접속 상태, 현재 좌표, 핑, 게임 모드, 현재 뮤트·차단·이동 동결 상태.
- 경고 메시지, 기간제/영구 뮤트, 킥, 기간제/영구 프로필 차단과 해제.
- 이동 동결, 텔레포트, 소환, 관전, 은신, 관전 종료 후 위치·게임 모드·비행 상태 복원.
- 온라인/오프라인 인벤토리·방어구·보조 손·엔더 상자 조회와 수정.
- 클릭형 최근 채팅 조회·삭제, 채팅창 정리, 색상과 웹 링크를 지원하는 공지.
- 기본 OP 전용. 설정과 기능별 권한으로 사용 범위를 제어할 수 있습니다.

과거 경고/제재/인벤토리 수정 이력 데이터베이스는 만들지 않습니다. 자동차단은 WorldShield의 기존 기능을 사용합니다.

## 설치

1. 서버를 종료합니다.
2. 기존 `admin-lite-2.1.0.jar` 등 이전 AdminLite JAR와 별도 InvSee JAR를 `plugins/` 밖에 보관합니다. 다른 이름의 이전 버전 JAR가 있다면 함께 옮깁니다.
3. 새 `admin-lite-2.2.0.jar` 하나를 `plugins/`에 넣습니다.
4. 기존 `plugins/AdminLite/` 폴더는 유지합니다. 기존 설정에 없는 신규 항목은 내장 기본값으로 작동합니다.
5. 설정 항목 전체가 필요하면 배포 폴더의 맞춤 `config.yml`을 `plugins/AdminLite/config.yml`에 복사합니다. 이 파일은 작업 시 확인한 기존 AdminLite 문구와 값을 유지하며 새 설정을 추가한 것입니다.
6. 서버를 시작하고 OP 계정으로 `/admin`을 입력합니다.

기존 InvSee가 함께 설치돼 있으면 데이터 편집 중복을 막기 위해 AdminLite 2가 시작을 중단하고 콘솔에 안내합니다.
기존 AdminLite 1의 메모리에만 있던 뮤트/관전 상태는 이전 JAR를 종료하기 전에 해제하세요. 이전 플러그인이 디스크에 기록하지 않은 상태는 가져올 수 없습니다.
실행 중 플러그인 교체나 서버 전체 `/reload` 대신 정상 종료 후 재시작하세요.

## 명령어

`/adm`은 `/admin`의 별칭입니다. 대상은 정확한 실제 계정명 또는 저장된 플레이어의 UUID를 사용합니다. BerryNames 1.3.0과 함께 설치하면 현재 지정 이름·기본 순번 이름·#순번도 UUID로 직접 조회합니다.

```text
/admin                               관리 메뉴
/admin <플레이어>                    해당 플레이어 관리 메뉴
/admin menu <플레이어>               해당 플레이어 관리 메뉴
/admin players                       플레이어 목록
/admin info <플레이어>               현재 플레이어 정보

/invsee <플레이어> [view|edit]        인벤토리 조회/수정
/endersee <플레이어> [view|edit]      엔더 상자 조회/수정
/ecsee <플레이어> [view|edit]         endersee 별칭
/admin invsee <플레이어> [view|edit] 위와 동일
/admin endersee <플레이어> [view|edit]

/admin warn <플레이어> [사유]
/admin mute <플레이어> [기간|perm] [사유]
/admin unmute <플레이어>
/admin kick <플레이어> [사유]
/admin ban <플레이어> [기간|perm] [사유]
/admin unban <플레이어>

/admin freeze <플레이어>             이동 동결 켜기/끄기
/admin tp <플레이어>                 대상에게 이동
/admin tphere <플레이어>             대상을 내 위치로 이동
/admin spectate <플레이어>           관전 시작/대상 변경
/admin spectate                      관전 종료 및 원상 복귀
/admin spec [플레이어]               spectate 별칭
/admin vanish                        관리자 은신 켜기/끄기

/admin broadcast <내용>              전체 공지 (별칭 bc)
/admin chatlog                       최근 채팅 표시
/admin deletechat #<ID>              메시지 하나 삭제
/admin deletechat <닉네임|UUID>      해당 플레이어의 최근 채팅 삭제
/admin delchat <대상>                deletechat 별칭
/admin clearchat                     채팅창 정리
/admin reload                        이 플러그인의 설정만 다시 읽기
/admin help                          명령어 도움말
```

기간 예: `30m`, `2h`, `1d`, `1h30m`, `7d`, `perm`. 단위는 초 s, 분 m, 시간 h, 일 d, 주 w입니다. 기간을 생략하면 영구이며, 0·음수·단위 없는 숫자는 거부합니다. 최대 기간은 3650일입니다.
GUI에서 사유 입력은 채팅으로 받으며 다른 플레이어에게 전송되지 않습니다. `-`는 기본 사유, `취소` 또는 `cancel`은 취소입니다. 입력 제한 시간은 60초입니다.
플레이어가 영구 차단을 실행하면 확인 화면이 열립니다. 콘솔에서는 입력 즉시 실행합니다. GUI 기능을 비활성화한 경우에는 명령어도 즉시 실행합니다.

## 인벤토리 편집

- `view`는 조회 전용입니다. 모드를 생략하면 수정 권한이 있는 OP는 편집 모드로 엽니다.
- 위쪽 슬롯과 내 인벤토리 사이에 커서를 이용해 아이템을 옮깁니다. 위쪽 슬롯은 좌클릭으로 집기·넣기·교환, 우클릭으로 절반 집기·한 개 넣기를 지원합니다.
- Shift 클릭, 숫자키 교환, 드래그, 더블 클릭 수집은 차단합니다. 장식 슬롯은 가져갈 수 없습니다.
- 기본 인벤토리의 슬롯 0~35는 보관칸/핫바, 36~39는 신발·바지·흉갑·투구, 40은 보조 손입니다. 엔더 상자는 0~26입니다.
- 온라인 편집은 클릭마다 대상의 실제 현재 슬롯을 읽고 즉시 반영합니다. 창을 닫을 때 전체 스냅샷을 덮어쓰지 않습니다.
- 오프라인 편집은 변경마다 임시 파일 검증과 파일 교체로 즉시 저장합니다. 다른 곳에서 플레이어 데이터가 바뀌면 편집을 중단합니다.
- 오프라인 편집 중 대상의 접속은 잠시 거부하며, 편집 창을 닫으면 다시 접속할 수 있습니다. 대상의 접속/종료 시 오래된 조회 창을 닫습니다.
- 같은 대상·컨테이너의 동시 편집을 막습니다. 자신의 인벤토리는 편집 GUI로 열 수 없고 직접 수정해야 합니다.
- 현재 서버 버전으로 저장된 플레이어 데이터만 오프라인으로 엽니다. 이전 Minecraft 버전의 데이터라면 대상이 한 번 접속해 서버가 변환한 뒤 사용하세요.

## 저장과 권한

- `active.yml`: 현재 뮤트와 만료 시각, 현재 이동 동결 대상만 저장합니다. 해제된 대상의 제재 이력은 남기지 않습니다.
- `recovery.yml`: 현재 관전 중인 관리자의 원래 상태만 저장합니다. 복귀 후 해당 항목을 제거합니다. 종료/접속 시 복귀를 재시도합니다.
- 접속 차단은 Minecraft의 프로필 차단 목록을 사용합니다. 기존 WorldShield 등의 차단과 동일한 서버 차단 목록에 반영됩니다.
- 최근 채팅은 설정한 개수만 메모리에 보관하고 재시작하면 사라집니다. 서버 자체의 기존 콘솔 로그 정책은 그대로 적용됩니다.
- 서명 있는 채팅은 클라이언트의 메시지 삭제 기능을 사용합니다. 서명 없는 메시지는 최근 목록에서만 제거할 수 있으며, 클라이언트의 보관 시간·표시 정책에 따라 삭제 결과가 다를 수 있습니다.
- 은신은 일반 플레이어에게 적용하고, 기본적으로 OP는 숨은 관리자를 볼 수 있습니다. 은신 중 아이템 자동 줍기와 전투를 막습니다.
- 동결된 플레이어는 시점 회전은 가능하며 이동·일반 텔레포트·탈것 탑승·전투를 막습니다. 관리 플러그인의 이동 명령은 허용합니다.

`op-only: true`가 기본입니다. 개별 권한 부여를 사용하려면 false로 설정하고 `adminlite.use`와 필요한 권한을 부여하세요.

```text
adminlite.gui / adminlite.info
adminlite.invsee                      조회 권한
adminlite.invsee.edit                 수정 권한 (조회 권한도 필요)
adminlite.warn / adminlite.freeze / adminlite.mute
adminlite.kick / adminlite.ban
adminlite.spectate / adminlite.teleport / adminlite.vanish
adminlite.visibility                 탭·머리 위 이름 숨김
adminlite.see-hidden                  은신한 관리자 보기
adminlite.broadcast / adminlite.chatlog
adminlite.deletechat / adminlite.clearchat / adminlite.reload
```

`features`는 기능별 명령 사용을 켜고 끕니다. 현재 적용 중인 뮤트·차단은 기능을 꺼도 임의로 해제하지 않습니다. 관전과 은신은 기능을 꺼도 종료할 수 있습니다.
기본값은 OP 전용이며, 기존 AdminLite의 개별 권한 이름도 이어 사용합니다.

## 빌드와 검증

Java 25 JDK와 Maven이 필요합니다. 저장소 루트에서 실행합니다.

```powershell
./build.ps1
```

BerryNames API가 로컬 Maven 저장소에 없는 다른 PC에서는 호환되는 BerryNames 1.3.x 서버 JAR를 지정해 API를 먼저 등록할 수 있습니다.

```powershell
./build.ps1 -BerryNamesJar 'C:\server\plugins\BerryNames-1.3.2.jar'
```

컴파일 의존성은 BerryNames API 1.3.0이며 1.3.2에서도 같은 API를 제공합니다. 의존 플러그인의 클래스는 AdminLite JAR에 포함하지 않습니다. Paper API는 26.3.build.206-stable, PacketEvents API는 2.14.0으로 고정합니다. Adventure NBT 5.2.0은 패키지를 변경해 포함합니다.
결과 파일은 `target/admin-lite-2.2.0.jar`입니다.

자동 테스트 66개, Paper 26.3-206·BerryNames 1.3.2·PacketEvents 2.14.0에서 실제 로컬 TCP 접속자의 수신 패킷 검증 26개, 재시작 후 유지·복원 검증 3개, 의존 플러그인 없는 단독 실행 검증 4개를 통과했습니다.
실제 접속 테스트에는 일반 플레이어·OP·늦게 접속하는 OP를 사용했습니다. 베리 이름 변경, 캐릭터 생성 유지, 은신 ON/OFF, 개별 이름 숨김, 기능 재설정, 권한 회수·복구, 재접속을 확인했습니다.
화면을 띄운 Minecraft 클라이언트로 수동 시각 검수는 수행하지 않았으며, 실제 전달된 프로필·목록 표시 여부·이름표 팀 규칙을 검증했습니다. 자세한 항목은 `검증결과.txt`와 `validation/`에 있습니다.
