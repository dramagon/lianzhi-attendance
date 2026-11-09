package cn.lianzhi.attendance.knowledge.parse;

import java.io.*;
import java.util.Locale;
import cn.lianzhi.attendance.knowledge.text.SourceLocation;
import cn.lianzhi.attendance.knowledge.text.TextNormalizer;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;

/** Word body paragraphs/tables and all physical sheets/slides (including hidden ones).
 * Does not extract notes, headers/footers, embedded objects or external targets. */
final class OfficeTextParser {
    private OfficeTextParser() {}
    static void extract(DocumentFormat format, byte[] bytes, TextCollector collector) throws IOException {
        try (var input = new ByteArrayInputStream(bytes)) {
            switch (format) {
                case DOCX -> word(input, collector);
                case XLS, XLSX -> excel(input, collector);
                case PPTX -> { try (var deck = new XMLSlideShow(input)) { slides(deck, collector); } }
                case PPT -> { try (var deck = new HSLFSlideShow(input)) {
                    for (var record : deck.getMostRecentCoreRecords()) rejectPowerpointMacros(record);
                    slides(deck, collector);
                } }
                default -> throw new ParseFailure(ParseCode.UNSUPPORTED);
            }
        }
    }
    private static void word(InputStream input, TextCollector collector) throws IOException {
        try (var document = new XWPFDocument(input)) {
            int index = 0;
            for (IBodyElement element : document.getBodyElements()) {
                try (var writer = collector.section(SourceLocation.section("正文段落" + ++index))) {
                    body(element, writer, collector);
                }
            }
        }
    }
    private static void body(IBodyElement element, TextCollector.SectionWriter writer, TextCollector collector) {
        if (element instanceof XWPFParagraph paragraph) { writer.add(paragraph.getText()); writer.add("\n"); }
        else if (element instanceof XWPFTable table) {
            for (var row : table.getRows()) {
                for (var cell : row.getTableCells()) {
                    collector.cell();
                    for (var nested : cell.getBodyElements()) body(nested, writer, collector);
                    writer.add("\t");
                }
                writer.add("\n");
            }
        }
    }
    private static void excel(InputStream input, TextCollector collector) throws IOException {
        try (var book = WorkbookFactory.create(input)) {
            ParseFailure.limit(book.getNumberOfSheets() > collector.options.maxPages());
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (Sheet sheet : book) {
                try (var writer = collector.section(SourceLocation.sheet(sheet.getSheetName()))) {
                    for (Row row : sheet) for (Cell cell : row) {
                        collector.cell();
                        if (cell.getCellType() == CellType.BLANK) continue;
                        String value;
                        if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell xmlCell
                                && cell.getCellType() == CellType.FORMULA && !xmlCell.getCTCell().isSetV())
                            value = "[未计算公式]";
                        else value = formatter.formatCellValue(cell);
                        if (TextNormalizer.normalize(value).isEmpty()) continue;
                        writer.add(new CellReference(row.getRowNum(), cell.getColumnIndex()).formatAsString());
                        writer.add(": "); writer.add(value);
                        writer.add("\n");
                    }
                }
            }
        }
    }
    private static void rejectPowerpointMacros(org.apache.poi.hslf.record.Record record) {
        if (record instanceof org.apache.poi.hslf.record.VBAInfoAtom atom && atom.isHasMacros())
            throw new ParseFailure(ParseCode.UNSUPPORTED);
        var children = record.getChildRecords();
        if (children != null) for (var child : children) rejectPowerpointMacros(child);
    }
    private static <S extends Shape<S,P>, P extends TextParagraph<S,P,? extends TextRun>>
    void slides(SlideShow<S,P> deck, TextCollector collector) {
        ParseFailure.limit(deck.getSlides().size() > collector.options.maxPages());
        int number = 0;
        for (var slide : deck.getSlides()) {
            try (var writer = collector.section(SourceLocation.slide(++number))) {
                for (var shape : slide.getShapes()) shape(shape, writer, collector);
            }
        }
    }
    private static void shape(Shape<?,?> shape, TextCollector.SectionWriter writer, TextCollector collector) {
        if (shape instanceof TableShape<?,?> table) {
            for (int row = 0; row < table.getNumberOfRows(); row++) {
                for (int col = 0; col < table.getNumberOfColumns(); col++) {
                    collector.cell(); var cell = table.getCell(row, col);
                    if (cell != null) writer.add(cell.getText()); writer.add("\t");
                }
                writer.add("\n");
            }
        } else if (shape instanceof TextShape<?,?> text) { writer.add(text.getText()); writer.add("\n"); }
        else if (shape instanceof GroupShape<?,?> group) for (var child : group.getShapes()) shape(child, writer, collector);
    }
}
