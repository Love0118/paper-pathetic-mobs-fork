# MobOpt Paper 26.1.2

이 포크는 Paper `ver/26.1.2` 최신 유지보수 커밋
`e4e17fc90d31c3dca6de8bebc87c741749f8f3df`를 기반으로 합니다.

## 호환성 계약

- `paper-api`의 공개 클래스, 메서드, 패키지를 수정하지 않습니다.
- 일반 Paper API 플러그인의 바이너리 및 소스 호환성을 유지합니다.
- 최적화는 `paper-server`와 Minecraft 내부 feature patch에만 존재합니다.
- NMS, CraftBukkit 내부 클래스, 리플렉션에 직접 의존하는 플러그인은 Paper 자체와 마찬가지로 버전 간 호환성을 보장하지 않습니다.
- 패킷의 write 순서와 `ChannelFutureListener` 완료 순서를 변경하지 않습니다.

## 최적화

첫 실행 후 `config/paper-global.yml`에 다음 설정이 생성됩니다.

```yaml
optimizations:
  pathetic-mob-pathfinding:
    enabled: true
  play-protocol-flush-consolidation:
    enabled: true
  play-protocol-lazy-write-scheduling:
    enabled: true
  network-zero-copy-decoding:
    enabled: true
  network-frame-prefix-in-place:
    enabled: true
  explosion-broadcast-optimization:
    enabled: true
```

각 기능은 `enabled` 하나로만 켜거나 끕니다. 설정 변경 뒤에는 서버를 완전히 재시작하십시오.

### Pathetic mob pathfinding

정확히 바닐라 `WalkNodeEvaluator`를 쓰는 단일 목표, 정확도 `0` 또는 `1`, 동일 높이의 평지 경로 요청에 한해 다음 순서로 처리합니다.

1. 같은 Y 높이의 위험 없는 직선 fast path
2. 최대 8블록의 직교 우회 fast path
3. Pathetic 5.4.6 A* 탐색

원본 Pathetic Mobs에 있던 폴백만 유지합니다. 미지원 요청이거나 Pathetic 결과가 없으면 기존 Paper/바닐라 경로탐색이 계속 실행됩니다. 별도의 자동 폴백 정책은 추가하지 않았습니다.

현재 포팅은 원본과 동일하게 수평 4방향 탐색을 사용하되, 준비된 바닐라 evaluator의 `PathType`, 몹별 malus와 위험 블록 인접 비용을 재사용합니다. 폭 1블록 미만·높이 2블록 미만인 일반 몹이 stone/dirt 같은 완전한 공통 바닥 위를 걷는 경우에는 headroom과 인접 위험을 확인한 뒤 전체 bounding-box 분류를 생략합니다. 정확도 `1`에서는 원래 목표와 수평 인접 후보 중 유효한 점을 동일 평가 예산 안에서 탐색합니다. 화염·마그마·허니·레일·통과 가능한 문처럼 evaluator가 평지 통과 가능하다고 확정한 타입도 비용을 보존해 처리합니다. 시작점과 바닥 높이가 달라지는 계단·점프·낙하, 유체 전이, 비행·수륙 evaluator, 정확도 `2` 이상은 즉시 Paper 경로탐색으로 이어집니다. 직선 fast path도 follow range와 대각 모서리 충돌 규칙을 검사합니다. 바닐라 path debug heap 캡처가 활성화된 요청 역시 디버그 데이터 호환을 위해 Paper 경로를 사용합니다.

### PLAY protocol flush consolidation

Paper는 이미 메인 서버 tick에서 생성된 플레이어 패킷을 묶습니다. 이 포크는 그 동작을 중복 구현하지 않고, `PLAY` 프로토콜에서 inbound read 바깥에 발생한 연속 flush를 현재 Netty event-loop 회전이 끝날 때 통합합니다. Netty의 안전 상한에 따라 대기 flush가 256회에 도달하면 회전 종료 전이라도 즉시 flush합니다.

- `HANDSHAKING`, `STATUS`, `LOGIN`, `CONFIGURATION`은 Paper의 즉시 flush 동작을 유지합니다.
- 로그인 압축 경계와 프로토콜 전환을 건드리지 않습니다.
- 패킷 write 순서와 future listener를 별도 큐로 옮기거나 재생하지 않습니다.
- `PLAY -> CONFIGURATION -> PLAY` 전환 때 handler 모드를 함께 전환합니다.
- 기존 Paper JVM 옵션 `-DPaper.disableFlushConsolidate=true`가 지정되면 이 토글보다 우선하여 consolidation handler를 설치하지 않습니다.

### PLAY write scheduling

Paper가 메인 서버 tick 동안 flush를 정지한 PLAY 패킷은 기존과 똑같이 패킷마다 하나의 FIFO Netty task를 사용하되, 각 task가 selector를 따로 깨우지 않도록 `lazyExecute`로 등록합니다. tick 끝의 기존 normal flush task가 앞선 write task 뒤에 들어가 event loop를 깨웁니다.

- task 수, 패킷 순서, promise와 listener 완료 순서는 바뀌지 않습니다.
- 즉시 flush, 비동기/plugin thread, pending/unready/extra packet, `LOGIN`·`CONFIGURATION` 경로는 기존 `execute`를 사용합니다.
- 별도 single-drain queue가 후발 패킷을 흡수해 protocol transition이나 plugin raw write를 추월하는 구조는 사용하지 않습니다.

### Network zero-copy decoding

완성된 inbound frame과 압축 envelope에서 `uncompressed-length=0`인 payload는 새 `ByteBuf`로 복사하지 않고 retained slice로 다음 decoder에 넘깁니다. heap, direct, composite 입력과 VarInt 길이 경계를 테스트하며, 기능을 끄면 기존 `readBytes` 복사 경로를 그대로 사용합니다.

### In-place frame prefix

Packet/Compression encoder가 최대 3바이트의 frame-length headroom을 예약합니다. 버퍼가 단독 소유 root buffer이고 writable·contiguous일 때만 그 공간에 VarInt 길이를 기록합니다. headroom이 없거나 shared, sliced, composite, read-only 또는 제3자 버퍼이면 기존 allocate-and-copy 경로로 자동 전환합니다. 압축·암호화 handler와 하나의 원래 promise는 그대로 유지합니다.

### Explosion broadcast optimization

폭발마다 월드 전체 플레이어를 순회하는 대신 Moonrise가 이미 유지하는 `GENERAL_SMALL` 주변 플레이어 인덱스에서 후보를 얻은 뒤, 기존의 정확한 3차원 거리 조건 `< 4096.0`을 그대로 적용합니다. 청크 경계에서 실제 64블록 안쪽인 플레이어를 빠뜨리지 않도록 ±3청크 인덱스는 사용하지 않습니다.

- 폭발 블록 변경은 Paper의 기존 section update batching을 유지합니다.
- 전체 chunk 재전송, encoded buffer 공유, 자동 bundle 변환은 하지 않습니다.
- 플레이어별 packet 객체를 공유하지 않아 ProtocolLib류의 player-specific outbound 변경 의미를 유지합니다.

## 빌드

요구 사항:

- PowerShell 7 (`pwsh`)
- JDK 25 (`JAVA_HOME`, 일반적인 Zulu/Adoptium/Oracle 설치 경로 자동 검색)
- 인터넷 연결

```powershell
.\scripts\build-server.ps1
```

배포 JAR, SHA-256 파일과 라이선스가 포함된 ZIP은 `dist/`에 만들어집니다. 기존 빌드 산출물을 지우고 다시 빌드하려면:

```powershell
.\scripts\build-server.ps1 -Clean
```

릴리스 빌드는 정확한 Git revision을 기록하기 위해 깨끗한 worktree를 요구합니다. 미커밋 변경을 시험 빌드할 때만 `-AllowDirty`를 추가하십시오.

## 실행

EULA를 확인한 뒤 최초 한 번만 `-AcceptEula`를 전달합니다.

```powershell
.\scripts\start-server.ps1 -MemoryGb 6 -AcceptEula
```

이후 실행:

```powershell
.\scripts\start-server.ps1 -MemoryGb 6
```

CMD에서는 다음 래퍼를 사용할 수 있습니다.

```bat
scripts\start-server.cmd -MemoryGb 6
```

기본 서버 디렉터리는 `run/`이며, 플러그인은 `run/plugins/`에 넣습니다.
기본 배포 JAR은 `.sha256` sidecar가 반드시 있어야 합니다. 별도 개발 JAR을 의도적으로 무검증 실행할 때만 `-JarPath <path> -SkipHashCheck`를 함께 사용하십시오.

## 검증 기준

릴리스 전 다음을 수행합니다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat --no-daemon applyPatches
.\gradlew.bat --no-daemon build
.\gradlew.bat --no-daemon createPaperclipJar
```

`scripts\build-server.ps1`은 검증된 `local-SNAPSHOT` 산출물만 패키징하며, 오래된 JAR 오인을 막기 위해 `BUILD_NUMBER`가 설정된 CI 환경에서는 실행을 거부합니다. 로컬 릴리스 전에 해당 환경변수를 제거하십시오.

그 뒤 새 서버 디렉터리에서 부팅하여 다음을 확인합니다.

- Paper 26.1.2 정상 부팅
- `paper-global.yml`의 여섯 토글 생성
- 로그인/압축/PLAY 전환 오류 없음
- 기존 Paper 플러그인 로딩
- 기능을 각각 껐을 때 순정 Paper 코드 경로 사용

성능 수치는 실제 서버의 `/spark profiler start --timeout 600` 결과와 동일 workload A/B 비교로 판단해야 합니다.

### 실제 fast-path 진단

테스트 서버에서만 다음 JVM 옵션을 추가하면 종료 시 실제로 실행된 내부 경로를 한 줄로 출력합니다. 기본값은 꺼짐이며, 꺼진 상태에서는 카운터 상태와 종료 훅을 만들지 않습니다.

```text
-Dpaper.mobopt.verifyFastPaths=true
-Dpaper.mobopt.verifyFastPaths.runId=enabled-run
-Dpaper.mobopt.verifyFastPaths.outputFile=C:\server\mobopt-fastpath-metrics.txt
```

정상 종료 로그의 `MOBOPT_FASTPATH_METRICS` 줄은 공백으로 구분된 `key=value` 형식입니다. `outputFile`을 지정하면 콘솔과 동일한 한 줄을 해당 파일에도 덮어써서, 콘솔 스트림이 먼저 닫히는 환경에서도 결과를 회수할 수 있습니다. 경로가 잘못됐거나 기록이 거부돼도 서버 종료에는 영향을 주지 않습니다. `run_id`는 공백과 `=`을 `_`로 치환하고 최대 128자로 제한합니다.

경로탐색 검증 필드는 최적화 경로를 실제로 시도한 요청 수(`pathfinding_attempts`), direct/detour/A* 성공 수, 바닐라 fallback 수, 예산 소진 수, provider가 실제 평가한 좌표 수를 포함합니다. 이 카운터는 위 JVM 검증 속성을 켠 실행에서만 생성됩니다.

- frame 및 compression passthrough: retained/copied 호출 수(`*_count`)와 payload 바이트(`*_bytes`)
- frame prefix: in-place, 기능 ON이지만 부적격 fallback, 기능 OFF fallback의 호출 수와 body 바이트
- write dispatch: selector wakeup을 미루는 lazy task와 `PlayWriteDispatch`를 통과한 모든 normal task 수. normal에는 비-PLAY 프로토콜 dispatch도 포함됩니다.
- explosion: indexed lookup/full scan 횟수, 조회 시점의 전체 월드 플레이어 합계, 후보 합계, 정확한 64블록 거리 검사를 통과한 대상 합계. 마지막 값은 전송 완료 성공 횟수가 아닙니다.

이 옵션은 경로 실행 여부와 처리량을 증명하는 진단용입니다. 종료 훅의 snapshot은 동시 갱신을 멈추지 않고 각 카운터를 읽는 best-effort 비원자적 합계입니다. 성능 차이는 동일한 월드·플레이어·패킷 workload에서 토글 ON/OFF 실행의 CPU, allocation, tick 지표를 함께 비교하십시오.

## 라이선스

- Paper 및 이 포크의 서버 패치: 저장소의 GPL 조건
- Pathetic Mobs에서 참조·각색한 upstream 부분: CC0-1.0
- MobOpt 신규 코드와 서버 패치: 저장소에 적용되는 GPL 조건
- Pathetic engine/API 5.4.6: MIT
- PulseNet 코드는 사용하거나 복사하지 않았습니다. 공개된 기능 목표만 확인하고 Paper, Moonrise와 Netty의 공개 API/동작을 기준으로 이름·구조·경계값·테스트를 새로 설계했습니다.

자세한 내용은 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하십시오.
