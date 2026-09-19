# MUD Rust JNI 엔티티 배치

이 모듈은 실제 Java NMS Entity 객체를 받아 JNI로 위치와 회전을 갱신합니다. Java에서 전용 태그·AI/물리/중력 꺼짐·무적·생존·탑승 없음·청크 로드 여부를 먼저 검사하고 서버 메인 스레드에서만 호출합니다.

JVM 객체의 raw 메모리 오프셋을 쓰지 않습니다. setPos를 호출해야 바운딩 박스·청크 위치 인덱스·추적 시스템의 콜백이 함께 동작합니다. 캐시한 jmethodID와 global class reference를 사용하고 개별 entity reference는 배치 안에서만 유지합니다. primitive array는 JNI 호출 전에 복사하므로 GC를 고정한 상태에서 Java를 호출하지 않습니다. 예외가 발생한 배치는 이미 일부 이동했을 수 있으므로 재실행하지 않습니다.

~~~powershell
cargo build --release --locked --manifest-path native/mud-entities/Cargo.toml
java --enable-native-access=ALL-UNNAMED -Dmud.native.entities=<절대경로/mud_entities.dll> -Dmud.native.entityBatch=true -jar <26.3-mud.jar> --nogui
~~~

Linux 파일명은 libmud_entities.so입니다. 서버의 presentation-mob-tick과 대응 MUD 플러그인이 필요합니다. 기본은 꺼짐입니다. 비교용 -Dmud.native.entities.javaControl=true는 같은 사전 검사·배치 구조에서 실제 이동만 Java 루프로 수행합니다. Rust 모듈이 없거나 설정하지 않으면 기존 Java 경로를 사용합니다.

단독 JNI 계약 테스트:

~~~powershell
javac -d native/mud-entities/target/java-test native/mud-entities/tests/java/io/papermc/paper/optimization/mud/MudNativeEntities.java
java -Xcheck:jni --enable-native-access=ALL-UNNAMED -cp native/mud-entities/target/java-test io.papermc.paper.optimization.mud.MudNativeEntities <절대경로/mud_entities.dll>
~~~

이 테스트는 실제 Java 객체 변경, 배열 길이/NaN 거절, Java 예외 전파, GC 이후 참조 수명을 확인합니다. 실제 서버에서는 20클라이언트·2,520엔티티에서 core 위치와 NMS 위치를 주기적으로 비교하고 JNI 배치 실행 횟수를 기록합니다. 결과와 권장 활성화 여부는 mc-luck-defense 저장소의 성능 보고서를 따릅니다.
