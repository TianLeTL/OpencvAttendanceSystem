package com.smartattendance.service;

import com.smartattendance.entity.AttendanceRecord;
import com.smartattendance.entity.Session;
import com.smartattendance.service.SessionService;
import com.smartattendance.service.SessionService.RosterEntryDetail;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class SessionExportService {

    private final SessionService sessionService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    public SessionExportService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public enum ExportFormat {
        CSV, XLSX, PDF;

        public static ExportFormat fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Format is required");
            }
            return switch (value.trim().toLowerCase()) {
                case "csv" -> CSV;
                case "xlsx", "xls" -> XLSX;
                case "pdf" -> PDF;
                default -> throw new IllegalArgumentException("Unsupported export format: " + value);
            };
        }
    }

    public byte[] export(Integer sessionId, ExportFormat format) {
        ExportContext context = buildContext(sessionId);
        return switch (format) {
            case CSV -> exportCsv(context);
            case XLSX -> exportXlsx(context);
            case PDF -> exportPdf(context);
        };
    }

    private ExportContext buildContext(Integer sessionId) {
        Session session = sessionService.getSessionWithSection(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        List<RosterEntryDetail> roster = sessionService.getRosterWithAttendance(sessionId);
        return new ExportContext(session, roster);
    }

    private byte[] exportCsv(ExportContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session:,").append(escapeCsv(context.sessionTitle)).append('\n');
        sb.append("Course Name:,").append(escapeCsv(context.courseName)).append('\n');
        sb.append("Course Date:,").append(escapeCsv(context.sessionDate)).append('\n');
        sb.append("Course Timing:,").append(escapeCsv(context.sessionTiming)).append('\n');
        sb.append('\n');
        sb.append("Student ID,Student Name,Status,Timestamp,Confidence,Method,Notes\n");
        for (ExportRow row : context.rows) {
            sb.append(escapeCsv(row.studentId()));
            sb.append(',').append(escapeCsv(row.studentName()));
            sb.append(',').append(escapeCsv(row.status()));
            sb.append(',').append(escapeCsv(row.timestamp()));
            sb.append(',').append(escapeCsv(row.confidence()));
            sb.append(',').append(escapeCsv(row.method()));
            sb.append(',').append(escapeCsv(row.notes()));
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] exportXlsx(ExportContext context) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Session");

            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("Session:");
            row0.createCell(1).setCellValue(context.sessionTitle);

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Course Name:");
            row1.createCell(1).setCellValue(context.courseName);

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Course Date:");
            row2.createCell(1).setCellValue(context.sessionDate);

            Row row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("Course Timing:");
            row3.createCell(1).setCellValue(context.sessionTiming);

            int headerRowIndex = 5;
            String[] headers = {"Student ID", "Student Name", "Status", "Timestamp", "Confidence", "Method", "Notes"};
            Row headerRow = sheet.createRow(headerRowIndex);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowIndex = headerRowIndex + 1;
            for (ExportRow row : context.rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                int col = 0;
                dataRow.createCell(col++).setCellValue(row.studentId());
                dataRow.createCell(col++).setCellValue(row.studentName());
                dataRow.createCell(col++).setCellValue(row.status());
                dataRow.createCell(col++).setCellValue(row.timestamp());
                dataRow.createCell(col++).setCellValue(row.confidence());
                dataRow.createCell(col++).setCellValue(row.method());
                dataRow.createCell(col).setCellValue(row.notes());
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            sheet.autoSizeColumn(1);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate XLSX export", e);
        }
    }

    private byte[] exportPdf(ExportContext context) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

            document.add(new Paragraph("Session: " + context.sessionTitle, subFont));
            document.add(new Paragraph("Course Name: " + context.courseName, subFont));
            document.add(new Paragraph("Course Date: " + context.sessionDate, subFont));
            document.add(new Paragraph("Course Timing: " + context.sessionTiming, subFont));
            document.add(new Paragraph(" ", subFont));

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{2.0f, 3.0f, 2.0f, 2.4f, 1.4f, 1.6f, 3.0f});

            String[] headers = {"Student ID", "Student Name", "Status", "Timestamp", "Confidence", "Method", "Notes"};

            for (String header : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(6);
                table.addCell(cell);
            }

            for (ExportRow row : context.rows) {
                table.addCell(createCell(row.studentId(), cellFont));
                table.addCell(createCell(row.studentName(), cellFont));
                table.addCell(createCell(row.status(), cellFont));
                table.addCell(createCell(row.timestamp(), cellFont));
                table.addCell(createCell(row.confidence(), cellFont));
                table.addCell(createCell(row.method(), cellFont));
                table.addCell(createCell(row.notes(), cellFont));
            }

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new RuntimeException("Failed to generate PDF export", e);
        }
    }

    private PdfPCell createCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setPadding(5);
        return cell;
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String formatDate(LocalDate date) {
        return date != null ? DATE_FORMATTER.format(date) : "";
    }

    private String formatTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return "";
        }
        return TIME_FORMATTER.format(start) + " - " + TIME_FORMATTER.format(end);
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        return timestamp != null ? TIMESTAMP_FORMATTER.format(timestamp) : "";
    }

    private String formatConfidence(BigDecimal score) {
        if (score == null) {
            return "";
        }
        return score.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String toTitleCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private final class ExportContext {
        private final Session session;
        private final List<ExportRow> rows;
        private final String sessionTitle;
        private final String courseName;
        private final String sessionDate;
        private final String sessionTiming;

        private ExportContext(Session session, List<RosterEntryDetail> roster) {
            this.session = session;
            this.courseName = session.getSection().getCourse().getCourseName();
            this.sessionDate = formatDate(session.getSessionDate());
            this.sessionTiming = formatTimeRange(session.getStartTime(), session.getEndTime());
            this.sessionTitle = session.getSection().getCourse().getCourseCode() + " • " + session.getSection().getSectionCode();
            this.rows = buildRows(session, roster);
        }

        private List<ExportRow> buildRows(Session session, List<RosterEntryDetail> roster) {
            List<ExportRow> data = new ArrayList<>();

            for (RosterEntryDetail entry : roster) {
                AttendanceRecord record = entry.attendanceRecord();
                String status = record != null ? toTitleCase(record.getStatus().name()) : "Not Marked";
                String timestamp = record != null ? formatTimestamp(record.getMarkedAt()) : "";
                String confidence = record != null ? formatConfidence(record.getConfidenceScore()) : "";
                String method = record != null && record.getMarkingMethod() != null
                        ? toTitleCase(record.getMarkingMethod().name())
                        : "Not Marked";
                String notes = record != null && record.getNotes() != null ? record.getNotes() : "";

                data.add(new ExportRow(
                        entry.rosterEntry().getStudent().getStudentId(),
                        entry.rosterEntry().getStudent().getFullName(),
                        status,
                        timestamp,
                        confidence,
                        method,
                        notes
                ));
            }

            return data;
        }
    }

    private record ExportRow(
            String studentId,
            String studentName,
            String status,
            String timestamp,
            String confidence,
            String method,
            String notes
    ) {}
}
