package cn.lianzhi.attendance.knowledge.parse;

import java.util.*;
import cn.lianzhi.attendance.knowledge.text.TextNormalizer;
import org.apache.poi.EncryptedDocumentException;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/** Internal, in-memory parser. The name is detection/display metadata, never a filesystem path.
 * Null/blank names and null bytes yield INVALID_INPUT. Constructor rejects null options.
 * No OCR, macros, formula evaluation, external references, HTTP or persistence. */
public final class DocumentParser {
    private final ParseOptions options;
    public DocumentParser() { this(ParseOptions.defaults()); }
    public DocumentParser(ParseOptions options) { this.options = Objects.requireNonNull(options); }
    public ParseResult parse(String fileName, byte[] bytes) {
        DocumentFormat actual = DocumentFormat.UNKNOWN;
        if (fileName == null || fileName.isBlank() || bytes == null) return failure(actual, ParseCode.INVALID_INPUT);
        if (bytes.length == 0) return failure(actual, ParseCode.EMPTY_INPUT);
        if (bytes.length > options.maxBytes()) return failure(actual, ParseCode.LIMIT_EXCEEDED);
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        DocumentFormat expected;
        try { expected = DocumentFormat.valueOf(extension); }
        catch (IllegalArgumentException e) { return failure(actual, ParseCode.UNSUPPORTED); }
        if (expected == DocumentFormat.DOC || expected == DocumentFormat.UNKNOWN) return failure(actual, ParseCode.UNSUPPORTED);
        try {
            actual = FormatDetector.detect(bytes, options);
            if (actual != expected) return failure(actual, ParseCode.FORMAT_MISMATCH);
            TextCollector collector = new TextCollector(options);
            if (actual == DocumentFormat.PDF) PdfTextParser.extract(bytes, collector);
            else OfficeTextParser.extract(actual, bytes, collector);
            var sections = collector.finish();
            if (sections.stream().noneMatch(s -> !TextNormalizer.normalize(s.text()).isEmpty()))
                return failure(actual, ParseCode.NO_TEXT);
            return new ParseResult(actual, ParseCode.SUCCESS, "解析成功", sections);
        } catch (ParseFailure e) { return failure(actual, e.code); }
        catch (InvalidPasswordException | EncryptedDocumentException e) { return failure(actual, ParseCode.ENCRYPTED); }
        catch (java.io.IOException | RuntimeException e) { return failure(actual, ParseCode.MALFORMED); }
    }
    private static ParseResult failure(DocumentFormat format, ParseCode code) {
        String message = switch (code) {
            case INVALID_INPUT -> "文件名或内容参数无效"; case EMPTY_INPUT -> "文件为空";
            case UNSUPPORTED -> "此格式尚不支持（含宏文件和旧版DOC）";
            case FORMAT_MISMATCH -> "文件扩展名与实际格式不符"; case ENCRYPTED -> "不支持加密文件";
            case LIMIT_EXCEEDED -> "文件解析资源超限"; case NO_TEXT -> "未提取到正文，可能需要OCR";
            default -> "文件损坏或结构无效";
        };
        return new ParseResult(format, code, message, List.of());
    }
}
