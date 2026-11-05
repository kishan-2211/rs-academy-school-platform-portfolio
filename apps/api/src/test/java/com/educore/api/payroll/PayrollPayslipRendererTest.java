package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.payroll.PayrollPayslipRenderer.AmountEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.AttendanceEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.EmployeeEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.LineEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.PayrollEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.PayslipEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.SchoolEvidence;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PayrollPayslipRendererTest {
    private final PayrollPayslipRenderer renderer = new PayrollPayslipRenderer();

    @Test
    void rendersBrandedEnglishPrivatePayslipWithoutPublicVerificationMaterial() throws IOException {
        var result = renderer.render(evidence("en-IN", standardLines()));

        assertValid(result);
        String text = extractText(result);
        assertThat(text).contains("Example School", "Synthetic Staff", "PSL-202608-001",
                        "Net salary", "Private payroll record")
                .doesNotContain("verify", "QR", "public token");
    }

    @Test
    void rendersHindiPayslipUsingEmbeddedDevanagariFont() throws IOException {
        var result = renderer.render(evidence("hi-IN", standardLines()));

        assertValid(result);
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getDocumentCatalog().getLanguage()).isEqualTo("hi-IN");
        }
    }

    @Test
    void paginatesLongFrozenComponentEvidence() throws IOException {
        List<LineEvidence> lines = new ArrayList<>();
        for (int index = 1; index <= 42; index++) {
            lines.add(new LineEvidence("E" + index, "Synthetic component " + index,
                    "EARNING", false, new BigDecimal("100.00"), new BigDecimal("100.00")));
        }
        var result = renderer.render(evidence("en-IN", lines));

        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
    }

    private static PayslipEvidence evidence(String locale, List<LineEvidence> lines) {
        return new PayslipEvidence(1, "PSL-202608-001", locale, UUID.randomUUID(), UUID.randomUUID(),
                new SchoolEvidence("Example School", "Example locality, Varanasi",
                        "school@example.test"),
                new PayrollEvidence("PAY-202608-001", LocalDate.of(2026, 8, 1), "INR"),
                new EmployeeEvidence("SYN-001", "Synthetic Staff"),
                new AttendanceEvidence(24, 24, new BigDecimal("24.0000"), BigDecimal.ZERO,
                        BigDecimal.ZERO),
                new AmountEvidence(new BigDecimal("22500.00"), new BigDecimal("1500.00"),
                        new BigDecimal("800.00"), new BigDecimal("21000.00")),
                lines, Instant.parse("2026-09-01T10:15:30Z"));
    }

    private static List<LineEvidence> standardLines() {
        return List.of(
                new LineEvidence("BASIC", "Basic salary", "EARNING", false,
                        new BigDecimal("20000.00"), new BigDecimal("20000.00")),
                new LineEvidence("ALLOWANCE", "School allowance", "EARNING", false,
                        new BigDecimal("2500.00"), new BigDecimal("2500.00")),
                new LineEvidence("DEDUCTION", "Synthetic deduction", "DEDUCTION", false,
                        new BigDecimal("1500.00"), new BigDecimal("1500.00")));
    }

    private static void assertValid(PayrollPayslipRenderer.RenderedPdf result) throws IOException {
        assertThat(result.bytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F');
        assertThat(result.bytes().length).isBetween(1_000, 10_485_760);
        assertThat(result.sha256()).matches("^[0-9a-f]{64}$");
        try (var document = Loader.loadPDF(result.bytes())) {
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(document.getDocumentInformation().getTitle()).contains("PSL-202608-001");
        }
    }

    private static String extractText(PayrollPayslipRenderer.RenderedPdf result) throws IOException {
        try (var document = Loader.loadPDF(result.bytes())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
