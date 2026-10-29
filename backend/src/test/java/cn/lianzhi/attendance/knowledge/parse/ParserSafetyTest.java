package cn.lianzhi.attendance.knowledge.parse;

import cn.lianzhi.attendance.knowledge.text.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.poifs.crypt.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserSafetyTest {
    private final DocumentParser parser = new DocumentParser();
    @Test void blankStringExcelCellsDoNotInventCoordinateOnlyText() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            try (var book = legacy ? new HSSFWorkbook() : new XSSFWorkbook()) {
                var row = book.createSheet().createRow(0);
                row.createCell(0).setCellValue(""); row.createCell(1).setCellValue(" \t\n\uFEFF");
                var out = new ByteArrayOutputStream(); book.write(out);
                assertFailure(ParseCode.NO_TEXT, parser.parse(legacy ? "a.xls" : "a.xlsx", out.toByteArray()));
            }
        }
    }
    @Test void bomAndControlOnlyWordContentIsNotSubstantiveText() throws Exception {
        assertFailure(ParseCode.NO_TEXT, parser.parse("a.docx", docx("\uFEFF\t\n\uFEFF")));
    }
    @Test void extractsSyntheticChinesePdfKeepingPageThreeAndChunkOffsets() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/knowledge/unicode-three-pages.pdf")) {
            assertNotNull(input); bytes = input.readAllBytes();
        }
        var result = parser.parse("unicode.pdf", bytes);
        assertEquals(ParseCode.SUCCESS, result.code()); assertEquals(3, result.sections().size());
        assertTrue(result.sections().get(0).text().contains("合成制度样例：员工考勤规则。"));
        assertTrue(result.sections().get(1).text().isBlank());
        assertEquals(SourceLocation.page(3), result.sections().get(2).source());
        assertTrue(result.sections().get(2).text().contains("第三页：请假审批完成后更新考勤。"));
        var chunker = new TextChunker(new ChunkingOptions(8, 2, 200, 10, 50));
        var chunks = chunker.chunk(result.sections());
        assertEquals(chunks, chunker.chunk(parser.parse("unicode.pdf", bytes).sections()));
        for (var chunk : chunks) {
            assertNotEquals(SourceLocation.page(2), chunk.source());
            var normalized = TextNormalizer.normalize(result.sections().get(chunk.sectionIndex() - 1).text());
            int start = normalized.offsetByCodePoints(0, chunk.startOffset());
            int end = normalized.offsetByCodePoints(0, chunk.endOffset());
            assertEquals(normalized.substring(start, end), chunk.content());
        }
    }
    @Test void detectsEncryptedOoxmlContainer() throws Exception {
        try (var fs = new POIFSFileSystem()) {
            var encryption = new EncryptionInfo(EncryptionMode.agile);
            var encryptor = encryption.getEncryptor(); encryptor.confirmPassword("secret");
            try (var stream = encryptor.getDataStream(fs)) { stream.write(docx("保密")); }
            var out = new ByteArrayOutputStream(); fs.writeFilesystem(out);
            assertFailure(ParseCode.ENCRYPTED, parser.parse("a.docx", out.toByteArray()));
        }
    }
    @Test void rejectsMacroContentTypeRenamedAsDocx() throws Exception {
        byte[] macro = rewrite(docx("中文"), "[Content_Types].xml", text -> text.replace(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "application/vnd.ms-word.document.macroEnabled.main+xml"));
        assertFailure(ParseCode.UNSUPPORTED, parser.parse("a.docx", macro));
    }
    @Test void rejectsLegacyWorkbookWithMacroStorage() throws Exception {
        try (var book = new HSSFWorkbook()) {
            book.createSheet().createRow(0).createCell(0).setCellValue("文本");
            var out = new ByteArrayOutputStream(); book.write(out);
            try (var fs = new POIFSFileSystem(new ByteArrayInputStream(out.toByteArray()))) {
                fs.getRoot().createDirectory("_VBA_PROJECT_CUR").createDirectory("VBA");
                out.reset(); fs.writeFilesystem(out);
                assertFailure(ParseCode.UNSUPPORTED, parser.parse("a.xls", out.toByteArray()));
            }
        }
    }
    @Test void rejectsPowerpointMacroMetadata() throws Exception {
        try (var deck = new org.apache.poi.hslf.usermodel.HSLFSlideShow()) {
            var slide = deck.createSlide();
            var box = new org.apache.poi.hslf.usermodel.HSLFTextBox(); box.setText("宏标志测试"); slide.addShape(box);
            // Real binary PPT with synthetic VBAInfoAtom metadata; no executable macro is created.
            byte[] record = new byte[20];
            org.apache.poi.util.LittleEndian.putShort(record, 2, org.apache.poi.hslf.record.RecordTypes.VBAInfoAtom.typeID);
            org.apache.poi.util.LittleEndian.putInt(record, 4, 12);
            var atom = new org.apache.poi.hslf.record.VBAInfoAtom(record, 0, record.length); atom.setHasMacros(true);
            deck.getDocumentRecord().appendChildRecord(atom);
            var out = new ByteArrayOutputStream(); deck.write(out);
            assertFailure(ParseCode.UNSUPPORTED, parser.parse("a.ppt", out.toByteArray()));
        }
    }
    @Test void rejectsAmbiguousOleMainStreams() throws Exception {
        try (var book = new HSSFWorkbook()) {
            book.createSheet().createRow(0).createCell(0).setCellValue("data");
            var out = new ByteArrayOutputStream(); book.write(out);
            try (var fs = new POIFSFileSystem(new ByteArrayInputStream(out.toByteArray()))) {
                fs.getRoot().createDocument("PowerPoint Document", new ByteArrayInputStream(new byte[]{1}));
                out.reset(); fs.writeFilesystem(out);
                assertFailure(ParseCode.MALFORMED, parser.parse("a.xls", out.toByteArray()));
            }
        }
    }
    @Test void rejectsExtensionAndOleContentMismatch() throws Exception {
        try (var book = new HSSFWorkbook()) {
            book.createSheet().createRow(0).createCell(0).setCellValue("数据");
            var out = new ByteArrayOutputStream(); book.write(out);
            var result = parser.parse("a.ppt", out.toByteArray());
            assertFailure(ParseCode.FORMAT_MISMATCH, result); assertEquals(DocumentFormat.XLS, result.format());
        }
    }
    @Test void uncomputedFormulaIsExplicitRatherThanInventedZero() throws Exception {
        try (var book = new XSSFWorkbook()) {
            book.createSheet("公式").createRow(0).createCell(0).setCellFormula("1+2");
            var out = new ByteArrayOutputStream(); book.write(out);
            var result = parser.parse("a.xlsx", out.toByteArray());
            assertEquals(ParseCode.SUCCESS, result.code());
            assertTrue(result.sections().get(0).text().contains("未计算公式"));
        }
    }
    @Test void outputCodePointAndSectionLimitsDiscardAllPartialText() throws Exception {
        byte[] bytes = docx("😀😀😀");
        assertEquals(ParseCode.SUCCESS, parser(new ParseOptions(100_000, 4, 10, 10, 10, 100, 100_000, 100_000)).parse("a.docx", bytes).code());
        assertFailure(ParseCode.LIMIT_EXCEEDED, parser(new ParseOptions(100_000, 3, 10, 10, 10, 100, 100_000, 100_000)).parse("a.docx", bytes));
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("first"); doc.createParagraph().createRun().setText("second");
            var out = new ByteArrayOutputStream(); doc.write(out);
            assertFailure(ParseCode.LIMIT_EXCEEDED, parser(new ParseOptions(100_000, 100, 1, 10, 10, 100, 100_000, 100_000)).parse("a.docx", out.toByteArray()));
        }
    }
    @Test void enforcesPageAndCellLimits() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage()); pdf.addPage(new PDPage()); var out = new ByteArrayOutputStream(); pdf.save(out);
            assertFailure(ParseCode.LIMIT_EXCEEDED, parser(new ParseOptions(100_000, 100, 10, 1, 10, 100, 100_000, 100_000)).parse("a.pdf", out.toByteArray()));
        }
        try (var book = new XSSFWorkbook()) {
            var row = book.createSheet().createRow(0); row.createCell(0).setCellValue("A"); row.createCell(1).setCellValue("B");
            var out = new ByteArrayOutputStream(); book.write(out);
            assertFailure(ParseCode.LIMIT_EXCEEDED, parser(new ParseOptions(100_000, 100, 10, 10, 1, 100, 100_000, 100_000)).parse("a.xlsx", out.toByteArray()));
        }
    }
    @Test void preflightsZipCountIndividualAndTotalExpansion() throws Exception {
        byte[] bytes = docx("正文".repeat(100));
        for (ParseOptions options : List.of(
                new ParseOptions(100_000, 100, 10, 10, 10, 1, 100_000, 100_000),
                new ParseOptions(100_000, 100, 10, 10, 10, 100, 100, 100_000),
                new ParseOptions(100_000, 100, 10, 10, 10, 100, 100_000, 100))) {
            assertFailure(ParseCode.LIMIT_EXCEEDED, parser(options).parse("a.docx", bytes));
        }
    }
    @Test void rejectsCorruptZipAndPdfWithoutContentLeaks() {
        assertFailure(ParseCode.MALFORMED, parser.parse("a.docx", new byte[]{'P','K',3,4,0}));
        assertFailure(ParseCode.MALFORMED, parser.parse("a.pdf", "%PDF-1.7\nwrong".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void optionsAndResultsHaveExplicitInvariantChecks() {
        assertThrows(NullPointerException.class, () -> new DocumentParser(null));
        assertThrows(IllegalArgumentException.class, () -> new ParseOptions(1, 1, 1, 1, 1, 1, 0, 1));
        var sections = new ArrayList<TextSection>(); sections.add(new TextSection(SourceLocation.file(), "正文"));
        var result = new ParseResult(DocumentFormat.DOCX, ParseCode.SUCCESS, "ok", sections); sections.clear();
        assertEquals(1, result.sections().size());
        assertThrows(IllegalArgumentException.class, () -> new ParseResult(DocumentFormat.PDF, ParseCode.MALFORMED, "bad", result.sections()));
    }
    private static DocumentParser parser(ParseOptions options) { return new DocumentParser(options); }
    private static void assertFailure(ParseCode code, ParseResult result) {
        assertEquals(code, result.code()); assertFalse(result.message().isBlank()); assertTrue(result.sections().isEmpty());
    }
    private static byte[] docx(String text) throws Exception {
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text); var out = new ByteArrayOutputStream(); doc.write(out); return out.toByteArray();
        }
    }
    private static byte[] rewrite(byte[] bytes, String entryName, java.util.function.UnaryOperator<String> change) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes)); var target = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] content = zip.readAllBytes();
                if (entry.getName().equals(entryName)) content = change.apply(new String(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                target.putNextEntry(new ZipEntry(entry.getName())); target.write(content); target.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
