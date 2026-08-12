package com.sharedsync.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 필드 번호 부여 규칙.
 *
 * 이 규칙이 틀리면 클라이언트가 같은 바이트를 다른 필드로 읽는다 — 파싱은 성공하므로 예외가 없고,
 * 값이 뒤섞인 채로 저장된다. 실제 빌드에서만 확인할 수 있는 상태로 두면 안 되는 종류의 코드다.
 */
class WireNumberLockTest {

    private static Map<String, List<String>> entity(String name, String... fields) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(name, List.of(fields));
        return map;
    }

    private static Map<String, Integer> lock(Object... pairs) {
        Map<String, Integer> locked = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            locked.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return locked;
    }

    @Test
    @DisplayName("잠금이 없으면 선언 순서로 매긴다 (첫 빌드)")
    void assignsSequentiallyWithoutLock() {
        Map<String, Integer> numbers = WireNumberLock.assignNumbers(
                entity("Plan", "plan_id", "plan_name", "user_id"), null);

        assertThat(numbers).containsExactly(
                Map.entry("Plan.plan_id", 1),
                Map.entry("Plan.plan_name", 2),
                Map.entry("Plan.user_id", 3));
    }

    @Test
    @DisplayName("필드를 지워도 뒤 필드의 번호가 밀리지 않는다")
    void removedFieldDoesNotShiftOthers() {
        // transportation_type(5) 이 엔티티에서 사라진 상황. 예전에는 user_id 가 6 -> 5 로 당겨져
        // 컴파일이 깨졌고, 앱이 할 수 있는 일은 번호를 재사용하는 것뿐이었다.
        Map<String, Integer> numbers = WireNumberLock.assignNumbers(
                entity("Plan", "plan_id", "user_id", "destination_id"),
                lock("Plan.plan_id", 1, "Plan.transportation_type", 5,
                        "Plan.user_id", 6, "Plan.destination_id", 7));

        assertThat(numbers)
                .containsEntry("Plan.user_id", 6)
                .containsEntry("Plan.destination_id", 7);
    }

    @Test
    @DisplayName("지워진 필드의 번호는 새 필드에 재사용되지 않는다")
    void retiredNumberIsNeverReused() {
        Map<String, Integer> numbers = WireNumberLock.assignNumbers(
                entity("Plan", "plan_id", "user_id", "memo"),
                lock("Plan.plan_id", 1, "Plan.transportation_type", 5, "Plan.user_id", 6));

        assertThat(numbers.get("Plan.memo"))
                .as("구 클라이언트가 5번으로 보낸 값이 새 필드로 읽히면 안 된다")
                .isEqualTo(7);
        assertThat(numbers).doesNotContainValue(5);
    }

    @Test
    @DisplayName("선언 순서를 바꿔도 번호는 움직이지 않는다")
    void reorderingDoesNotRenumber() {
        Map<String, Integer> numbers = WireNumberLock.assignNumbers(
                entity("Plan", "user_id", "plan_id", "plan_name"),
                lock("Plan.plan_id", 1, "Plan.plan_name", 2, "Plan.user_id", 3));

        assertThat(numbers)
                .containsEntry("Plan.plan_id", 1)
                .containsEntry("Plan.plan_name", 2)
                .containsEntry("Plan.user_id", 3);
    }

    @Test
    @DisplayName("새 필드는 그 엔티티가 쓴 적 있는 가장 큰 번호 다음을 받는다")
    void newFieldTakesNextFreeNumber() {
        Map<String, Integer> numbers = WireNumberLock.assignNumbers(
                entity("Plan", "plan_id", "added_first", "added_second"),
                lock("Plan.plan_id", 1, "Plan.gone", 9));

        assertThat(numbers)
                .containsEntry("Plan.added_first", 10)
                .containsEntry("Plan.added_second", 11);
    }

    @Test
    @DisplayName("엔티티마다 번호 공간이 따로다")
    void numbersAreScopedPerEntity() {
        Map<String, List<String>> entities = new LinkedHashMap<>();
        entities.put("Plan", List.of("plan_id"));
        entities.put("TimeTable", List.of("time_table_id"));

        Map<String, Integer> numbers = WireNumberLock.assignNumbers(entities,
                lock("Plan.plan_id", 4));

        assertThat(numbers)
                .containsEntry("Plan.plan_id", 4)
                .containsEntry("TimeTable.time_table_id", 1);
    }

    @Test
    @DisplayName("사라진 필드는 예약 번호로 보고된다")
    void reportsRetiredNumbersAsReserved() {
        Map<String, Integer> locked = lock("Plan.plan_id", 1, "Plan.transportation_type", 5,
                "Plan.dropped_too", 3, "TimeTable.time_table_id", 1);
        Map<String, Integer> assigned = WireNumberLock.assignNumbers(
                entity("Plan", "plan_id"), locked);

        Map<String, List<Integer>> reserved = WireNumberLock.reserved(locked, assigned);

        assertThat(reserved.get("Plan"))
                .as("작은 번호부터 정렬되어야 .proto 의 reserved 절이 읽을 만해진다")
                .containsExactly(3, 5);
    }
}
