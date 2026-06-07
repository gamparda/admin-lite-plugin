# AdminLite

Paper 서버용 단순 관리자 도구 플러그인입니다. `/adm` 하나로 인벤토리 확인, 단순 경고, 이동 동결, 채팅 차단, 관전, 이동/소환, 공지, 최근 채팅 관리 기능을 제공합니다.

## 명령어

```text
/adm invsee <플레이어>                 인벤토리 열기
/adm warn <플레이어> [사유]            단순 경고 전송. 누적 저장 없음
/adm freeze <플레이어>                 이동 동결 토글
/adm mute <플레이어> [시간]              채팅 차단. 예: 10s, 5m, 2h, 1d. 생략 시 무기한
/adm unmute <플레이어>                 채팅 차단 해제
/adm spectate <플레이어>               관전 시작
/adm spectate                          관전 종료
/adm tp <플레이어>                     대상에게 이동
/adm tphere <플레이어>                 대상을 내 위치로 소환
/adm broadcast <메시지>                전체 공지

/adm chatlog                           최근 채팅 기록 표시
/adm deletechat #<ID>                  특정 채팅 기록 삭제
/adm deletechat <플레이어>             해당 유저의 최근 채팅 기록 전체 삭제
/adm clearchat                         전체 채팅창 초기화
/adm reload                            설정 리로드
```

## 채팅 로그 예시

```text
─── 최근 채팅 기록 (클릭하여 삭제) ───
[X] [#1] gamparda: a
[X] [#2] gamparda: b
[X] [#3] gamparda: c
[X] [#4] gamparda: d
· [X] 단건 삭제 · 닉네임 클릭: 해당 유저 전체 삭제
```

- `[X]` 클릭: `/adm deletechat #<ID>` 실행
- 닉네임 클릭: `/adm deletechat <플레이어>` 실행
- 채팅 기록은 메모리에만 저장됩니다. 서버 재시작 시 초기화됩니다.
- Paper의 signed message 삭제 API를 사용해 온라인 플레이어의 클라이언트에서 해당 채팅 삭제를 요청합니다.
- 서버/클라이언트 버전, 채팅 서명 상태, 메시지 유형에 따라 삭제 표시 방식이 다를 수 있습니다. 필요하면 `/adm clearchat`도 함께 사용하세요.

## 권한

```text
adminlite.use
adminlite.invsee
adminlite.warn
adminlite.freeze
adminlite.mute
adminlite.spectate
adminlite.teleport
adminlite.broadcast
adminlite.chatlog
adminlite.deletechat
adminlite.clearchat
adminlite.reload
```

기본값은 모두 OP입니다.

## 빌드

Java 17 + Maven 필요:

```bash
mvn clean package
```

결과:

```text
target/AdminLite-1.0.0.jar
```

서버의 `plugins/` 폴더에 넣고 재시작하세요.

## 설정

`plugins/AdminLite/config.yml`에서 메시지, 최근 채팅 보관 개수, 채팅 초기화 줄 수 등을 바꿀 수 있습니다.

## 시간 형식

`/adm mute <플레이어> [시간]`에서 시간은 다음 형식을 지원합니다.

```text
10s / 10초
5m / 5분
2h / 2시간
1d / 1일
```

단위를 생략하면 분으로 처리합니다. 예: `/adm mute Steve 5` = 5분.
