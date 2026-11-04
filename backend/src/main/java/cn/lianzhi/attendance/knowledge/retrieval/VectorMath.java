package cn.lianzhi.attendance.knowledge.retrieval;

import cn.lianzhi.attendance.knowledge.provider.Embedding;

/** Scale before normalization to avoid overflow/underflow for finite provider values. */
public final class VectorMath {
    private VectorMath() {}
    public static double[] unit(Embedding vector) {
        if (vector.values().size() > 4096) throw new IllegalArgumentException("向量维度超限");
        double max = vector.values().stream().mapToDouble(Math::abs).max().orElseThrow();
        if (max == 0) throw new IllegalArgumentException("零向量不可用于检索");
        double[] result = new double[vector.values().size()];
        double sum = 0;
        for (int i = 0; i < result.length; i++) { result[i] = vector.values().get(i) / max; sum += result[i] * result[i]; }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < result.length; i++) result[i] /= norm;
        return result;
    }
    public static double cosine(Embedding a, Embedding b) {
        double[] x = unit(a), y = unit(b);
        if (x.length != y.length) throw new IllegalArgumentException("向量维度不一致");
        double score = 0;
        for (int i = 0; i < x.length; i++) score += x[i] * y[i];
        return Math.max(-1, Math.min(1, score));
    }
}
