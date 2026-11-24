package org.districompute.app.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A small flowing layout on A4 pages with PDFBox: headings, wrapped paragraphs, key-value lists, tables that repeat
 * their header on a new page, and horizontal bar charts. Text is set in the standard Helvetica and Courier fonts;
 * characters they cannot encode (outside Windows-1252) are replaced by '?', which {@code docs/reports.md} states.
 */
final class PdfReport {

    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float TOP = PDRectangle.A4.getHeight() - MARGIN;
    private static final float BOTTOM = MARGIN + 24;
    private static final Color INK = new Color(0x0f, 0x17, 0x2a);
    private static final Color MUTED = new Color(0x47, 0x55, 0x69);
    private static final Color RULE = new Color(0xe2, 0xe8, 0xf0);
    private static final Color HEADER_FILL = new Color(0xf1, 0xf5, 0xf9);
    private static final Color BAR = new Color(0x2a, 0x78, 0xd6);

    private final PDDocument document = new PDDocument();
    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private final Map<PDFont, Map<Character, Boolean>> encodable = new HashMap<>();
    private final String footer;
    private PDPageContentStream content;
    private float y;

    PdfReport(String title, String subtitle, String footer, Calendar created) {
        this.footer = footer;
        PDDocumentInformation info = document.getDocumentInformation();
        info.setTitle(safe(title, regular));
        info.setCreator("DistriCompute");
        info.setProducer("DistriCompute with Apache PDFBox");
        info.setCreationDate(created);
        newPage();
        text(title, bold, 18, MARGIN, y - 18, INK);
        y -= 26;
        if (subtitle != null && !subtitle.isBlank()) {
            for (String line : wrap(subtitle, regular, 11, WIDTH)) {
                text(line, regular, 11, MARGIN, y - 11, MUTED);
                y -= 15;
            }
        }
        y -= 6;
        rule();
    }

    /** Starts a section. */
    void heading(String text) {
        ensure(40);
        y -= 12;
        text(text, bold, 12.5f, MARGIN, y - 12.5f, INK);
        y -= 20;
    }

    /** A wrapped paragraph. */
    void paragraph(String text) {
        lines(text, regular, 9.5f, INK, 0);
        y -= 4;
    }

    /** A wrapped paragraph in smaller, muted type. */
    void note(String text) {
        lines(text, regular, 8, MUTED, 0);
        y -= 4;
    }

    /** A bulleted item. */
    void bullet(String text) {
        List<String> wrapped = wrap(text, regular, 9.5f, WIDTH - 12);
        for (int i = 0; i < wrapped.size(); i++) {
            ensure(13);
            if (i == 0) {
                text("•", regular, 9.5f, MARGIN, y - 9.5f, INK);
            }
            text(wrapped.get(i), regular, 9.5f, MARGIN + 12, y - 9.5f, INK);
            y -= 13;
        }
        y -= 2;
    }

    /** Two columns: names in muted type, values wrapped. */
    void keyValues(List<String[]> items) {
        float keyWidth = 150;
        for (String[] item : items) {
            List<String> values = wrap(item[1] == null ? "-" : item[1], regular, 9.5f, WIDTH - keyWidth);
            ensure(13 * Math.min(values.size(), 3));
            text(item[0], regular, 9.5f, MARGIN, y - 9.5f, MUTED);
            for (String line : values) {
                ensure(13);
                text(line, regular, 9.5f, MARGIN + keyWidth, y - 9.5f, INK);
                y -= 13;
            }
        }
        y -= 4;
    }

    /**
     * A table; cells wrap, the header repeats after a page break.
     *
     * @param headers column titles
     * @param widths relative column widths
     * @param rows rows of cells
     * @param monospaced columns set in Courier (for digests and ids)
     */
    void table(String[] headers, float[] widths, List<String[]> rows, boolean... monospaced) {
        float total = 0;
        for (float w : widths) {
            total += w;
        }
        float[] cols = new float[widths.length];
        for (int i = 0; i < widths.length; i++) {
            cols[i] = widths[i] / total * WIDTH;
        }
        float size = 8.5f;
        tableHeader(headers, cols, size);
        for (String[] row : rows) {
            List<List<String>> cells = new ArrayList<>();
            int height = 1;
            for (int i = 0; i < cols.length; i++) {
                PDFont font = i < monospaced.length && monospaced[i] ? mono : regular;
                List<String> lines = wrap(i < row.length && row[i] != null ? row[i] : "-", font, size, cols[i] - 6);
                cells.add(lines);
                height = Math.max(height, lines.size());
            }
            float rowHeight = height * 11 + 5;
            if (y - rowHeight < BOTTOM) {
                newPage();
                tableHeader(headers, cols, size);
            }
            float x = MARGIN;
            for (int i = 0; i < cols.length; i++) {
                PDFont font = i < monospaced.length && monospaced[i] ? mono : regular;
                float lineY = y - 3 - size;
                for (String line : cells.get(i)) {
                    text(line, font, size, x + 3, lineY, INK);
                    lineY -= 11;
                }
                x += cols[i];
            }
            y -= rowHeight;
            line(MARGIN, y, MARGIN + WIDTH, y, RULE, 0.5f);
        }
        y -= 8;
    }

    /**
     * Horizontal bars, one per label, scaled to the largest value; the value is printed after each bar. The title
     * starts on the same page as the bars.
     *
     * @param title caption above the chart
     * @param labels bar labels
     * @param values values (non-negative)
     * @param format formats a value for printing
     * @param reference a value to mark with a dashed line (for example 1 for speedups), or null
     */
    void barChart(String title, List<String> labels, List<Double> values,
            java.util.function.DoubleFunction<String> format, Double reference) {
        float labelWidth = 150;
        float valueWidth = 70;
        float plot = WIDTH - labelWidth - valueWidth;
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (reference != null) {
            max = Math.max(max, reference);
        }
        max = max <= 0 ? 1 : max;
        float barHeight = 12;
        float gap = 6;
        ensure(Math.min(labels.size(), 10) * (barHeight + gap) + 30);
        lines(title, regular, 9.5f, INK, 0);
        y -= 4;
        float chartTop = y;
        for (int i = 0; i < labels.size(); i++) {
            if (y - barHeight - gap < BOTTOM) {
                newPage();
                chartTop = y;
            }
            String label = wrap(labels.get(i), regular, 8.5f, labelWidth - 8).get(0);
            text(label, regular, 8.5f, MARGIN, y - 10, INK);
            float length = (float) (values.get(i) / max * plot);
            content(c -> {
                c.setNonStrokingColor(BAR);
                c.addRect(MARGIN + labelWidth, y - barHeight, Math.max(1, length), barHeight);
                c.fill();
            });
            text(format.apply(values.get(i)), regular, 8.5f, MARGIN + labelWidth + length + 4, y - 10, MUTED);
            y -= barHeight + gap;
        }
        if (reference != null) {
            float x = MARGIN + labelWidth + (float) (reference / max * plot);
            float top = chartTop + 2;
            float bottom = y + gap - 2;
            content(c -> {
                c.setStrokingColor(MUTED);
                c.setLineWidth(0.75f);
                c.setLineDashPattern(new float[] {3, 3}, 0);
                c.moveTo(x, top);
                c.lineTo(x, bottom);
                c.stroke();
                c.setLineDashPattern(new float[] {}, 0);
            });
            text(format.apply(reference), regular, 7.5f, x + 2, bottom - 9, MUTED);
            y -= 10;
        }
        y -= 6;
    }

    /**
     * Finishes the document: footers with page numbers on every page.
     *
     * @return the PDF
     */
    byte[] finish() {
        try {
            content.close();
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                try (PDPageContentStream c = new PDPageContentStream(document, document.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true)) {
                    String text = safe(footer + "  -  page " + (i + 1) + " of " + pages, regular);
                    c.beginText();
                    c.setFont(regular, 7.5f);
                    c.setNonStrokingColor(MUTED);
                    c.newLineAtOffset(MARGIN, MARGIN - 6);
                    c.showText(text);
                    c.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            document.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void tableHeader(String[] headers, float[] cols, float size) {
        int height = 1;
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            List<String> lines = wrap(headers[i], bold, size, cols[i] - 6);
            cells.add(lines);
            height = Math.max(height, lines.size());
        }
        float rowHeight = height * 11 + 5;
        ensure(rowHeight + 16);
        float top = y;
        content(c -> {
            c.setNonStrokingColor(HEADER_FILL);
            c.addRect(MARGIN, top - rowHeight, WIDTH, rowHeight);
            c.fill();
        });
        float x = MARGIN;
        for (int i = 0; i < cols.length; i++) {
            float lineY = y - 3 - size;
            for (String line : cells.get(i)) {
                text(line, bold, size, x + 3, lineY, INK);
                lineY -= 11;
            }
            x += cols[i];
        }
        y -= rowHeight;
    }

    private void lines(String text, PDFont font, float size, Color color, float indent) {
        for (String line : wrap(text, font, size, WIDTH - indent)) {
            ensure(size + 4);
            text(line, font, size, MARGIN + indent, y - size, color);
            y -= size + 3.5f;
        }
    }

    private void rule() {
        line(MARGIN, y, MARGIN + WIDTH, y, RULE, 0.75f);
        y -= 6;
    }

    private void ensure(float height) {
        if (y - height < BOTTOM) {
            newPage();
        }
    }

    private void newPage() {
        try {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = TOP;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void text(String text, PDFont font, float size, float x, float baseline, Color color) {
        String safe = safe(text, font);
        content(c -> {
            c.beginText();
            c.setFont(font, size);
            c.setNonStrokingColor(color);
            c.newLineAtOffset(x, baseline);
            c.showText(safe);
            c.endText();
        });
    }

    private void line(float x1, float y1, float x2, float y2, Color color, float width) {
        content(c -> {
            c.setStrokingColor(color);
            c.setLineWidth(width);
            c.moveTo(x1, y1);
            c.lineTo(x2, y2);
            c.stroke();
        });
    }

    private interface Drawing {
        void draw(PDPageContentStream c) throws IOException;
    }

    private void content(Drawing drawing) {
        try {
            drawing.draw(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lines of at most {@code width} points; long words are cut. */
    List<String> wrap(String text, PDFont font, float size, float width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : safe(text, font).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(candidate, font, size) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (width(rest, font, size) > width && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(rest.substring(0, cut), font, size) > width) {
                        cut--;
                    }
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static float width(String text, PDFont font, float size) {
        try {
            return font.getStringWidth(text) / 1000 * size;
        } catch (IOException | IllegalArgumentException e) {
            return text.length() * size * 0.6f;
        }
    }

    /** Replaces characters the font cannot encode, and control characters, keeping line breaks. */
    String safe(String text, PDFont font) {
        if (text == null) {
            return "";
        }
        Map<Character, Boolean> known = encodable.computeIfAbsent(font, f -> new HashMap<>());
        StringBuilder out = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            if (ch == '\n') {
                out.append(ch);
            } else if (Character.isISOControl(ch)) {
                out.append(' ');
            } else if (known.computeIfAbsent(ch, c -> canEncode(font, c))) {
                out.append(ch);
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static boolean canEncode(PDFont font, char ch) {
        try {
            font.encode(String.valueOf(ch));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
