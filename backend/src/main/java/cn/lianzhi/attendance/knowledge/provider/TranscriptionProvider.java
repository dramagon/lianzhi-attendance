package cn.lianzhi.attendance.knowledge.provider;

import java.util.List;
import cn.lianzhi.attendance.knowledge.text.TextSection;

/** No bundled implementation. Successful immutable sections use measured TIME_RANGE sources.
 * Implementations validate media and report unavailable/failure without fabricated timestamps. */
public interface TranscriptionProvider {
    ProviderAvailability availability();
    ProviderResult<List<TextSection>> transcribe(String fileName, byte[] mediaBytes);
}
