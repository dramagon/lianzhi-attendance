package cn.lianzhi.attendance.knowledge.provider;

import java.util.List;

/** No bundled implementation or synthetic vectors. Success returns an immutable vector list
 * in input order, one vector per text, with a consistent positive dimension. Implementations
 * validate inputs/budgets and report unavailable/failure explicitly; never return partial vectors. */
public interface EmbeddingProvider {
    ProviderAvailability availability();
    ProviderResult<List<Embedding>> embed(List<String> texts);
}
