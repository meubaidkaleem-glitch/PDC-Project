package org.districompute.core.serialization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.matrix.AddWork;
import org.districompute.core.algorithm.matrix.Matrix;
import org.districompute.core.algorithm.matrix.MatrixBlock;
import org.districompute.core.algorithm.matrix.MultiplyWork;
import org.districompute.core.algorithm.matrix.ValueDistribution;
import org.districompute.core.algorithm.primes.PrimeCount;
import org.districompute.core.algorithm.primes.PrimeRange;
import org.districompute.core.algorithm.sort.SortChunk;
import org.districompute.core.algorithm.sort.SortedRun;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.partition.BlockBounds;
import org.junit.jupiter.api.Test;

class PayloadCodecTest {

    private static Matrix matrix(int rows, int cols, long stream) {
        return ValueDistribution.UNIFORM.generate(rows, cols, 3, stream, CancellationToken.NONE);
    }

    @Test
    void primeAndSortPayloadsRoundTrip() {
        assertEquals(new PrimeRange(10, 99), PrimeCount.RANGE_CODEC.decode(PrimeCount.RANGE_CODEC.encode(
                new PrimeRange(10, 99))));
        assertEquals(new PrimeCount(10, 99, 21), PrimeCount.CODEC.decode(PrimeCount.CODEC.encode(
                new PrimeCount(10, 99, 21))));

        long[] values = {5, -3, Long.MAX_VALUE, Long.MIN_VALUE, 0, 9};
        SortChunk decodedChunk = SortChunk.CODEC.decode(SortChunk.CODEC.encode(new SortChunk(values, 1, 5, 101)));
        assertArrayEquals(Arrays.copyOfRange(values, 1, 5), decodedChunk.values(), "only the chunk is sent");
        assertEquals(101, decodedChunk.offset());
        SortedRun run = SortedRun.CODEC.decode(SortedRun.CODEC.encode(new SortedRun(7, new long[] {1, 2, 3})));
        assertEquals(7, run.offset());
        assertArrayEquals(new long[] {1, 2, 3}, run.values());
    }

    @Test
    void matrixWorkUnitsCarryExactlyTheNeededBands() {
        Matrix a = matrix(6, 4, 1);
        Matrix b = matrix(4, 5, 2);
        BlockBounds block = new BlockBounds(2, 5, 1, 4);
        MultiplyWork decoded = MultiplyWork.CODEC.decode(MultiplyWork.CODEC.encode(
                new MultiplyWork(a, 2, b, 1, block, 16)));
        assertEquals(3, decoded.a().rows());
        assertEquals(4, decoded.a().cols());
        assertEquals(4, decoded.b().rows());
        assertEquals(3, decoded.b().cols());
        assertEquals(a.get(3, 2), decoded.a().get(1, 2), 0.0);
        assertEquals(b.get(2, 3), decoded.b().get(2, 2), 0.0);
        assertEquals(block, decoded.block());
        assertEquals(16, decoded.blockSize());

        AddWork add = AddWork.CODEC.decode(AddWork.CODEC.encode(new AddWork(a, matrix(6, 4, 2), 1, 1,
                new BlockBounds(1, 3, 1, 4))));
        assertEquals(a.get(2, 3), add.a().get(1, 2), 0.0);

        MatrixBlock blockResult = new MatrixBlock(2, 1, matrix(3, 3, 4));
        MatrixBlock roundTrip = MatrixBlock.CODEC.decode(MatrixBlock.CODEC.encode(blockResult));
        assertTrue(blockResult.data().equalsExactly(roundTrip.data()));
        assertEquals(2, roundTrip.rowStart());
    }

    @Test
    void corruptedTruncatedAndForeignPayloadsAreRejected() {
        byte[] bytes = PrimeCount.CODEC.encode(new PrimeCount(1, 100, 25));
        byte[] flipped = bytes.clone();
        flipped[bytes.length / 2] ^= 0x10;
        assertSerializationFailure(() -> PrimeCount.CODEC.decode(flipped));
        assertSerializationFailure(() -> PrimeCount.CODEC.decode(Arrays.copyOf(bytes, bytes.length - 3)));
        assertSerializationFailure(() -> PrimeCount.CODEC.decode(new byte[3]));
        assertSerializationFailure(() -> PrimeCount.RANGE_CODEC.decode(bytes));
        assertSerializationFailure(() -> SortedRun.CODEC.decode(bytes));
    }

    @Test
    void declaredLengthsAreCheckedBeforeAllocating() {
        byte[] lying = new BinaryPayloadWriter("sort-run/1", 12).putLong(0).putInt(Integer.MAX_VALUE).finish();
        DistriComputeException e = assertThrows(DistriComputeException.class, () -> SortedRun.CODEC.decode(lying));
        assertEquals(ErrorCode.SERIALIZATION_FAILED, e.code());
        assertThrows(IllegalStateException.class,
                () -> new BinaryPayloadWriter("x/1", 16).putLong(1).finish(), "declared body not fully written");
    }

    @Test
    void jsonEnvelopeDetectsTampering() {
        byte[] bytes = SortedRun.CODEC.encode(new SortedRun(0, new long[] {1, 2}));
        EncodedPayload payload = EncodedPayload.of("sort-run/1", bytes);
        assertArrayEquals(bytes, payload.decodeBytes());
        EncodedPayload roundTrip = ContractJson.fromJson(ContractJson.toJson(payload), EncodedPayload.class);
        assertArrayEquals(bytes, roundTrip.decodeBytes());
        EncodedPayload tampered = new EncodedPayload(payload.format(), payload.sizeBytes(), payload.crc32c(),
                payload.base64().replace(payload.base64().charAt(10), payload.base64().charAt(10) == 'A' ? 'B' : 'A'));
        assertSerializationFailure(tampered::decodeBytes);
        assertSerializationFailure(() -> new EncodedPayload("x/1", 3, "00000000", "!!!").decodeBytes());
    }

    private static void assertSerializationFailure(Runnable action) {
        DistriComputeException e = assertThrows(DistriComputeException.class, action::run);
        assertEquals(ErrorCode.SERIALIZATION_FAILED, e.code(), e.getMessage());
    }
}
