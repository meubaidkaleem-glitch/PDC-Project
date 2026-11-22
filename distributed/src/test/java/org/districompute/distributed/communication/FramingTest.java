package org.districompute.distributed.communication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FramingTest {

    @Test
    void readsSeveralFramesFromOneRead() throws IOException {
        FrameReader reader = reader("one\ntwo\nthree\n", 100);
        assertEquals("one", text(reader.readFrame()));
        assertEquals("two", text(reader.readFrame()));
        assertEquals("three", text(reader.readFrame()));
        assertNull(reader.readFrame());
        assertEquals(14, reader.bytesRead());
    }

    @Test
    void reassemblesFramesFromPartialReads() throws IOException {
        // A stream that returns one byte per read, like a slow network.
        byte[] data = "{\"a\":1}\n{\"b\":22}\n".getBytes(StandardCharsets.UTF_8);
        InputStream trickle = new InputStream() {
            private int pos;

            @Override
            public int read() {
                return pos < data.length ? data[pos++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= data.length) {
                    return -1;
                }
                b[off] = data[pos++];
                return 1;
            }
        };
        FrameReader reader = new FrameReader(trickle, 100);
        assertEquals("{\"a\":1}", text(reader.readFrame()));
        assertEquals("{\"b\":22}", text(reader.readFrame()));
        assertNull(reader.readFrame());
    }

    @Test
    void stripsCarriageReturnsAndSkipsEmptyLines() throws IOException {
        FrameReader reader = reader("\n\r\nfirst\r\n\n\nsecond\n", 100);
        assertEquals("first", text(reader.readFrame()));
        assertEquals("second", text(reader.readFrame()));
        assertNull(reader.readFrame());
    }

    @Test
    void framesSpanningManyBuffersAreReassembled() throws IOException {
        byte[] big = new byte[300_000];
        Arrays.fill(big, (byte) 'x');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(out, 1_000_000);
        writer.write(big);
        writer.write("tail".getBytes(StandardCharsets.UTF_8));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()), 1_000_000);
        assertArrayEquals(big, reader.readFrame());
        assertEquals("tail", text(reader.readFrame()));
        assertEquals(300_006, writer.bytesWritten());
    }

    @Test
    void acceptsAFrameOfExactlyTheLimit() throws IOException {
        assertEquals("12345", text(reader("12345\n", 5).readFrame()));
        assertEquals("12345", text(reader("12345\r\n", 5).readFrame()));
    }

    @Test
    void rejectsOversizedFramesWithoutBufferingThem() {
        assertThrows(FrameTooLargeException.class, () -> reader("123456\n", 5).readFrame());
        assertThrows(FrameTooLargeException.class, () -> reader("1234567890", 5).readFrame());
    }

    @Test
    void reportsATruncatedFinalFrame() {
        FrameReader reader = reader("complete\npartial", 100);
        TruncatedFrameException e = assertThrows(TruncatedFrameException.class, () -> {
            reader.readFrame();
            reader.readFrame();
        });
        assertEquals("Connection closed in the middle of a frame (7 bytes without a line feed)", e.getMessage());
    }

    @Test
    void writerRefusesFramesThePeerCouldNotRead() {
        FrameWriter writer = new FrameWriter(new ByteArrayOutputStream(), 5);
        assertThrows(IllegalArgumentException.class, () -> writer.write(bytes("a\nb")));
        assertThrows(IllegalArgumentException.class, () -> writer.write(bytes("123456")));
        assertThrows(IllegalArgumentException.class, () -> writer.write(new byte[0]));
    }

    private static FrameReader reader(String text, int max) {
        return new FrameReader(new ByteArrayInputStream(bytes(text)), max);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] frame) {
        return new String(frame, StandardCharsets.UTF_8);
    }
}
