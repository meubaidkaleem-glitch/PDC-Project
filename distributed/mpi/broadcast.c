/*
 * Broadcast with MPI_Bcast.
 *
 * Rank 0 chooses the problem parameters and a data array; one MPI_Bcast delivers them to every process. Each
 * process computes a checksum of what it received, and MPI_Reduce brings the number of mismatches back to rank 0.
 * For comparison, rank 0 also distributes the same array with one MPI_Send per process (a linear broadcast).
 *
 * Run: mpirun -np 4 ./broadcast [elements]
 */
#include <mpi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct {
    int64_t elements;
    uint64_t seed;
} Parameters;

static uint64_t next(uint64_t *state) {
    /* SplitMix64: the same generator family DistriCompute-Core uses for reproducible inputs. */
    uint64_t z = (*state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

static uint64_t checksum(const uint64_t *data, int64_t n) {
    uint64_t sum = 1469598103934665603ULL;
    for (int64_t i = 0; i < n; i++) {
        sum = (sum ^ data[i]) * 1099511628211ULL;
    }
    return sum;
}

int main(int argc, char **argv) {
    MPI_Init(&argc, &argv);
    int rank;
    int size;
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    MPI_Comm_size(MPI_COMM_WORLD, &size);

    Parameters params = {0, 0};
    if (rank == 0) {
        params.elements = argc > 1 ? atoll(argv[1]) : 1 << 20;
        params.seed = 42;
    }
    /* The parameters are a small struct: broadcast its bytes. */
    MPI_Bcast(&params, (int) sizeof params, MPI_BYTE, 0, MPI_COMM_WORLD);

    uint64_t *data = malloc((size_t) params.elements * sizeof *data);
    if (data == NULL) {
        MPI_Abort(MPI_COMM_WORLD, 2);
    }
    uint64_t expected = 0;
    if (rank == 0) {
        uint64_t state = params.seed;
        for (int64_t i = 0; i < params.elements; i++) {
            data[i] = next(&state);
        }
        expected = checksum(data, params.elements);
    }
    MPI_Bcast(&expected, 1, MPI_UINT64_T, 0, MPI_COMM_WORLD);

    MPI_Barrier(MPI_COMM_WORLD);
    double start = MPI_Wtime();
    MPI_Bcast(data, (int) params.elements, MPI_UINT64_T, 0, MPI_COMM_WORLD);
    double collective = MPI_Wtime() - start;
    int mismatches = checksum(data, params.elements) == expected ? 0 : 1;

    /* The same distribution with point-to-point messages, for comparison. */
    MPI_Barrier(MPI_COMM_WORLD);
    start = MPI_Wtime();
    if (rank == 0) {
        for (int r = 1; r < size; r++) {
            MPI_Send(data, (int) params.elements, MPI_UINT64_T, r, 0, MPI_COMM_WORLD);
        }
    } else {
        MPI_Recv(data, (int) params.elements, MPI_UINT64_T, 0, 0, MPI_COMM_WORLD, MPI_STATUS_IGNORE);
    }
    double linear = MPI_Wtime() - start;
    mismatches += checksum(data, params.elements) == expected ? 0 : 1;

    int total = 0;
    MPI_Reduce(&mismatches, &total, 1, MPI_INT, MPI_SUM, 0, MPI_COMM_WORLD);
    double slowest_collective = 0;
    double slowest_linear = 0;
    MPI_Reduce(&collective, &slowest_collective, 1, MPI_DOUBLE, MPI_MAX, 0, MPI_COMM_WORLD);
    MPI_Reduce(&linear, &slowest_linear, 1, MPI_DOUBLE, MPI_MAX, 0, MPI_COMM_WORLD);
    if (rank == 0) {
        printf("broadcast: %lld elements (%.1f MiB) to %d processes\n", (long long) params.elements,
               params.elements * 8.0 / 1048576, size);
        printf("  MPI_Bcast          %8.3f ms\n", slowest_collective * 1e3);
        printf("  linear MPI_Send    %8.3f ms\n", slowest_linear * 1e3);
        printf("  checksum %016llx on every process: %s\n", (unsigned long long) expected,
               total == 0 ? "OK" : "MISMATCH");
    }
    free(data);
    MPI_Finalize();
    return total == 0 ? 0 : 1;
}
