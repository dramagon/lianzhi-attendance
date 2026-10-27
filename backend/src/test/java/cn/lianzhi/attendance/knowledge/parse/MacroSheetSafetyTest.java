package cn.lianzhi.attendance.knowledge.parse;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class MacroSheetSafetyTest {
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true"})
    void rejectsExcel4MacroMetadataBeforeExtractingAnyText(boolean boundSheet, boolean bof) throws Exception {
        try (var book = new HSSFWorkbook()) {
            book.createSheet("Macro1").createRow(0).createCell(0).setCellValue("synthetic macro-sheet metadata, no executable macro");
            var output = new ByteArrayOutputStream(); book.write(output);
            try (var fs = new POIFSFileSystem(new ByteArrayInputStream(output.toByteArray()))) {
                byte[] workbook;
                try (var input = fs.createDocumentInputStream("Workbook")) { workbook = input.readAllBytes(); }
                boolean patched = false;
                for (int offset = 0; offset + 4 <= workbook.length;) {
                    int sid = LittleEndian.getUShort(workbook, offset);
                    int size = LittleEndian.getUShort(workbook, offset + 2);
                    assertTrue(offset + 4 + size <= workbook.length);
                    if (sid == BoundSheetRecord.sid) {
                        int sheetBof = LittleEndian.getInt(workbook, offset + 4);
                        assertEquals(BOFRecord.sid, LittleEndian.getUShort(workbook, sheetBof));
                        if (boundSheet) workbook[offset + 9] = 1; // [MS-XLS] BoundSheet8.dt: Excel 4 macro sheet.
                        if (bof) LittleEndian.putShort(workbook, sheetBof + 6, (short) BOFRecord.TYPE_EXCEL_4_MACRO);
                        patched = true; break;
                    }
                    offset += 4 + size;
                }
                assertTrue(patched, "Fixture must contain a real BoundSheet/BOF pair");
                fs.getRoot().getEntry("Workbook").delete();
                fs.getRoot().createDocument("Workbook", new ByteArrayInputStream(workbook));
                output.reset(); fs.writeFilesystem(output);
                assertUnsupported(new DocumentParser().parse("macro.xls", output.toByteArray()));
            }
        }
    }
    static Stream<XSSFRelation> macroSheetTypes() {
        return Stream.of(XSSFRelation.MACRO_SHEET_XML, XSSFRelation.MACRO_SHEET_BIN,
                XSSFRelation.INTL_MACRO_SHEET_XML, XSSFRelation.INTL_MACRO_SHEET_BIN);
    }
    @ParameterizedTest
    @MethodSource("macroSheetTypes")
    void rejectsRenamedOoxmlMacroSheetParts(XSSFRelation relation) throws Exception {
        try (var book = new XSSFWorkbook()) {
            book.createSheet("Macro1").createRow(0).createCell(0).setCellValue("synthetic metadata");
            var output = new ByteArrayOutputStream(); book.write(output);
            byte[] rewritten = withSheetType(output.toByteArray(), relation.getContentType());
            assertUnsupported(new DocumentParser().parse("macro.xlsx", rewritten));
        }
    }
    private static byte[] withSheetType(byte[] bytes, String type) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var source = new ZipInputStream(new ByteArrayInputStream(bytes)); var target = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                byte[] content = source.readAllBytes();
                if (entry.getName().equals("[Content_Types].xml")) content = new String(content, StandardCharsets.UTF_8)
                        .replace(XSSFRelation.WORKSHEET.getContentType(), type).getBytes(StandardCharsets.UTF_8);
                target.putNextEntry(new ZipEntry(entry.getName())); target.write(content); target.closeEntry();
            }
        }
        return output.toByteArray();
    }
    private static void assertUnsupported(ParseResult result) {
        assertEquals(ParseCode.UNSUPPORTED, result.code()); assertTrue(result.sections().isEmpty());
    }
}
