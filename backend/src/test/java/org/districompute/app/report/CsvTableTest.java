package org.districompute.app.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTableTest {

    @Test
    void cellsAreQuotedAsRfc4180Requires() {
        assertEquals("plain", CsvTable.cell("plain"));
        assertEquals("\"a,b\"", CsvTable.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvTable.cell("say \"hi\""));
        assertEquals("\"two\nlines\"", CsvTable.cell("two\nlines"));
        assertEquals("", CsvTable.cell(null));
    }

    @Test
    void textThatASpreadsheetWouldRunAsAFormulaIsDefused() {
        assertEquals("'=1+2", CsvTable.cell("=1+2"));
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvTable.cell("=HYPERLINK(\"x\")"), "defused, then quoted");
        assertEquals("'+1+2", CsvTable.cell("+1+2"));
        assertEquals("'@SUM(A1)", CsvTable.cell("@SUM(A1)"));
        assertEquals("'-cmd", CsvTable.cell("-cmd"));
        assertEquals("-12.5", CsvTable.cell("-12.5"), "negative numbers in text stay numbers");
        assertEquals("-3", CsvTable.cell(-3), "numbers are written as they are");
        assertEquals("1.5E-7", CsvTable.cell(1.5e-7));
        assertEquals("42", CsvTable.cell(42.0));
    }

    @Test
    void theFileIsUtf8WithAByteOrderMarkAndCrlf() {
        CsvTable csv = new CsvTable(List.of("name", "value"));
        csv.row(Arrays.asList("Größe", 1));
        byte[] bytes = csv.bytes();
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals("name,value\r\nGröße,1\r\n", new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
    }
}
