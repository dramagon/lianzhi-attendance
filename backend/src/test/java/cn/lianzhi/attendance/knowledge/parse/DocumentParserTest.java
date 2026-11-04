package cn.lianzhi.attendance.knowledge.parse;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import cn.lianzhi.attendance.knowledge.text.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();
    @Test void rejectsEncryptedPdfEvenWithEmptyUserPassword() throws Exception {
        for (String password : new String[]{"", "secret"}) {
            try (var pdf = new PDDocument()) {
                pdf.addPage(new PDPage());
                pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", password,
                        new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
                var out = new ByteArrayOutputStream(); pdf.save(out);
                assertEquals(ParseCode.ENCRYPTED, parser.parse("a.pdf", out.toByteArray()).code());
            }
        }
    }
    @Test void blankPdfNeedsOcrRatherThanInventedText() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage()); var out = new ByteArrayOutputStream(); pdf.save(out);
            var result = parser.parse("blank.pdf", out.toByteArray());
            assertEquals(ParseCode.NO_TEXT, result.code()); assertTrue(result.sections().isEmpty());
        }
    }
    @Test void rejectsOversizeInputWithoutPartialText() {
        var result = parser.parse("a.pdf", new byte[10 * 1024 * 1024 + 1]);
        assertEquals(ParseCode.LIMIT_EXCEEDED, result.code()); assertTrue(result.sections().isEmpty());
    }
    @Test void parsesRealPdfKeepingBlankPageNumber() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage()); pdf.addPage(new PDPage());
            try (var content = new PDPageContentStream(pdf, pdf.getPage(1))) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700); content.showText("Actual page two"); content.endText();
            }
            var out = new ByteArrayOutputStream(); pdf.save(out);
            ParseResult result = parser.parse("example.pdf", out.toByteArray());
            assertEquals(ParseCode.SUCCESS, result.code()); assertEquals(DocumentFormat.PDF, result.format());
            assertEquals(2, result.sections().size()); assertEquals(SourceLocation.page(2), result.sections().get(1).source());
            assertTrue(result.sections().get(1).text().contains("Actual page two"));
        }
    }
    @Test void parsesChineseDocxParagraphTableParagraphInOrder() throws Exception {
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("制度开始😀");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("表格内容");
            doc.createParagraph().createRun().setText("制度结束");
            var out = new ByteArrayOutputStream(); doc.write(out);
            var result = parser.parse("制度.docx", out.toByteArray());
            assertEquals(ParseCode.SUCCESS, result.code());
            String text = result.sections().stream().map(TextSection::text).reduce("", String::concat);
            assertTrue(text.indexOf("制度开始") < text.indexOf("表格内容"));
            assertTrue(text.indexOf("表格内容") < text.indexOf("制度结束"));
            assertTrue(result.sections().stream().allMatch(s -> s.source().kind() == SourceLocation.Kind.SECTION));
            var chunks = new TextChunker().chunk(result.sections());
            assertEquals(chunks, new TextChunker().chunk(parser.parse("制度.docx", out.toByteArray()).sections()));
            assertEquals(0, chunks.get(0).startOffset());
            assertThrows(UnsupportedOperationException.class, () -> result.sections().clear());
        }
    }
    @Test void parsesBothExcelFormatsWithCoordinatesAndCachedFormula() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            try (var book = legacy ? new HSSFWorkbook() : new XSSFWorkbook()) {
                book.createSheet("空白"); var sheet = book.createSheet("工资");
                var row = sheet.createRow(2); row.createCell(1).setCellValue("中文😀");
                row.createCell(2).setCellFormula("1+2");
                // Fixture generation calculates the stored cache; the parser must never evaluate.
                book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(2));
                var out = new ByteArrayOutputStream(); book.write(out);
                var result = parser.parse(legacy ? "a.xls" : "a.xlsx", out.toByteArray());
                assertEquals(ParseCode.SUCCESS, result.code()); assertEquals(2, result.sections().size());
                assertEquals(SourceLocation.sheet("工资"), result.sections().get(1).source());
                assertTrue(result.sections().get(1).text().contains("B3"));
                assertTrue(result.sections().get(1).text().contains("中文😀"));
                assertTrue(result.sections().get(1).text().contains("C3: 3\n"));
            }
        }
    }
    @Test void parsesBothPowerpointFormatsWithRealSlides() throws Exception {
        try (var deck = new XMLSlideShow()) {
            deck.createSlide(); var slide = deck.createSlide(); slide.createTextBox().setText("幻灯片中文😀");
            var table = slide.createTable(1, 1); table.getCell(0, 0).setText("表格");
            var out = new ByteArrayOutputStream(); deck.write(out);
            var result = parser.parse("a.pptx", out.toByteArray());
            assertEquals(ParseCode.SUCCESS, result.code()); assertEquals(SourceLocation.slide(2), result.sections().get(1).source());
            assertTrue(result.sections().get(1).text().contains("表格"));
        }
        try (var deck = new HSLFSlideShow()) {
            deck.createSlide(); var slide = deck.createSlide(); var box = new HSLFTextBox(); box.setText("旧版中文"); slide.addShape(box);
            var out = new ByteArrayOutputStream(); deck.write(out);
            var result = parser.parse("a.ppt", out.toByteArray());
            assertEquals(ParseCode.SUCCESS, result.code()); assertTrue(result.sections().get(1).text().contains("旧版中文"));
        }
    }
    @Test void rejectsInvalidUnsupportedAndMalformedInput() {
        assertEquals(ParseCode.INVALID_INPUT, parser.parse(null, new byte[]{1}).code());
        assertEquals(ParseCode.INVALID_INPUT, parser.parse("a.pdf", null).code());
        assertEquals(ParseCode.EMPTY_INPUT, parser.parse("a.pdf", new byte[0]).code());
        assertEquals(ParseCode.UNSUPPORTED, parser.parse("a.doc", new byte[]{1}).code());
        assertEquals(ParseCode.UNSUPPORTED, parser.parse("a.docm", new byte[]{1}).code());
        assertEquals(ParseCode.FORMAT_MISMATCH, parser.parse("a.pdf", new byte[]{1,2,3}).code());
        assertEquals(ParseCode.MALFORMED, parser.parse("a.pdf", "%PDF-1.7\ninvalid".getBytes()).code());
    }
    @Test void rejectsRenamedOoxml() throws Exception {
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("content"); var out = new ByteArrayOutputStream(); doc.write(out);
            var result = parser.parse("a.xlsx", out.toByteArray());
            assertEquals(ParseCode.FORMAT_MISMATCH, result.code()); assertEquals(DocumentFormat.DOCX, result.format());
            assertTrue(result.sections().isEmpty());
        }
    }
}
