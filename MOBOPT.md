# MobOpt Paper 1.21.8

이 포크는 Paper `ver/1.21.8` 최종 커밋
`29c8822d90899c89d2689338e81a98f690bcba12`(공식 Paper build 60)를 기반으로 합니다.

## 호환성 계약

- `paper-api`의 공개 클래스, 메서드, 패키지를 수정하지 않습니다.
- `Brand-Id`, `Brand-Name`, Bukkit/Paper API 좌표를 그대로 유지합니다.
- 최적화는 `paper-server`와 Minecraft 내부 feature patch에만 존재합니다.
- 일반 Paper API 플러그인의 바이너리·소스 호환성을 유지합니다.
- 패킷 write 순서, 패킷 내용과 `ChannelFutureListener` 완료 순서를 바꾸지 않습니다.
- NMS, CraftBukkit 내부 클래스나 리플렉션에 직접 의존하는 플러그인은 Paper 자체와 마찬가지로 버전 간 호환성을 보장하지 않습니다.

## 설정

첫 실행 후 `config/paper-global.yml`에 다음 설정이 생성됩니다.

```yaml
optimizations:
  explosion-broadcast-optimization:
    enabled: true
  network-frame-prefix-in-place:
    enabled: true
  network-zero-copy-decoding:
    enabled: true
  pathetic-mob-pathfinding:
    enabled: true
  play-protocol-flush-consolidation:
    enabled: true
  play-protocol-lazy-write-scheduling:
    enabled: true
```

기능별 설정은 `enabled` 하나뿐이며 변경 뒤에는 서버를 완전히 재시작해야 합니다. 기능을 끄면 해당 최적화의 기존 Paper 경로가 사용됩니다.

## 몹 경로탐색

정확히 바닐라 `WalkNodeEvaluator`를 쓰는 단일 목표, 정확도 `0` 또는 `1`, 동일 높이의 수평 경로 요청만 최적화합니다.

1. 자주 쓰이는 완전 블록 바닥과 headroom을 빠르게 판정합니다.
2. 같은 Y 높이의 지원되는 zero-malus 직선 경로를 검사합니다.
3. 최대 8블록의 직교 우회 경로를 검사합니다.
4. 이 bounded fast path로 처리할 수 없는 복잡 경로는 전체 이중 탐색 없이 즉시 기존 Paper 탐색으로 이어집니다.

정확도 `1`은 원래 목표와 수평 인접 네 좌표를 직선 후보로 사용하고, bounded detour는 그중 시작점에 가장 가까운 후보만 검사합니다. `Path#getTarget()`과 도달 반경은 바닐라 의미를 보존합니다. 이미 목표 반경 안이면 기존 Paper 경로를 그대로 사용합니다. 이 때문에 Paper API `Mob#getPathfinder().moveTo(...)`뿐 아니라 일반 좀비 AI가 흔히 생성하는 accuracy-1 요청도 평지에서 최적화될 수 있습니다.

한 번의 탐색 안에서 같은 좌표의 evaluator/floor 계산은 캐시하여 중복 호출하지 않습니다. 그러나 서로 다른 외부 `moveTo` 호출은 같은 틱이라도 월드·이벤트·malus가 달라질 수 있으므로 결과를 임의 재사용하거나 요청을 삭제하지 않습니다.

다음 요청은 기존 Paper 경로로 이어집니다.

- 계단, 점프, 낙하와 높이 변화
- 비행·수륙 evaluator 또는 다중 목표
- 정확도 `2` 이상
- 지원하지 않는 문·유체·전이 규칙
- 평가 예산 소진 또는 bounded direct/detour 탐색 실패

이는 원본 Pathetic Mobs의 실패/미지원 시 바닐라 폴백 계약을 유지한 것입니다. 별도의 자동 폴백 정책이나 장애 시 전체 탐색 재시도 정책을 추가하지 않았습니다.

## 네트워크

### PLAY flush consolidation

`PLAY` 상태에서 inbound read 밖에서 연속 호출된 flush를 현재 Netty event-loop 회전 끝의 실제 flush 하나로 합칩니다.

- `HANDSHAKING`, `STATUS`, `LOGIN`, `CONFIGURATION`은 즉시 flush를 유지합니다.
- 로그인 압축 경계와 프로토콜 전환을 건드리지 않습니다.
- write 순서와 future listener를 별도 큐로 재정렬하지 않습니다.
- 256회 명시적 flush 상한과 handler 교체/종료 시 pending flush를 유지합니다.
- `-DPaper.disableFlushConsolidate=true`는 기존 Paper 동작대로 handler 설치를 막습니다.

### PLAY lazy write scheduling

Paper가 메인 서버 tick의 flush를 일시 중단한 구간에서만, 각 패킷 write가 selector를 반복해서 깨우지 않도록 Netty의 FIFO `lazyExecute` 경로를 사용합니다. 정상적인 tick 종료 flush가 selector를 깨웁니다.

- 패킷 write 자체는 하나도 삭제하지 않습니다.
- 즉시 flush, 비동기 전송, pending/unready/extra packet, 비-PLAY 프로토콜은 일반 `execute`를 사용합니다.
- queued action의 per-action `AtomicBoolean`과 capturing lambda 할당도 제거했습니다.

따라서 “중복호출 제외”는 의미가 같은 selector wakeup과 flush 작업을 합치는 것이며, 중복처럼 보이는 패킷을 임의로 버리는 기능이 아닙니다.

### Zero-copy decoding

완전한 inbound frame과 압축되지 않은 compression passthrough payload는 `readRetainedSlice`로 넘겨 byte copy를 줄입니다. 토글 OFF에서는 기존 `readBytes` 복사 경로를 사용하며, Netty decoder의 소유권과 release 규칙은 유지합니다.

### In-place frame prefix

Packet/Compression encoder가 최대 3바이트 VarInt headroom을 예약합니다. 버퍼가 단독 소유 root buffer이고 writable·contiguous일 때만 같은 버퍼 앞에 길이를 씁니다.

shared, sliced, composite, read-only 또는 headroom 없는 버퍼는 기존 allocate-and-copy 경로를 사용합니다. 이 경로는 게임 로직의 자동 바닐라 폴백이 아니라 메모리 안전과 서드파티 Netty 호환을 위한 필수 경로입니다.

## 폭발 브로드캐스트

폭발마다 월드의 모든 플레이어를 훑지 않고 Moonrise `GENERAL_SMALL` nearby-player 인덱스로 후보를 먼저 제한합니다. 이후 기존 Paper와 동일한 3차원 `< 4096.0` 거리 검사와 1.21.8 `ClientboundExplodePacket` 생성자를 사용합니다.

- ON: 인덱스 후보만 검사합니다.
- OFF: 기존 `this.players` 전체 목록을 검사합니다.
- 인덱스 결과가 없으면 빈 후보이며 전체 목록으로 자동 재시도하지 않습니다.
- 블록 갱신을 chunk resend로 바꾸거나 패킷 내용을 합치지 않습니다.

## 선택적 fast-path 계측

계측은 기본 OFF이며 켜지 않으면 상태·shutdown hook도 만들지 않습니다.

```text
-Dpaper.mobopt.verifyFastPaths=true
-Dpaper.mobopt.verifyFastPaths.runId=my-run
-Dpaper.mobopt.verifyFastPaths.outputFile=C:\path\mobopt-metrics.txt
```

서버가 정상 종료할 때 다음 항목을 stdout과 선택한 파일에 한 줄로 기록합니다.

- retained/copied frame 및 compression passthrough 횟수·bytes
- in-place/copy frame prefix 횟수·bytes
- lazy/normal write dispatch 횟수
- indexed/full-scan explosion 조회, 전체 플레이어·후보·거리 통과 수
- pathfinding attempt, direct/detour/fallback, 예산 소진과 좌표 평가 수 (`pathfinding_astar` 호환 필드는 현재 0)

속성 읽기, 카운터, 경로 변환이나 파일 쓰기가 실패해도 서버·패킷 경로에는 예외를 전파하지 않습니다.

## 빌드

요구 사항:

- PowerShell 7 (`pwsh`)
- JDK 21
- 인터넷 연결

```powershell
.\scripts\build-server.ps1 -Clean
```

스크립트는 다음을 검증합니다.

- Paper build-60 기준 커밋의 ancestor 관계
- 빌드 전후 branch, HEAD, worktree 불변
- patch 완전 재적용 후 전체 Gradle 테스트
- 정확한 1.21.8 Mojmap Paperclip 산출물
- Paperclip manifest, `versions.list`, Pathetic 5.4.6과 Paper API 1.21.8 내장 항목
- JAR/ZIP SHA-256 sidecar

릴리스 빌드는 clean worktree를 요구합니다. 미커밋 변경을 시험할 때만 `-AllowDirty`를 사용하십시오.

## 실행

EULA를 확인한 뒤 최초 한 번만 `-AcceptEula`를 전달합니다.

```powershell
.\scripts\start-server.ps1 -MemoryGb 6 -AcceptEula
```

기본 실행은 JAR 옆 SHA-256 sidecar를 필수로 검증합니다. 임의 개발 JAR만 명시적으로 `-SkipHashCheck`를 사용할 수 있습니다.

## 릴리스 검증 기준

- `applyPatches`를 깨끗한 적용 소스에서 재실행
- 전체 Gradle 테스트와 신규 MobOpt codec/path 테스트 통과
- 최적화 6개 ON/OFF 부팅
- Paper API 좌표와 API JAR 해시 비교
- 실제 protocol 772 STATUS/LOGIN/CONFIGURATION/PLAY 트래픽
- 압축·비압축 frame, ping/pong과 패킷 순서 검증
- 실제 폭발 수신자와 64블록 경계 비교
- AI-enabled 좀비 1,000개 이상에서 공식 Paper build 60과 동일 workload A/B 비교
- metrics에서 실제 fast-path 카운터가 0보다 큰지 확인

성능 수치는 같은 Java, heap, world, plugin, config와 run 순서를 사용한 반복 A/B 결과로만 판단합니다. 단순 부팅이나 status ping을 부하 테스트 결과로 표현하지 않습니다.

## 라이선스

- Paper 및 이 포크의 서버 패치: 저장소의 GPL 조건
- Pathetic Mobs에서 참조·각색한 upstream 부분: CC0-1.0
- MobOpt 신규 코드와 서버 패치: 저장소에 적용되는 GPL 조건
- Pathetic engine/API 5.4.6: MIT
- PulseNet 코드는 복사하지 않았습니다. 네트워크·폭발 패치는 Paper와 Netty 동작을 기준으로 독립 구현했습니다.

자세한 내용은 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하십시오.
