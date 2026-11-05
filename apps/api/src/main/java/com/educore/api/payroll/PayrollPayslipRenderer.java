package com.educore.api.payroll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

@Component
final class PayrollPayslipRenderer {
    private static final String LATIN_FONT = "/fonts/NotoSans-Variable.ttf";
    private static final String DEVANAGARI_FONT = "/fonts/NotoSansDevanagariUI-Regular.ttf";
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 42;

    RenderedPdf render(PayslipEvidence evidence) {
        try (PDDocument document = new PDDocument();
                InputStream latinStream = PayrollPayslipRenderer.class.getResourceAsStream(LATIN_FONT);
                InputStream devanagariStream = PayrollPayslipRenderer.class.getResourceAsStream(DEVANAGARI_FONT)) {
            if (latinStream == null || devanagariStream == null) {
                throw new IllegalStateException("Payroll payslip fonts are unavailable");
            }
            PDType0Font latin = PDType0Font.load(document, latinStream);
            PDType0Font devanagari = PDType0Font.load(document, devanagariStream);
            document.getDocumentCatalog().setLanguage(evidence.locale());
            configureMetadata(document, evidence);
            try (Canvas canvas = new Canvas(document, latin, devanagari, evidence)) {
                canvas.startFirstPage();
                drawIdentity(canvas, evidence);
                drawComponents(canvas, evidence);
                drawTotals(canvas, evidence);
                drawAttendance(canvas, evidence);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            byte[] bytes = output.toByteArray();
            if (bytes.length < 1_000 || bytes.length > 10_485_760) {
                throw new IllegalStateException("Generated payroll payslip size is invalid");
            }
            return new RenderedPdf(bytes, sha256(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render the private payroll payslip", exception);
        }
    }

    private static void configureMetadata(PDDocument document, PayslipEvidence evidence) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(label(evidence.locale(), "Payslip", "वेतन पर्ची")
                + " " + evidence.payslipNumber());
        information.setAuthor(evidence.school().name());
        information.setSubject(evidence.employee().employeeCode() + " · "
                + evidence.payroll().payrollMonth());
        information.setCreator("EduCore private payroll document service");
        information.setCreationDate(GregorianCalendar.from(
                evidence.generatedAt().atZone(ZoneOffset.UTC)));
        document.setDocumentInformation(information);
    }

    private static void drawIdentity(Canvas canvas, PayslipEvidence evidence) throws IOException {
        canvas.heading(label(evidence.locale(), "Employee and payroll details", "कर्मचारी और वेतन विवरण"));
        canvas.detailRow(
                label(evidence.locale(), "Employee", "कर्मचारी"), evidence.employee().name(),
                label(evidence.locale(), "Employee code", "कर्मचारी कोड"),
                evidence.employee().employeeCode());
        canvas.detailRow(
                label(evidence.locale(), "Payroll month", "वेतन माह"),
                month(evidence.payroll().payrollMonth(), evidence.locale()),
                label(evidence.locale(), "Run reference", "रन संदर्भ"), evidence.payroll().runCode());
        canvas.detailRow(
                label(evidence.locale(), "Payslip number", "वेतन पर्ची संख्या"),
                evidence.payslipNumber(),
                label(evidence.locale(), "Currency", "मुद्रा"), evidence.payroll().currencyCode());
        canvas.move(14);
    }

    private static void drawComponents(Canvas canvas, PayslipEvidence evidence) throws IOException {
        canvas.heading(label(evidence.locale(), "Frozen salary components", "सुरक्षित वेतन घटक"));
        canvas.tableHeader(
                label(evidence.locale(), "Component", "घटक"),
                label(evidence.locale(), "Type", "प्रकार"),
                label(evidence.locale(), "Configured", "निर्धारित"),
                label(evidence.locale(), "Payable", "देय"));
        for (LineEvidence line : evidence.lines()) {
            canvas.ensure(25);
            canvas.tableRow(line.name(), kind(line.kind(), evidence.locale()),
                    money(evidence.payroll().currencyCode(), line.fullAmount()),
                    money(evidence.payroll().currencyCode(), line.payableAmount()));
        }
        canvas.move(15);
    }

    private static void drawTotals(Canvas canvas, PayslipEvidence evidence) throws IOException {
        canvas.ensure(128);
        canvas.heading(label(evidence.locale(), "Payroll totals", "वेतन योग"));
        canvas.totalRow(label(evidence.locale(), "Payable gross", "कुल देय"),
                money(evidence.payroll().currencyCode(), evidence.amounts().payableGross()));
        canvas.totalRow(label(evidence.locale(), "Deductions", "कटौतियां"),
                money(evidence.payroll().currencyCode(), evidence.amounts().deductions()));
        canvas.totalRow(label(evidence.locale(), "Net salary", "शुद्ध वेतन"),
                money(evidence.payroll().currencyCode(), evidence.amounts().net()));
        if (evidence.amounts().employerContributions().signum() > 0) {
            canvas.note(label(evidence.locale(), "Employer contributions (not deducted from net): ",
                    "नियोक्ता अंशदान (शुद्ध वेतन से कटौती नहीं): ")
                    + money(evidence.payroll().currencyCode(),
                            evidence.amounts().employerContributions()));
        }
        canvas.move(14);
    }

    private static void drawAttendance(Canvas canvas, PayslipEvidence evidence) throws IOException {
        canvas.ensure(105);
        canvas.heading(label(evidence.locale(), "Attendance basis", "उपस्थिति आधार"));
        canvas.detailRow(
                label(evidence.locale(), "Scheduled days", "निर्धारित दिन"),
                Integer.toString(evidence.attendance().scheduledWorkingDays()),
                label(evidence.locale(), "Recorded days", "दर्ज दिन"),
                Integer.toString(evidence.attendance().recordedDays()));
        canvas.detailRow(
                label(evidence.locale(), "Payable days", "देय दिन"),
                evidence.attendance().payableDays().stripTrailingZeros().toPlainString(),
                label(evidence.locale(), "Paid / unpaid leave", "सवेतन / अवैतनिक अवकाश"),
                evidence.attendance().paidLeaveDays().stripTrailingZeros().toPlainString()
                        + " / " + evidence.attendance().unpaidLeaveDays().stripTrailingZeros().toPlainString());
        canvas.note(label(evidence.locale(),
                "Private employment record generated only from independently finalized payroll evidence.",
                "यह निजी रोजगार अभिलेख केवल स्वतंत्र रूप से अंतिम किए गए वेतन साक्ष्य से बना है।"));
    }

    private static String label(String locale, String english, String hindi) {
        return "hi-IN".equals(locale) ? hindi : english;
    }

    private static String kind(String value, String locale) {
        return switch (value) {
            case "EARNING" -> label(locale, "Earning", "आय");
            case "DEDUCTION" -> label(locale, "Deduction", "कटौती");
            default -> label(locale, "Employer contribution", "नियोक्ता अंशदान");
        };
    }

    private static String month(LocalDate value, String locale) {
        String pattern = "hi-IN".equals(locale) ? "MMMM uuuu" : "MMMM uuuu";
        return DateTimeFormatter.ofPattern(pattern,
                "hi-IN".equals(locale) ? java.util.Locale.forLanguageTag("hi-IN")
                        : java.util.Locale.forLanguageTag("en-IN")).format(value);
    }

    private static String money(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2).toPlainString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record SchoolEvidence(String name, String address, String contact) {}
    record PayrollEvidence(String runCode, LocalDate payrollMonth, String currencyCode) {}
    record EmployeeEvidence(String employeeCode, String name) {}
    record AttendanceEvidence(int scheduledWorkingDays, int recordedDays, BigDecimal payableDays,
            BigDecimal paidLeaveDays, BigDecimal unpaidLeaveDays) {}
    record AmountEvidence(BigDecimal payableGross, BigDecimal deductions,
            BigDecimal employerContributions, BigDecimal net) {}
    record LineEvidence(String code, String name, String kind, boolean statutory,
            BigDecimal fullAmount, BigDecimal payableAmount) {}
    record PayslipEvidence(int schemaVersion, String payslipNumber, String locale,
            java.util.UUID payrollRunId, java.util.UUID staffProfileId,
            SchoolEvidence school, PayrollEvidence payroll, EmployeeEvidence employee,
            AttendanceEvidence attendance, AmountEvidence amounts, List<LineEvidence> lines,
            java.time.Instant generatedAt) {
        PayslipEvidence { lines = List.copyOf(lines); }
    }
    record RenderedPdf(byte[] bytes, String sha256) {
        RenderedPdf { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private static final class Canvas implements AutoCloseable {
        private final PDDocument document;
        private final PDFont latin;
        private final PDFont devanagari;
        private final PayslipEvidence evidence;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        Canvas(PDDocument document, PDFont latin, PDFont devanagari, PayslipEvidence evidence) {
            this.document = document;
            this.latin = latin;
            this.devanagari = devanagari;
            this.evidence = evidence;
        }

        void startFirstPage() throws IOException {
            startPage(false);
            fill(45, 61, 52, MARGIN, HEIGHT - 142, WIDTH - (MARGIN * 2), 100);
            text(evidence.school().name(), 21, MARGIN + 18, HEIGHT - 76, 420, 255, 250, 240);
            if (!evidence.school().address().isBlank())
                text(evidence.school().address(), 8, MARGIN + 18, HEIGHT - 99, 410, 233, 226, 214);
            if (!evidence.school().contact().isBlank())
                text(evidence.school().contact(), 8, MARGIN + 18, HEIGHT - 116, 410, 233, 226, 214);
            rightText(label(evidence.locale(), "PAYSLIP", "वेतन पर्ची"), 16,
                    WIDTH - MARGIN - 18, HEIGHT - 80, 190, 218, 179, 92);
            rightText(evidence.payslipNumber(), 8, WIDTH - MARGIN - 18, HEIGHT - 107,
                    190, 246, 239, 224);
            y = HEIGHT - 170;
        }

        private void startPage(boolean continuation) throws IOException {
            finishPage();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            pageNumber++;
            y = HEIGHT - MARGIN;
            if (continuation) {
                fill(45, 61, 52, MARGIN, HEIGHT - 82, WIDTH - (MARGIN * 2), 40);
                text(evidence.school().name(), 11, MARGIN + 14, HEIGHT - 59, 310, 255, 250, 240);
                rightText(evidence.payslipNumber() + " · "
                                + label(evidence.locale(), "continued", "जारी"),
                        8, WIDTH - MARGIN - 14, HEIGHT - 59, 210, 246, 239, 224);
                y = HEIGHT - 110;
            }
        }

        void ensure(float height) throws IOException {
            if (y - height < 72) startPage(true);
        }

        void heading(String value) throws IOException {
            ensure(28);
            text(value, 12, MARGIN, y, WIDTH - (MARGIN * 2), 77, 56, 40);
            y -= 19;
            rule(206, 190, 160, y);
            y -= 14;
        }

        void detailRow(String leftLabel, String leftValue, String rightLabel, String rightValue)
                throws IOException {
            ensure(39);
            float half = (WIDTH - (MARGIN * 2) - 18) / 2;
            text(leftLabel, 7, MARGIN, y, half, 110, 101, 88);
            text(rightLabel, 7, MARGIN + half + 18, y, half, 110, 101, 88);
            y -= 14;
            text(leftValue, 10, MARGIN, y, half, 42, 37, 31);
            text(rightValue, 10, MARGIN + half + 18, y, half, 42, 37, 31);
            y -= 22;
        }

        void tableHeader(String component, String type, String configured, String payable)
                throws IOException {
            ensure(26);
            fill(237, 229, 210, MARGIN, y - 18, WIDTH - (MARGIN * 2), 25);
            text(component, 8, MARGIN + 8, y - 8, 210, 77, 56, 40);
            text(type, 8, MARGIN + 226, y - 8, 105, 77, 56, 40);
            rightText(configured, 8, WIDTH - MARGIN - 104, y - 8, 96, 77, 56, 40);
            rightText(payable, 8, WIDTH - MARGIN - 8, y - 8, 86, 77, 56, 40);
            y -= 28;
        }

        void tableRow(String component, String type, String configured, String payable)
                throws IOException {
            text(component, 8, MARGIN + 8, y, 210, 45, 42, 36);
            text(type, 8, MARGIN + 226, y, 105, 92, 82, 67);
            rightText(configured, 8, WIDTH - MARGIN - 104, y, 96, 45, 42, 36);
            rightText(payable, 8, WIDTH - MARGIN - 8, y, 86, 45, 42, 36);
            y -= 17;
            rule(230, 222, 207, y);
            y -= 7;
        }

        void totalRow(String name, String value) throws IOException {
            ensure(24);
            text(name, 9, MARGIN + 8, y, 280, 66, 58, 47);
            rightText(value, 10, WIDTH - MARGIN - 8, y, 180, 66, 58, 47);
            y -= 24;
        }

        void note(String value) throws IOException {
            ensure(32);
            fill(247, 242, 232, MARGIN, y - 24, WIDTH - (MARGIN * 2), 31);
            text(value, 7.5f, MARGIN + 9, y - 10, WIDTH - (MARGIN * 2) - 18, 92, 82, 67);
            y -= 39;
        }

        void move(float amount) { y -= amount; }

        private void fill(int r, int g, int b, float x, float y, float width, float height)
                throws IOException {
            stream.setNonStrokingColor(r / 255f, g / 255f, b / 255f);
            stream.addRect(x, y, width, height);
            stream.fill();
        }

        private void rule(int r, int g, int b, float yValue) throws IOException {
            stream.setStrokingColor(r / 255f, g / 255f, b / 255f);
            stream.setLineWidth(0.6f);
            stream.moveTo(MARGIN, yValue);
            stream.lineTo(WIDTH - MARGIN, yValue);
            stream.stroke();
        }

        private void text(String value, float size, float x, float yValue, float maxWidth,
                int r, int g, int b) throws IOException {
            String safe = fit(value, size, maxWidth);
            stream.beginText();
            stream.setNonStrokingColor(r / 255f, g / 255f, b / 255f);
            stream.newLineAtOffset(x, yValue);
            writeMixed(safe, size);
            stream.endText();
        }

        private void rightText(String value, float size, float right, float yValue, float maxWidth,
                int r, int g, int b) throws IOException {
            String safe = fit(value, size, maxWidth);
            float width = width(safe, size);
            text(safe, size, right - width, yValue, maxWidth, r, g, b);
        }

        private void writeMixed(String value, float size) throws IOException {
            StringBuilder run = new StringBuilder();
            PDFont active = null;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                PDFont next = devanagari(codePoint) ? devanagari : latin;
                if (active != null && active != next) {
                    stream.setFont(active, size);
                    stream.showText(run.toString());
                    run.setLength(0);
                }
                active = next;
                run.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }
            if (!run.isEmpty()) {
                stream.setFont(active == null ? latin : active, size);
                stream.showText(run.toString());
            }
        }

        private String fit(String raw, float size, float maxWidth) throws IOException {
            String value = raw == null || raw.isBlank() ? "—" : raw.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (width(value, size) <= maxWidth) return value;
            String suffix = "…";
            int end = value.length();
            while (end > 1 && width(value.substring(0, end) + suffix, size) > maxWidth) {
                end = value.offsetByCodePoints(end, -1);
            }
            return value.substring(0, Math.max(1, end)) + suffix;
        }

        private float width(String value, float size) throws IOException {
            float result = 0;
            StringBuilder run = new StringBuilder();
            PDFont active = null;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                PDFont next = devanagari(codePoint) ? devanagari : latin;
                if (active != null && active != next) {
                    result += active.getStringWidth(run.toString()) / 1000 * size;
                    run.setLength(0);
                }
                active = next;
                run.appendCodePoint(codePoint);
                offset += Character.charCount(codePoint);
            }
            if (!run.isEmpty()) result += active.getStringWidth(run.toString()) / 1000 * size;
            return result;
        }

        private static boolean devanagari(int codePoint) {
            return codePoint >= 0x0900 && codePoint <= 0x097f;
        }

        private void finishPage() throws IOException {
            if (stream == null) return;
            stream.setStrokingColor(206 / 255f, 190 / 255f, 160 / 255f);
            stream.setLineWidth(0.6f);
            stream.moveTo(MARGIN, 48);
            stream.lineTo(WIDTH - MARGIN, 48);
            stream.stroke();
            text(label(evidence.locale(), "Private payroll record · do not share publicly",
                    "निजी वेतन अभिलेख · सार्वजनिक रूप से साझा न करें"),
                    7, MARGIN, 32, 390, 110, 101, 88);
            rightText(label(evidence.locale(), "Page ", "पृष्ठ ") + pageNumber,
                    7, WIDTH - MARGIN, 32, 80, 110, 101, 88);
            stream.close();
            stream = null;
        }

        @Override
        public void close() throws IOException { finishPage(); }
    }
}
