package com.educore.api.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialDocumentRendererTest {

    private final OfficialDocumentRenderer renderer = new OfficialDocumentRenderer(new ObjectMapper());

    @Test
    void rendersEnglishReportCardWithVerificationEvidence() throws IOException {
        var result = renderer.render(
                "REPORT_CARD",
                template("en-IN", "Annual report card", "Principal"),
                """
                {
                  "documentNumber":"RC-2026-001",
                  "school":{"name":"Example School","address":"Example locality, Varanasi","contact":"school@example.test"},
                  "learner":{"name":"Synthetic Learner","rollNumber":"17"},
                  "academic":{"section":"Class 10 · A","term":"Annual","session":"2026–27"},
                  "report":{"subjects":[
                    {"name":"Mathematics","percentage":91.25,"gradeCode":"A1","outcome":"PASS"},
                    {"name":"Science","percentage":87.5,"gradeCode":"A2","outcome":"PASS"}
                  ],"overallPercentage":89.38,"overallGradeCode":"A1","overallOutcome":"PASS"}
                }
                """,
                "a".repeat(64),
                "https://school.example.test/verify/test-token",
                Instant.parse("2026-08-22T10:15:30Z"),
                "Synthetic Approver");

        assertValidPdf(result);
        assertThat(extractText(result)).contains("2026-27").doesNotContain("2026?27");
        writeQaPdf(result, "report-card-en.pdf");
    }

    @Test
    void rendersHindiFeeReceiptUsingEmbeddedDevanagariFont() throws IOException {
        var result = renderer.render(
                "FEE_RECEIPT",
                template("hi-IN", "शुल्क रसीद", "प्रधानाचार्य"),
                """
                {
                  "documentNumber":"FR-2026-001",
                  "school":{"name":"उदाहरण विद्यालय","address":"उदाहरण स्थान, वाराणसी","contact":"school@example.test"},
                  "learner":{"name":"परीक्षण विद्यार्थी","admissionNumber":"SYN-001"},
                  "payment":{"receivedOn":"2026-08-22","method":"BANK_TRANSFER","status":"POSTED","currency":"INR","amount":1850},
                  "allocations":[{"label":"मासिक शुल्क","amount":1400},{"label":"वार्षिक शुल्क","amount":450}]
                }
                """,
                "b".repeat(64),
                "https://school.example.test/verify/test-token",
                Instant.parse("2026-08-22T10:15:30Z"),
                "परीक्षण अनुमोदक");

        assertValidPdf(result);
        writeQaPdf(result, "fee-receipt-hi.pdf");
    }

    @Test
    void newReceiptSeparatesActualReceivedTimeFromOpenDatePostingAndIgnoresLegacyDateOverride() throws IOException {
        String configured = template("en-IN", "Synthetic fee receipt", "Principal")
                .replace("\"labelOverrides\":{}", "\"labelOverrides\":{\"receivedOn\":\"Legacy received date\"}");
        var pdf = receipt(configured, """
                {"postingOn":"2026-09-11","originalReceivedOn":"2026-09-10",
                 "actualReceivedAt":"2026-09-10T03:05:07.123Z","receivedTimePrecision":"INSTANT",
                 "schoolTimeZone":"Asia/Kolkata","amount":200,"currency":"INR","method":"SCHOOL_UPI","status":"POSTED"}
                """, 2);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("Financial posting date", "2026-09-11", "Original received date", "2026-09-10",
                "Actual funds received", "2026-09-10T08:35:07.123+05:30", "[Asia/Kolkata]");
        assertThat(text).doesNotContain("Legacy received date", "Time not recorded");
        assertValidPdf(pdf);
        assertSinglePage(pdf);
        writeQaPdf(pdf, "fee-receipt-timing-en.pdf");
    }

    @Test
    void newDateOnlyCashReceiptNeverUsesIssuanceTimeOrCurrentZoneAsReceivedEvidence() throws IOException {
        var pdf = receipt(template("en-IN", "Synthetic cash receipt", "Principal"), """
                {"postingOn":"2026-09-11","originalReceivedOn":"2026-09-10",
                 "actualReceivedAt":null,"receivedTimePrecision":"DATE_ONLY","schoolTimeZone":null,
                 "amount":100,"currency":"INR","method":"CASH","status":"POSTED"}
                """, 2);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("Financial posting date", "2026-09-11", "Original received date", "2026-09-10",
                "Time not recorded (date only)");
        String receivedEvidence = text.substring(text.indexOf("Actual funds received:"), text.indexOf("Payment allocation"));
        assertThat(receivedEvidence).contains("Time not recorded (date only)").doesNotContain("T10:15:30", "00:00:00", "Asia/Kolkata");
        assertThat(text).contains("Issued at: 2026-09-12T10:15:30Z");
        assertSinglePage(pdf);
        writeQaPdf(pdf, "fee-receipt-date-only-en.pdf");
    }

    @Test
    void knownInstantWithoutHistoricalZoneIsShownAsItsRecordedUtcInstantWithoutInventingASchoolZone() throws IOException {
        var pdf = receipt(template("en-IN", "Synthetic received evidence", "Principal"), """
                {"postingOn":"2026-09-11","originalReceivedOn":"2026-09-10",
                 "actualReceivedAt":"2026-09-10T03:05:07Z","receivedTimePrecision":"INSTANT","schoolTimeZone":null,
                 "amount":100,"currency":"INR","method":"ONLINE_PROVIDER","status":"POSTED"}
                """, 2);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("2026-09-10T03:05:07Z", "recorded time zone unavailable");
        assertThat(text).doesNotContain("Asia/Kolkata", "+05:30");
    }

    @Test
    void frozenLegacyReceiptKeepsItsOriginalReceivedOnAndPublishedLabelOverride() throws IOException {
        String configured = template("en-IN", "Synthetic historical receipt", "Principal")
                .replace("\"labelOverrides\":{}", "\"labelOverrides\":{\"receivedOn\":\"Original approved date label\"}");
        var pdf = receipt(configured, """
                {"receivedOn":"2026-08-22","amount":1850,"currency":"INR","method":"BANK_TRANSFER","status":"POSTED"}
                """, 1);
        String text = extractText(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("Original approved date label", "2026-08-22");
        assertThat(text).doesNotContain("Financial posting date", "Actual funds received", "Time not recorded");
        writeQaPdf(pdf, "fee-receipt-legacy-en.pdf");
    }

    @Test
    void newHindiReceiptUsesTheEmbeddedFontsAndBothDistinctRecordedDates() throws IOException {
        var pdf = receipt(template("hi-IN", "शुल्क रसीद", "प्रधानाचार्य"), """
                {"postingOn":"2026-09-11","originalReceivedOn":"2026-09-10",
                 "actualReceivedAt":"2026-09-10T03:05:07Z","receivedTimePrecision":"INSTANT",
                 "schoolTimeZone":"Asia/Kolkata","amount":200,"currency":"INR","method":"SCHOOL_UPI","status":"POSTED"}
                """, 2);
        assertThat(extractText(pdf)).contains("2026-09-11", "2026-09-10", "08:35:07", "Asia/Kolkata");
        assertValidPdf(pdf);
        assertSinglePage(pdf);
        writeQaPdf(pdf, "fee-receipt-timing-hi.pdf");
    }

    private OfficialDocumentRenderer.RenderedPdf receipt(String configured, String payment, int schemaVersion) {
        String allocatedAmount = new ObjectMapper().readTree(payment).path("amount").asText();
        return renderer.render("FEE_RECEIPT", configured, """
                {"schemaVersion":%d,"documentNumber":"SYN/RECEIPT/1",
                 "school":{"name":"Synthetic school","address":"Synthetic locality","contact":"synthetic@example.test"},
                 "learner":{"name":"Synthetic learner","admissionNumber":"SYN-001"},
                 "payment":%s,"allocations":[{"label":"Synthetic tuition","amount":%s}]}
                """.formatted(schemaVersion, payment, allocatedAmount), "d".repeat(64),
                "https://school.example.test/verify/synthetic-token", Instant.parse("2026-09-12T10:15:30Z"),
                "Synthetic issuer");
    }

    private static void assertSinglePage(OfficialDocumentRenderer.RenderedPdf result) throws IOException {
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void rendersEnglishBonafideWithoutTruncatingOfficialEvidence() throws IOException {
        String learnerName="Synthetic learner with a deliberately long name for certificate line wrapping verification";
        String reference="SYNTHETIC_CERTIFICATE_NUMBERING_PREFIX_001/2026/000000001";
        var result=renderBonafide("en-IN","Bonafide certificate",learnerName,reference);
        assertValidPdf(result);
        String content=extractText(result).replaceAll("\\s+"," ");
        assertThat(content).contains(learnerName,reference,"2026-09-24","2026-04-01","SYN-ENROL-001",
                "Enrolment details","confirms enrolment only","not a transfer certificate");
        assertThat(content).doesNotContain("…");
        try(var document=Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        writeQaPdf(result,"bonafide-en.pdf");
    }

    @Test
    void rendersHindiBonafideWithSchoolEvidenceAndVerification() throws IOException {
        var result=renderBonafide("hi-IN","अध्ययन प्रमाणपत्र","परीक्षण विद्यार्थी","SYN-BON/2026/000000002");
        assertValidPdf(result);
        assertThat(extractText(result)).contains("SYN-BON/2026/000000002","SYN-ENROL-001","2026-09-24");
        try(var document=Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        writeQaPdf(result,"bonafide-hi.pdf");
    }

    private OfficialDocumentRenderer.RenderedPdf renderBonafide(String locale,String title,String learner,String reference) {
        return renderer.render("BONAFIDE_CERTIFICATE",template(locale,title,"Principal"),"""
                {"documentNumber":"%s",
                 "school":{"name":"Example School","address":"Synthetic locality, Varanasi","contact":"school@example.test"},
                 "learner":{"name":"%s","admissionNumber":"SYN-ENROL-001"},
                 "academic":{"grade":"VI","session":"Synthetic academic session 2026-2027"},
                 "certificate":{"asOf":"2026-09-24","enrolledSince":"2026-04-01"}}
                """.formatted(reference,learner),"c".repeat(64),"https://school.example.test/verify/synthetic-token",
                Instant.parse("2026-09-24T06:30:00Z"),"Synthetic Approver");
    }

    private static String template(String locale, String title, String signatory) {
        return """
                {
                  "locale":"%s",
                  "title":"%s",
                  "subtitle":"Controlled school record",
                  "footerText":"Verify the QR code before relying on this document.",
                  "signatoryRole":"%s",
                  "accentColour":"#23483F",
                  "labelOverrides":{}
                }
                """.formatted(locale, title, signatory);
    }

    private static void assertValidPdf(OfficialDocumentRenderer.RenderedPdf result) throws IOException {
        assertThat(result.bytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
        assertThat(result.bytes().length).isBetween(1_000, 10_485_760);
        assertThat(result.sha256()).matches("^[0-9a-f]{64}$");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(document.getDocumentCatalog().getLanguage()).isNotBlank();
            assertThat(document.getDocumentInformation().getTitle()).isNotBlank();
        }
    }

    private static String extractText(OfficialDocumentRenderer.RenderedPdf result) throws IOException {
        try (var document = Loader.loadPDF(result.bytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static void writeQaPdf(OfficialDocumentRenderer.RenderedPdf result, String filename)
            throws IOException {
        var outputDirectory = System.getProperty("educore.pdf.qa.outputDir");
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return;
        }
        var directory = Path.of(outputDirectory);
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), result.bytes());
    }
}
