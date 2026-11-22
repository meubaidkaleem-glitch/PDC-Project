package org.districompute.distributed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DistributedCliTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    void printsVersions() {
        assertEquals(0, DistributedCli.run(List.of("version"), out, err));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains(DistributedVersion.version()));
    }

    @Test
    void unknownCommandIsAUsageError() {
        assertEquals(2, DistributedCli.run(List.of("nonsense"), out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Unknown command"));
    }

    @Test
    void helpListsCommands() {
        assertEquals(0, DistributedCli.run(List.of(), out, err));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Commands:"));
    }
}
