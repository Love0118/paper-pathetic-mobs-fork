# MobOpt Paper 1.21.8

이 포크는 Paper `ver/1.21.8` 최종 커밋
`29c8822d90899c89d2689338e81a98f690bcba12`를 기반으로 합니다.

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
```

각 기능은 `enabled` 하나로만 켜거나 끕니다. 설정 변경 뒤에는 서버를 완전히 재시작하십시오.

### Pathetic mob pathfinding

정확히 바닐라 `WalkNodeEvaluator`를 쓰는 단일 목표, 정확도 `0`, 동일 높이의 평지 경로 요청에 한해 다음 순서로 처리합니다.

1. 같은 Y 높이의 위험 없는 직선 fast path
2. 최대 8블록의 직교 우회 fast path
3. Pathetic 5.4.6 A* 탐색

원본 Pathetic Mobs에 있던 폴백만 유지합니다. 미지원 요청이거나 Pathetic 결과가 없으면 기존 Paper/바닐라 경로탐색이 계속 실행됩니다. 별도의 자동 폴백 정책은 추가하지 않았습니다.

현재 포팅은 원본과 동일하게 수평 4방향 탐색을 사용하되, 준비된 바닐라 evaluator의 `PathType`, 몹별 malus와 위험 블록 인접 비용을 재사용합니다. 시작점과 바닥 높이가 달라지는 계단·점프·낙하, 문·레일·유체 같은 전이별 규칙, 비행·수륙 evaluator, 정확도 반경 경로는 즉시 Paper 경로탐색으로 이어집니다. 직선 fast path도 follow range와 대각 모서리 충돌 규칙을 검사합니다.

### PLAY protocol flush consolidation

Paper는 이미 메인 서버 tick에서 생성된 플레이어 패킷을 묶습니다. 이 포크는 그 동작을 중복 구현하지 않고, `PLAY` 프로토콜에서 inbound read 바깥에 발생한 연속 flush를 현재 Netty event-loop 회전이 끝날 때 한 번으로 통합합니다.

- `HANDSHAKING`, `STATUS`, `LOGIN`, `CONFIGURATION`은 Paper의 즉시 flush 동작을 유지합니다.
- 로그인 압축 경계와 프로토콜 전환을 건드리지 않습니다.
- 패킷 write 순서와 future listener를 별도 큐로 옮기거나 재생하지 않습니다.
- `PLAY -> CONFIGURATION -> PLAY` 전환 때 handler 모드를 함께 전환합니다.
- 기존 Paper JVM 옵션 `-DPaper.disableFlushConsolidate=true`가 지정되면 이 토글보다 우선하여 consolidation handler를 설치하지 않습니다.

## 빌드

요구 사항:

- PowerShell 7 (`pwsh`)
- JDK 21 (`C:\Program Files\Java\jdk-21` 또는 `JAVA_HOME`)
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

## 검증 기준

릴리스 전 다음을 수행합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat --no-daemon applyPatches
.\gradlew.bat --no-daemon build
.\gradlew.bat --no-daemon createMojmapPaperclipJar
```

그 뒤 새 서버 디렉터리에서 부팅하여 다음을 확인합니다.

- Paper 1.21.8 정상 부팅
- `paper-global.yml`의 두 토글 생성
- 로그인/압축/PLAY 전환 오류 없음
- 기존 Paper 플러그인 로딩
- 기능을 각각 껐을 때 순정 Paper 코드 경로 사용

성능 수치는 실제 서버의 `/spark profiler start --timeout 600` 결과와 동일 workload A/B 비교로 판단해야 합니다.

## 라이선스

- Paper 및 이 포크의 서버 패치: 저장소의 GPL 조건
- Pathetic Mobs에서 참조·각색한 upstream 부분: CC0-1.0
- MobOpt 신규 코드와 서버 패치: 저장소에 적용되는 GPL 조건
- Pathetic engine/API 5.4.6: MIT
- PulseNet 코드는 사용하거나 복사하지 않았습니다. 네트워크 패치는 Paper와 Netty의 공개 동작을 기반으로 독자 구현했습니다.

자세한 내용은 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하십시오.
