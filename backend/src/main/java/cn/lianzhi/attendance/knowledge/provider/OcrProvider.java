package cn.lianzhi.attendance.knowledge.provider;

import java.util.List;
import cn.lianzhi.attendance.knowledge.text.TextSection;

/** Substitutable boundary, with no bundled implementation or network access.
 * Implementations validate input, return immutable sections with real page/image sources,
 * and report unavailable/failure explicitly without partial or invented text. */
public interface OcrProvider {
    ProviderAvailability availability();
    ProviderResult<List<TextSection>> recognize(String fileName, byte[] imageBytes);
}
