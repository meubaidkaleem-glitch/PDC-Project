package org.districompute.app.report;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RFC 4180 CSV (comma, CRLF, quotes doubled) in UTF-8 with a byte-order mark so that spreadsheets detect the
 * encoding. Text cells that a spreadsheet would run as a formula (starting with =, +, -, @, tab or carriage return)
 * get a leading apostrophe; numbers are written as they are.
 */
final class CsvTable {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    private final List<String> lines = new ArrayList<>();

    CsvTable(List<String> header) {
        row(new ArrayList<>(header));
    }

    /** Appends a row; null cells are empty. */
    void row(List<?> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(i)));
        }
        lines.add(line.toString());
    }

    byte[] bytes() {
        String body = String.join("\r\n", lines) + "\r\n";
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(text, 0, out, 3, text.length);
        return out;
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof Double d ? (d.isNaN() || d.isInfinite() ? "" : stripZero(d)) : value.toString();
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0
                && !NUMBER.matcher(text).matches()) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String stripZero(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
