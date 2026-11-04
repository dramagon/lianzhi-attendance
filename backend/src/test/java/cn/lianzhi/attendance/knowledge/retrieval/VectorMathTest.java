package cn.lianzhi.attendance.knowledge.retrieval;

import cn.lianzhi.attendance.knowledge.provider.Embedding;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VectorMathTest {
    @Test void hugeAndSubnormalFiniteValuesDoNotOverflowOrUnderflow() {
        for (double number : new double[]{Double.MAX_VALUE, Double.MIN_VALUE, 1d}) {
            var a = new Embedding(List.of(number, number));
            assertThat(VectorMath.cosine(a, a)).isCloseTo(1, within(1e-12));
        }
    }
    @Test void oppositeAndOrthogonalVectorsHaveExpectedCosine() {
        var a = new Embedding(List.of(1d, 0d));
        assertThat(VectorMath.cosine(a, new Embedding(List.of(-1d, 0d)))).isEqualTo(-1);
        assertThat(VectorMath.cosine(a, new Embedding(List.of(0d, 1d)))).isZero();
    }
    @Test void rejectsZeroAndInconsistentDimensions() {
        assertThatThrownBy(() -> VectorMath.unit(new Embedding(List.of(0d, 0d)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorMath.cosine(new Embedding(List.of(1d)), new Embedding(List.of(1d, 2d))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
