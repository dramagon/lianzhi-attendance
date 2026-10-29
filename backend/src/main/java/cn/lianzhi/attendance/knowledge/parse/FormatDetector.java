package cn.lianzhi.attendance.knowledge.parse;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.hssf.record.FilePassRecord;
import org.apache.poi.hssf.record.RecordInputStream;
import org.apache.poi.util.LittleEndian;

final class FormatDetector {
    private FormatDetector() {}
    static DocumentFormat detect(byte[] bytes, ParseOptions options) throws IOException {
        if (starts(bytes, new byte[]{'%', 'P', 'D', 'F', '-'})) return DocumentFormat.PDF;
        if (starts(bytes, new byte[]{(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, (byte)0xA1, (byte)0xB1, 0x1A, (byte)0xE1})) {
            try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                var root = fs.getRoot();
                if (root.hasEntry("EncryptionInfo") || root.hasEntry("EncryptedPackage")) throw new ParseFailure(ParseCode.ENCRYPTED);
                rejectMacros(root);
                boolean workbook = root.hasEntry("Workbook") || root.hasEntry("Book");
                boolean presentation = root.hasEntry("PowerPoint Document");
                boolean word = root.hasEntry("WordDocument");
                if ((workbook ? 1 : 0) + (presentation ? 1 : 0) + (word ? 1 : 0) > 1)
                    throw new IOException("Ambiguous OLE main streams");
                if (workbook) {
                    try (var input = fs.createDocumentInputStream(root.hasEntry("Workbook") ? "Workbook" : "Book")) {
                        rejectWorkbookMacros(input);
                    }
                    return DocumentFormat.XLS;
                }
                if (presentation) return DocumentFormat.PPT;
                if (word) return DocumentFormat.DOC;
                return DocumentFormat.UNKNOWN;
            }
        }
        if (!starts(bytes, new byte[]{'P', 'K', 3, 4})) return DocumentFormat.UNKNOWN;
        preflightZip(bytes, options);
        try (var pack = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            DocumentFormat found = DocumentFormat.UNKNOWN;
            for (PackagePart part : pack.getParts()) {
                String type = part.getContentType();
                String normalizedType = type.toLowerCase(Locale.ROOT);
                if (normalizedType.contains("macroenabled") || normalizedType.contains("vbaproject")
                        || normalizedType.contains("macrosheet"))
                    throw new ParseFailure(ParseCode.UNSUPPORTED);
                DocumentFormat candidate = switch (type) {
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml" -> DocumentFormat.DOCX;
                    case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml" -> DocumentFormat.XLSX;
                    case "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml" -> DocumentFormat.PPTX;
                    default -> DocumentFormat.UNKNOWN;
                };
                if (candidate != DocumentFormat.UNKNOWN) {
                    if (found != DocumentFormat.UNKNOWN) throw new IOException("Ambiguous main parts");
                    found = candidate;
                }
            }
            if (found == DocumentFormat.UNKNOWN) throw new IOException("No supported main part");
            return found;
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) { throw new IOException(e); }
    }
    private static boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
    private static void rejectWorkbookMacros(InputStream input) throws IOException {
        var records = new RecordInputStream(input);
        while (records.hasNextRecord()) {
            records.nextRecord();
            int sid = records.getSid();
            byte[] payload = records.readRemainder();
            if (sid == FilePassRecord.sid) throw new ParseFailure(ParseCode.ENCRYPTED);
            if (sid == BoundSheetRecord.sid) {
                if (payload.length < 6) throw new IOException("Truncated BoundSheet record");
                // [MS-XLS] BoundSheet8: lbPlyPos(4), hsState(1), dt(1).
                // Both Excel 4.0 macro sheets and Visual Basic module sheets are unsupported.
                int sheetType = Byte.toUnsignedInt(payload[5]);
                if (sheetType == 1 || sheetType == 6) throw new ParseFailure(ParseCode.UNSUPPORTED);
            }
            if (sid == BOFRecord.sid) {
                if (payload.length < 4) throw new IOException("Truncated BOF record");
                int type = LittleEndian.getUShort(payload, 2);
                if (type == BOFRecord.TYPE_EXCEL_4_MACRO || type == BOFRecord.TYPE_VB_MODULE)
                    throw new ParseFailure(ParseCode.UNSUPPORTED);
            }
        }
    }
    private static void rejectMacros(DirectoryEntry directory) {
        for (Entry entry : directory) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (name.equals("vba") || name.equals("_vba_project_cur") || name.equals("_vba_project"))
                throw new ParseFailure(ParseCode.UNSUPPORTED);
            if (entry instanceof DirectoryEntry nested) rejectMacros(nested);
        }
    }
    private static void preflightZip(byte[] bytes, ParseOptions options) throws IOException {
        int entries = 0; long total = 0; Set<String> names = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                ParseFailure.limit(++entries > options.maxZipEntries());
                if (!names.add(entry.getName())) throw new IOException("Duplicate ZIP entry");
                long expanded = 0; int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count; total += count;
                    ParseFailure.limit(expanded > options.maxZipEntryBytes() || total > options.maxZipTotalBytes());
                }
            }
            if (entries == 0) throw new IOException("Empty ZIP");
        }
    }
}
