package cn.lianzhi.attendance.knowledge.parse;

import java.io.IOException;
import cn.lianzhi.attendance.knowledge.text.SourceLocation;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class PdfTextParser {
    private PdfTextParser() {}
    static void extract(byte[] bytes, TextCollector collector) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new ParseFailure(ParseCode.ENCRYPTED);
            ParseFailure.limit(document.getNumberOfPages() > collector.options.maxPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(page); stripper.setEndPage(page);
                try (var writer = collector.section(SourceLocation.page(page))) {
                    stripper.writeText(document, writer);
                }
            }
        }
    }
}
