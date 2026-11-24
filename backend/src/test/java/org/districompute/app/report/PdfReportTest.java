package org.districompute.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfReportTest {

    @Test
    void writesAReadableDocumentWithTablesChartsAndPageNumbers() throws Exception {
        PdfReport pdf = new PdfReport("Experiment report", "Matrix 768 × 768 – sequential", "DistriCompute test",
                new GregorianCalendar());
        pdf.heading("Runs");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            rows.add(new String[] {String.valueOf(i), "completed", i + ".00 ms",
                "sha256:56c4d06247e059d1da610b900b78bc27c6586bf07b54a3f9215f95ea7fa85ee0"});
        }
        pdf.table(new String[] {"#", "Status", "Wall time", "Digest"}, new float[] {3, 8, 8, 30}, rows,
                false, false, false, true);
        pdf.barChart("Wall time", List.of("Run 1", "Run 2"), List.of(10.0, 20.0), v -> v + " ms", 15.0);
        pdf.bullet("A finding that is long enough to wrap onto a second line of the page, because reports explain "
                + "their figures in sentences.");
        byte[] bytes = pdf.finish();

        assertEquals("%PDF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() > 1, "120 rows need more than one page");
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Matrix 768 × 768 – sequential"), "Windows-1252 characters are kept");
            assertTrue(text.contains("page 1 of " + document.getNumberOfPages()));
            assertTrue(text.contains("Run 2"));
            assertEquals("Experiment report", document.getDocumentInformation().getTitle());
        }
    }

    @Test
    void charactersTheStandardFontsCannotShowAreReplaced() throws Exception {
        PdfReport pdf = new PdfReport("Report", "Experiment 实验 → done", "footer", new GregorianCalendar());
        byte[] bytes = pdf.finish();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Experiment ?? ? done"), text);
        }
    }
}
