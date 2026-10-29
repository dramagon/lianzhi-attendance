package cn.lianzhi.attendance.knowledge.provider;

import java.util.List;

/** One provider-generated vector; the interface supplies no fake implementation. */
public record Embedding(List<Double> values) {
    public Embedding {
        values = List.copyOf(values);
        if (values.isEmpty() || values.stream().anyMatch(value -> !Double.isFinite(value)))
            throw new IllegalArgumentException("向量必须非空且所有维度必须是有限数值");
    }
}
