package com.educore.api.documents;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class OfficialDocumentRenderer {
    private static final String LATIN_FONT_RESOURCE = "/fonts/NotoSans-Variable.ttf";
    private static final String DEVANAGARI_FONT_RESOURCE = "/fonts/NotoSansDevanagariUI-Regular.ttf";
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 44;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2);

    private final ObjectMapper objectMapper;

    OfficialDocumentRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    RenderedPdf render(
            String documentKind,
            String templateJson,
            String sourceJson,
            String snapshotSha256,
            String verificationUrl,
            Instant issuedAt,
            String approvingActor) {
        try {
            JsonNode template = objectMapper.readTree(templateJson);
            JsonNode source = objectMapper.readTree(sourceJson);
            try (PDDocument document = new PDDocument();
                    InputStream latinStream = OfficialDocumentRenderer.class.getResourceAsStream(LATIN_FONT_RESOURCE);
                    InputStream devanagariStream = OfficialDocumentRenderer.class.getResourceAsStream(DEVANAGARI_FONT_RESOURCE)) {
                if (latinStream == null || devanagariStream == null) {
                    throw new IllegalStateException("Official document fonts are unavailable");
                }
                PDType0Font latinFont = PDType0Font.load(document, latinStream);
                PDType0Font devanagariFont = PDType0Font.load(document, devanagariStream);
                document.getDocumentCatalog().setLanguage(text(template, "locale", "en-IN"));
                configureMetadata(document, template, source, issuedAt);
                Colour accent = Colour.parse(text(template, "accentColour", "#23483F"));
                try (Canvas canvas = new Canvas(document, latinFont, devanagariFont, accent)) {
                    canvas.startPage();
                    drawHeading(canvas, template, source);
                    if ("REPORT_CARD".equals(documentKind)) drawReportCard(canvas, template, source);
                    else if ("FEE_RECEIPT".equals(documentKind)) drawFeeReceipt(canvas, template, source);
                    else if ("BONAFIDE_CERTIFICATE".equals(documentKind)) drawBonafideCertificate(canvas, template, source);
                    else throw new IllegalArgumentException("Unsupported official document kind");
                    drawVerification(canvas, template, source, snapshotSha256,
                            verificationUrl, issuedAt, approvingActor);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                document.save(output);
                byte[] bytes = output.toByteArray();
                if (bytes.length == 0 || bytes.length > 10_485_760) {
                    throw new IllegalStateException("Generated official document size is invalid");
                }
                return new RenderedPdf(bytes, sha256(bytes));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render the official PDF", exception);
        }
    }

    private static void configureMetadata(
            PDDocument document, JsonNode template, JsonNode source, Instant issuedAt) {
        PDDocumentInformation information = new PDDocumentInformation();
        information.setTitle(text(template, "title", "Official document"));
        information.setAuthor(source.path("school").path("name").asText("School"));
        information.setSubject(text(source, "documentNumber", "Official school evidence"));
        information.setCreator("EduCore controlled document factory");
        information.setCreationDate(GregorianCalendar.from(issuedAt.atZone(ZoneOffset.UTC)));
        document.setDocumentInformation(information);
    }

    private static void drawHeading(Canvas canvas, JsonNode template, JsonNode source) throws IOException {
        JsonNode school = source.path("school");
        canvas.fill(canvas.accent(), MARGIN, PAGE_HEIGHT - 148, CONTENT_WIDTH, 104);
        canvas.text(school.path("name").asText("School"), 22, MARGIN + 22, PAGE_HEIGHT - 82, Colour.WHITE);
        String address = school.path("address").asText("");
        if (!address.isBlank()) canvas.text(address, 9, MARGIN + 22, PAGE_HEIGHT - 103, Colour.SOFT_WHITE);
        String contact = school.path("contact").asText("");
        if (!contact.isBlank()) canvas.text(contact, 8, MARGIN + 22, PAGE_HEIGHT - 119, Colour.SOFT_WHITE);
        canvas.y(PAGE_HEIGHT - 174);
        canvas.text(text(template, "title", "Official document"), 25, MARGIN, canvas.y(), canvas.accent());
        canvas.moveY(29);
        String subtitle = text(template, "subtitle", "");
        if (!subtitle.isBlank()) {
            canvas.paragraph(subtitle, 10, Colour.MUTED, 15);
            canvas.moveY(4);
        }
        canvas.rule(canvas.accent().lighter(), canvas.y());
        canvas.moveY(20);
    }

    private static void drawReportCard(Canvas canvas, JsonNode template, JsonNode source) throws IOException {
        JsonNode learner = source.path("learner");
        JsonNode academic = source.path("academic");
        JsonNode report = source.path("report");
        String locale = text(template, "locale", "en-IN");
        canvas.twoColumnDetails(List.of(
                pair(label(template, "learner", locale, "Learner", "विद्यार्थी"), learner.path("name").asText("—")),
                pair(label(template, "reportNumber", locale, "Report number", "रिपोर्ट संख्या"),
                        source.path("documentNumber").asText("—")),
                pair(label(template, "classSection", locale, "Class / section", "कक्षा / सेक्शन"),
                        academic.path("section").asText("—")),
                pair(label(template, "term", locale, "Term", "सत्रावधि"), academic.path("term").asText("—")),
                pair(label(template, "session", locale, "Academic session", "शैक्षणिक सत्र"),
                        academic.path("session").asText("—")),
                pair(label(template, "rollNumber", locale, "Roll number", "अनुक्रमांक"),
                        blankFallback(learner.path("rollNumber").asText(""), "—"))));
        canvas.moveY(18);
        canvas.sectionTitle(label(template, "results", locale, "Result summary", "परिणाम सारांश"));
        canvas.tableHeader(List.of(
                new Cell(label(template, "subject", locale, "Subject", "विषय"), 0.46f),
                new Cell(label(template, "percentage", locale, "Percentage", "प्रतिशत"), 0.20f),
                new Cell(label(template, "grade", locale, "Grade", "ग्रेड"), 0.14f),
                new Cell(label(template, "outcome", locale, "Outcome", "परिणाम"), 0.20f)));
        JsonNode subjects = report.path("subjects");
        if (subjects.isArray()) {
            for (JsonNode subject : subjects) {
                canvas.ensure(40);
                canvas.tableRow(List.of(
                        new Cell(subject.path("name").asText("—"), 0.46f),
                        new Cell(decimal(subject.get("percentage")), 0.20f),
                        new Cell(blankFallback(subject.path("gradeCode").asText(""), "—"), 0.14f),
                        new Cell(human(subject.path("outcome").asText("—")), 0.20f)));
            }
        }
        canvas.moveY(18);
        canvas.summaryBand(List.of(
                pair(label(template, "overallPercentage", locale, "Overall percentage", "कुल प्रतिशत"),
                        decimal(report.get("overallPercentage"))),
                pair(label(template, "overallGrade", locale, "Overall grade", "कुल ग्रेड"),
                        blankFallback(report.path("overallGradeCode").asText(""), "—")),
                pair(label(template, "overallOutcome", locale, "Overall outcome", "समग्र परिणाम"),
                        human(report.path("overallOutcome").asText("—")))));
    }

    private static void drawFeeReceipt(Canvas canvas, JsonNode template, JsonNode source) throws IOException {
        JsonNode learner = source.path("learner");
        JsonNode payment = source.path("payment");
        String locale = text(template, "locale", "en-IN");
        List<Pair> details = new ArrayList<>(List.of(
                pair(label(template, "learner", locale, "Learner", "विद्यार्थी"), learner.path("name").asText("—")),
                pair(label(template, "receiptNumber", locale, "Receipt number", "रसीद संख्या"),
                        source.path("documentNumber").asText("—")),
                pair(label(template, "admissionNumber", locale, "Admission number", "प्रवेश संख्या"),
                        blankFallback(learner.path("admissionNumber").asText(""), "—")),
                pair(label(template, "paymentMethod", locale, "Payment method", "भुगतान विधि"),
                        human(payment.path("method").asText("—"))),
                pair(label(template, "paymentStatus", locale, "Source status", "स्रोत स्थिति"),
                        human(payment.path("status").asText("—")))));
        if (source.path("schemaVersion").asInt(1) >= 2) {
            // A receivedOn override belongs to the older snapshot's received date, never to a posting date.
            details.add(pair(label(template, "postingOn", locale, "Financial posting date", "वित्तीय प्रविष्टि तिथि"),
                    payment.path("postingOn").asText("—")));
        } else {
            // Frozen legacy requests still render their original recorded schema and label semantics.
            details.add(3, pair(label(template, "receivedOn", locale, "Received on", "प्राप्ति तिथि"),
                    payment.path("receivedOn").asText("—")));
        }
        canvas.twoColumnDetails(details);
        if (source.path("schemaVersion").asInt(1) >= 2) {
            canvas.paragraph(label(template, "originalReceivedOn", locale, "Original received date", "मूल प्राप्ति तिथि")
                    + ": " + payment.path("originalReceivedOn").asText("—"), 9, Colour.DARK, 14);
            // Wrap the full timestamp, offset and persisted zone; financial timing must never be ellipsized.
            canvas.paragraph(label(template, "actualReceivedAt", locale, "Actual funds received", "राशि मिलने का वास्तविक समय")
                    + ": " + receiptReceivedTime(payment, locale), 9, Colour.DARK, 14);
        }
        canvas.moveY(18);
        canvas.sectionTitle(label(template, "allocations", locale, "Payment allocation", "भुगतान आवंटन"));
        canvas.tableHeader(List.of(
                new Cell(label(template, "feeItem", locale, "Fee item", "शुल्क मद"), 0.72f),
                new Cell(label(template, "amount", locale, "Amount", "राशि"), 0.28f)));
        JsonNode allocations = source.path("allocations");
        if (allocations.isArray()) {
            for (JsonNode allocation : allocations) {
                canvas.ensure(40);
                canvas.tableRow(List.of(
                        new Cell(allocation.path("label").asText("—"), 0.72f),
                        new Cell(money(allocation.get("amount"), payment.path("currency").asText("INR")), 0.28f)));
            }
        }
        canvas.moveY(18);
        canvas.summaryBand(List.of(pair(
                label(template, "totalReceived", locale, "Total received", "कुल प्राप्त राशि"),
                money(payment.get("amount"), payment.path("currency").asText("INR")))));
    }

    private static String receiptReceivedTime(JsonNode payment, String locale) {
        boolean hindi = "hi-IN".equals(locale);
        if (!"INSTANT".equals(payment.path("receivedTimePrecision").asText())) {
            return hindi ? "समय दर्ज नहीं किया गया (केवल तारीख)" : "Time not recorded (date only)";
        }
        Instant actual = Instant.parse(payment.path("actualReceivedAt").asText());
        String zone = payment.path("schoolTimeZone").asText("");
        if (zone.isBlank()) {
            return actual + (hindi ? " (दर्ज समय क्षेत्र उपलब्ध नहीं है)" : " (recorded time zone unavailable)");
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(actual.atZone(ZoneId.of(zone))) + " [" + zone + "]";
    }

    private static void drawBonafideCertificate(Canvas canvas, JsonNode template, JsonNode source) throws IOException {
        String locale = text(template, "locale", "en-IN");
        boolean hindi = "hi-IN".equals(locale);
        JsonNode learner = source.path("learner");
        JsonNode academic = source.path("academic");
        JsonNode certificate = source.path("certificate");
        canvas.paragraph(label(template, "certificateNumber", locale, "Certificate number", "प्रमाणपत्र संख्या")
                + ": " + text(source, "documentNumber", "—"), 10, Colour.MUTED, 16);
        canvas.paragraph(label(template, "asOf", locale, "Record date", "अभिलेख तिथि")
                + ": " + text(certificate, "asOf", "—"), 10, Colour.MUTED, 16);
        canvas.moveY(18);
        canvas.sectionTitle(label(template, "enrolmentDetails", locale, "Enrolment details", "नामांकन विवरण"));
        // Official names, references and session labels must wrap, never truncate.
        canvas.paragraph(label(template, "learner", locale, "Learner", "विद्यार्थी")
                + ": " + text(learner, "name", "—"), 12, Colour.DARK, 20);
        canvas.paragraph(label(template, "admissionNumber", locale, "Admission number", "प्रवेश संख्या")
                + ": " + text(learner, "admissionNumber", "—"), 10, Colour.DARK, 17);
        canvas.paragraph(label(template, "grade", locale, "Class", "कक्षा")
                + ": " + text(academic, "grade", "—"), 10, Colour.DARK, 17);
        canvas.paragraph(label(template, "session", locale, "Academic session", "शैक्षणिक सत्र")
                + ": " + text(academic, "session", "—"), 10, Colour.DARK, 17);
        canvas.paragraph(label(template, "enrolledSince", locale, "Enrolled since", "नामांकन प्रारंभ")
                + ": " + text(certificate, "enrolledSince", "—"), 10, Colour.DARK, 17);
        canvas.moveY(22);
        canvas.paragraph(hindi
                ? "यह प्रमाणित किया जाता है कि उपर्युक्त विद्यार्थी इस विद्यालय में दर्शाए गए शैक्षणिक सत्र और कक्षा में नामांकित है। यह प्रमाणपत्र उल्लिखित अभिलेख तिथि की विद्यालयीय जानकारी पर आधारित है।"
                : "This certifies that the learner named above is enrolled at this school in the stated class and academic session, according to the school records on the record date shown above.",
                11, Colour.DARK, 18);
        canvas.moveY(16);
        canvas.paragraph(hindi
                ? "यह प्रमाणपत्र केवल नामांकन की पुष्टि करता है। यह स्थानांतरण, चरित्र या शुल्क भुगतान का प्रमाणपत्र नहीं है।"
                : "This certificate confirms enrolment only. It is not a transfer certificate, character certificate or fee-clearance certificate.",
                9, Colour.MUTED, 15);
    }

    private static void drawVerification(
            Canvas canvas,
            JsonNode template,
            JsonNode source,
            String snapshotSha256,
            String verificationUrl,
            Instant issuedAt,
            String approvingActor) throws IOException {
        canvas.ensure(155);
        canvas.moveY(22);
        float top = canvas.y();
        canvas.stroke(Colour.LINE, MARGIN, top - 126, CONTENT_WIDTH, 126);
        byte[] qr = qrCode(verificationUrl);
        PDImageXObject image = PDImageXObject.createFromByteArray(canvas.document(), qr, "verification-qr");
        canvas.image(image, MARGIN + 15, top - 111, 88, 88);
        float left = MARGIN + 122;
        String locale = text(template, "locale", "en-IN");
        canvas.text(label(template, "verification", locale, "Verify this document", "इस दस्तावेज़ की पुष्टि करें"),
                13, left, top - 27, canvas.accent());
        canvas.wrappedText(verificationUrl, 8, left, top - 46, CONTENT_WIDTH - 145, Colour.MUTED, 12);
        canvas.text(label(template, "issuedAt", locale, "Issued at", "जारी करने का समय") + ": " + issuedAt,
                8, left, top - 76, Colour.MUTED);
        canvas.text(label(template, "approvedBy", locale, "Approved electronically by", "इलेक्ट्रॉनिक अनुमोदन")
                        + ": " + approvingActor,
                8, left, top - 92, Colour.MUTED);
        canvas.text(text(template, "signatoryRole", "Authorized signatory"), 9, left, top - 110, Colour.DARK);
        canvas.y(top - 142);
        String footer = text(template, "footerText", "");
        if (!footer.isBlank()) canvas.paragraph(footer, 8, Colour.MUTED, 12);
        canvas.text("SHA-256: " + snapshotSha256,
                6.5f, MARGIN, 24, Colour.MUTED);
    }

    private static byte[] qrCode(String value) throws IOException {
        try {
            var matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 220, 220,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 1,
                            EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG writer is unavailable");
            return output.toByteArray();
        } catch (com.google.zxing.WriterException exception) {
            throw new IOException("Unable to encode the verification QR", exception);
        }
    }

    private static Pair pair(String label, String value) {
        return new Pair(label, blankFallback(value, "—"));
    }

    private static String label(JsonNode template, String key, String locale, String english, String hindi) {
        String override = template.path("labelOverrides").path(key).asText("").strip();
        if (!override.isBlank()) return override;
        return "hi-IN".equals(locale) ? hindi : english;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").strip();
        return value.isBlank() ? fallback : value;
    }

    private static String decimal(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) return "—";
        return node.decimalValue().stripTrailingZeros().toPlainString() + "%";
    }

    private static String money(JsonNode node, String currency) {
        if (node == null || node.isNull() || !node.isNumber()) return currency + " —";
        return currency + " " + node.decimalValue().setScale(2).toPlainString();
    }

    private static String human(String value) {
        if (value == null || value.isBlank() || "—".equals(value)) return "—";
        String normalized = value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record RenderedPdf(byte[] bytes, String sha256) {
        RenderedPdf {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Pair(String label, String value) {}
    private record Cell(String value, float fraction) {}
    private record Colour(int red, int green, int blue) {
        static final Colour WHITE = new Colour(255, 255, 255);
        static final Colour SOFT_WHITE = new Colour(220, 233, 228);
        static final Colour DARK = new Colour(30, 48, 48);
        static final Colour MUTED = new Colour(92, 111, 108);
        static final Colour LINE = new Colour(215, 224, 220);
        static final Colour PALE = new Colour(240, 245, 242);

        static Colour parse(String value) {
            if (value == null || !value.matches("^#[0-9A-F]{6}$")) return new Colour(35, 72, 63);
            return new Colour(Integer.parseInt(value.substring(1, 3), 16),
                    Integer.parseInt(value.substring(3, 5), 16),
                    Integer.parseInt(value.substring(5, 7), 16));
        }

        Colour lighter() {
            return new Colour((red + 255) / 2, (green + 255) / 2, (blue + 255) / 2);
        }
    }

    private static final class Canvas implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font latinFont;
        private final PDType0Font devanagariFont;
        private final Colour accent;
        private PDPageContentStream stream;
        private float y;

        Canvas(PDDocument document, PDType0Font latinFont, PDType0Font devanagariFont, Colour accent) {
            this.document = document;
            this.latinFont = latinFont;
            this.devanagariFont = devanagariFont;
            this.accent = accent;
        }

        PDDocument document() { return document; }
        Colour accent() { return accent; }
        float y() { return y; }
        void y(float value) { y = value; }
        void moveY(float amount) { y -= amount; }

        void startPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        void ensure(float height) throws IOException {
            if (y - height >= 52) return;
            startPage();
            text("Official document · continued", 8, MARGIN, y, Colour.MUTED);
            moveY(24);
        }

        void text(String value, float size, float x, float baseline, Colour colour) throws IOException {
            String safe = sanitize(value);
            List<FontRun> runs = fontRuns(safe);
            stream.beginText();
            try {
                setFillColour(colour);
                stream.newLineAtOffset(x, baseline);
                for (FontRun run : runs) {
                    stream.setFont(run.font(), size);
                    stream.showText(run.value());
                }
            } finally {
                stream.endText();
            }
        }

        void wrappedText(String value, float size, float x, float baseline,
                float width, Colour colour, float lineHeight) throws IOException {
            float current = baseline;
            for (String line : wrap(value, size, width)) {
                text(line, size, x, current, colour);
                current -= lineHeight;
            }
        }

        void paragraph(String value, float size, Colour colour, float lineHeight) throws IOException {
            List<String> lines = wrap(value, size, CONTENT_WIDTH);
            ensure((lines.size() * lineHeight) + 5);
            for (String line : lines) {
                text(line, size, MARGIN, y, colour);
                moveY(lineHeight);
            }
        }

        void sectionTitle(String value) throws IOException {
            ensure(30);
            text(value, 14, MARGIN, y, accent);
            moveY(22);
        }

        void twoColumnDetails(List<Pair> pairs) throws IOException {
            int rows = (pairs.size() + 1) / 2;
            ensure(rows * 49f);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < 2; column++) {
                    int index = (row * 2) + column;
                    if (index >= pairs.size()) continue;
                    Pair pair = pairs.get(index);
                    float x = MARGIN + (column * ((CONTENT_WIDTH / 2) + 6));
                    float width = (CONTENT_WIDTH / 2) - 6;
                    fill(Colour.PALE, x, y - 38, width, 42);
                    text(pair.label(), 7.5f, x + 10, y - 10, Colour.MUTED);
                    text(fit(pair.value(), 10, width - 20), 10, x + 10, y - 28, Colour.DARK);
                }
                moveY(49);
            }
        }

        void tableHeader(List<Cell> cells) throws IOException {
            ensure(31);
            fill(accent, MARGIN, y - 27, CONTENT_WIDTH, 31);
            float x = MARGIN;
            for (Cell cell : cells) {
                float width = CONTENT_WIDTH * cell.fraction();
                text(fit(cell.value(), 8, width - 12), 8, x + 7, y - 16, Colour.WHITE);
                x += width;
            }
            moveY(31);
        }

        void tableRow(List<Cell> cells) throws IOException {
            fill(Colour.WHITE, MARGIN, y - 29, CONTENT_WIDTH, 31);
            stroke(Colour.LINE, MARGIN, y - 29, CONTENT_WIDTH, 31);
            float x = MARGIN;
            for (Cell cell : cells) {
                float width = CONTENT_WIDTH * cell.fraction();
                text(fit(cell.value(), 8.5f, width - 12), 8.5f, x + 7, y - 18, Colour.DARK);
                x += width;
            }
            moveY(31);
        }

        void summaryBand(List<Pair> pairs) throws IOException {
            ensure(58);
            fill(accent.lighter(), MARGIN, y - 48, CONTENT_WIDTH, 52);
            float width = CONTENT_WIDTH / pairs.size();
            for (int index = 0; index < pairs.size(); index++) {
                Pair pair = pairs.get(index);
                float x = MARGIN + (index * width) + 10;
                text(fit(pair.label(), 7.5f, width - 20), 7.5f, x, y - 13, Colour.MUTED);
                text(fit(pair.value(), 12, width - 20), 12, x, y - 34, Colour.DARK);
            }
            moveY(55);
        }

        void fill(Colour colour, float x, float bottom, float width, float height) throws IOException {
            setFillColour(colour);
            stream.addRect(x, bottom, width, height);
            stream.fill();
        }

        void stroke(Colour colour, float x, float bottom, float width, float height) throws IOException {
            setStrokeColour(colour);
            stream.setLineWidth(0.7f);
            stream.addRect(x, bottom, width, height);
            stream.stroke();
        }

        void rule(Colour colour, float baseline) throws IOException {
            setStrokeColour(colour);
            stream.setLineWidth(1);
            stream.moveTo(MARGIN, baseline);
            stream.lineTo(PAGE_WIDTH - MARGIN, baseline);
            stream.stroke();
        }

        void image(PDImageXObject image, float x, float bottom, float width, float height) throws IOException {
            stream.drawImage(image, x, bottom, width, height);
        }

        private void setFillColour(Colour colour) throws IOException {
            stream.setNonStrokingColor(
                    colour.red() / 255f, colour.green() / 255f, colour.blue() / 255f);
        }

        private void setStrokeColour(Colour colour) throws IOException {
            stream.setStrokingColor(
                    colour.red() / 255f, colour.green() / 255f, colour.blue() / 255f);
        }

        private List<String> wrap(String value, float size, float width) throws IOException {
            String safe = sanitize(value);
            if (safe.isBlank()) return List.of("");
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : safe.split("\\s+")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (textWidth(candidate, size) <= width) current = new StringBuilder(candidate);
                else {
                    if (!current.isEmpty()) lines.add(current.toString());
                    if (textWidth(word, size) <= width) current = new StringBuilder(word);
                    else {
                        current = new StringBuilder();
                        for (int offset = 0; offset < word.length();) {
                            int next = offset + Character.charCount(word.codePointAt(offset));
                            String part = current + word.substring(offset, next);
                            if (!current.isEmpty() && textWidth(part, size) > width) {
                                lines.add(current.toString());
                                current = new StringBuilder();
                            }
                            current.append(word, offset, next);
                            offset = next;
                        }
                    }
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
            return lines;
        }

        private String fit(String value, float size, float width) throws IOException {
            String safe = sanitize(value);
            if (textWidth(safe, size) <= width) return safe;
            String suffix = "…";
            int end = safe.length();
            while (end > 0 && textWidth(safe.substring(0, end) + suffix, size) > width) {
                end = safe.offsetByCodePoints(0, Math.max(0, safe.codePointCount(0, end) - 1));
            }
            return end == 0 ? suffix : safe.substring(0, end).stripTrailing() + suffix;
        }

        private float textWidth(String value, float size) throws IOException {
            float width = 0;
            for (FontRun run : fontRuns(value)) {
                width += run.font().getStringWidth(run.value()) / 1000f * size;
            }
            return width;
        }

        private List<FontRun> fontRuns(String value) throws IOException {
            if (value.isEmpty()) return List.of(new FontRun(latinFont, ""));
            List<FontRun> runs = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            PDType0Font currentFont = null;
            for (int offset = 0; offset < value.length();) {
                int codePoint = value.codePointAt(offset);
                PDType0Font selected = selectFont(codePoint, currentFont);
                int renderedCodePoint = codePoint;
                if (selected == null) {
                    selected = latinFont;
                    renderedCodePoint = '?';
                }
                if (currentFont != null && selected != currentFont) {
                    runs.add(new FontRun(currentFont, current.toString()));
                    current.setLength(0);
                }
                currentFont = selected;
                current.appendCodePoint(renderedCodePoint);
                offset += Character.charCount(codePoint);
            }
            if (!current.isEmpty()) runs.add(new FontRun(currentFont, current.toString()));
            return runs;
        }

        private PDType0Font selectFont(int codePoint, PDType0Font currentFont) throws IOException {
            if (Character.isWhitespace(codePoint) && currentFont != null) return currentFont;
            boolean devanagari = codePoint >= 0x0900 && codePoint <= 0x097F;
            if (devanagari && devanagariFont.hasGlyph(codePoint)) return devanagariFont;
            if (latinFont.hasGlyph(codePoint)) return latinFont;
            if (devanagariFont.hasGlyph(codePoint)) return devanagariFont;
            return null;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) stream.close();
        }

        private static String sanitize(String value) {
            if (value == null) return "";
            return value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ")
                    .replaceAll("[\\u2010-\\u2015\\u2212]", "-")
                    .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').strip();
        }

        private record FontRun(PDFont font, String value) {}
    }
}
