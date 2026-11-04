package cn.lianzhi.attendance.knowledge.provider;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderContractTest {
    @Test void successfulResultRequiresAValueAndExplicitMessage() {
        assertThrows(NullPointerException.class, () -> new ProviderResult<>(ProviderResult.State.SUCCESS, "ok", null));
        assertThrows(NullPointerException.class, () -> new ProviderResult<>(null, "ok", "text"));
        assertThrows(IllegalArgumentException.class, () -> new ProviderResult<>(ProviderResult.State.SUCCESS, " ", "text"));
        assertThrows(NullPointerException.class, () -> new ProviderResult<>(ProviderResult.State.SUCCESS, null, "text"));
        assertEquals("text", new ProviderResult<>(ProviderResult.State.SUCCESS, "识别成功", "text").value());
    }
    @Test void unavailableAndFailedResultsCannotPretendToHaveOutput() {
        for (var state : List.of(ProviderResult.State.UNAVAILABLE, ProviderResult.State.FAILURE)) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderResult<>(state, "未配置", "fake"));
            assertNull(new ProviderResult<>(state, "尚未接入", null).value());
        }
    }
    @Test void vectorsRejectEmptyNonFiniteAndNullValuesAndCopyInput() {
        assertThrows(IllegalArgumentException.class, () -> new Embedding(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Embedding(List.of(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new Embedding(List.of(Double.POSITIVE_INFINITY)));
        assertThrows(NullPointerException.class, () -> new Embedding(null));
        assertThrows(NullPointerException.class, () -> new Embedding(Arrays.asList(1.0, null)));
        var mutable = new ArrayList<>(List.of(0.25, -0.5)); var vector = new Embedding(mutable); mutable.clear();
        assertEquals(List.of(0.25, -0.5), vector.values());
        assertThrows(UnsupportedOperationException.class, () -> vector.values().clear());
    }
}
