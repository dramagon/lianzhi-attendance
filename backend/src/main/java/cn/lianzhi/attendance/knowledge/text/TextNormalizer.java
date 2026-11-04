package cn.lianzhi.attendance.knowledge.text;

import java.text.Normalizer;
import java.util.Objects;

/** NFC and conservative whitespace normalization; no case folding or instruction interpretation. */
public final class TextNormalizer {
    private TextNormalizer() {}

    public static String normalize(String raw) {
        Objects.requireNonNull(raw, "正文不能为空");
        validateUnicode(raw);
        String text = Normalizer.normalize(raw.startsWith("\uFEFF") ? raw.substring(1) : raw, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u2028', '\n').replace('\u2029', '\n');
        StringBuilder result = new StringBuilder(text.length());
        boolean pendingSpace = false;
        int pendingNewlines = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\uFEFF' && result.isEmpty()) continue;
            if (cp == '\n') {
                pendingNewlines = Math.min(2, pendingNewlines + 1);
                pendingSpace = false;
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
                pendingSpace = true;
            } else {
                if (!result.isEmpty()) {
                    if (pendingNewlines > 0) result.append("\n".repeat(pendingNewlines));
                    else if (pendingSpace) result.append(' ');
                }
                result.appendCodePoint(cp);
                pendingSpace = false;
                pendingNewlines = 0;
            }
        }
        return result.toString();
    }

    private static void validateUnicode(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= raw.length() || !Character.isLowSurrogate(raw.charAt(i))) {
                    throw new IllegalArgumentException("正文包含不完整的Unicode字符");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("正文包含不完整的Unicode字符");
            }
        }
    }
}
