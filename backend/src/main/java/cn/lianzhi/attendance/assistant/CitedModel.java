package cn.lianzhi.attendance.assistant;

import cn.lianzhi.attendance.knowledge.provider.*;
import java.util.List;

/** Only grounded text generation. No tools, actions, identity parameters or automatic writes. */
public interface CitedModel {
    record Evidence(String id, String text) {}
    record Quote(String id, String quote) {}
    record Draft(boolean answerable, String answer, List<Quote> citations) {
        public Draft { citations=List.copyOf(citations); }
    }
    ProviderAvailability availability();
    default String mode() { return "GENERATION"; }
    ProviderResult<Draft> generate(String question, List<Evidence> evidence);
}
