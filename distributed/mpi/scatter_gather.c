/*
 * Scatter and gather with MPI_Scatterv, MPI_Gatherv and MPI_Reduce.
 *
 * Rank 0 owns an array of n doubles. MPI_Scatterv gives each process a contiguous chunk (sizes differ by at most
 * one element when n is not divisible by the number of processes, the same remainder rule as DistriCompute-Core's
 * range partitioner). Each process transforms its chunk (x -> 2x + 1) and computes the sum of squares of the
 * original values. MPI_Gatherv collects the transformed chunks in order, MPI_Reduce adds the partial sums, and
 * rank 0 checks both against a sequential computation.
 *
 * Run: mpirun -np 4 ./scatter_gather [n]
 */
#include <math.h>
#include <mpi.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    MPI_Init(&argc, &argv);
    int rank;
    int size;
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    MPI_Comm_size(MPI_COMM_WORLD, &size);
    long n = argc > 1 ? atol(argv[1]) : 1000003; /* prime, so the chunks cannot all be equal */

    int *counts = malloc((size_t) size * sizeof *counts);
    int *offsets = malloc((size_t) size * sizeof *offsets);
    if (counts == NULL || offsets == NULL) {
        MPI_Abort(MPI_COMM_WORLD, 2);
    }
    for (int r = 0, offset = 0; r < size; r++) {
        counts[r] = (int) (n / size + (r < n % size ? 1 : 0));
        offsets[r] = offset;
        offset += counts[r];
    }

    double *all = NULL;
    double *result = NULL;
    if (rank == 0) {
        all = malloc((size_t) n * sizeof *all);
        result = malloc((size_t) n * sizeof *result);
        if (all == NULL || result == NULL) {
            MPI_Abort(MPI_COMM_WORLD, 2);
        }
        for (long i = 0; i < n; i++) {
            all[i] = (double) (i % 1000) / 7.0;
        }
    }
    double *chunk = malloc((size_t) counts[rank] * sizeof *chunk);
    if (chunk == NULL && counts[rank] > 0) {
        MPI_Abort(MPI_COMM_WORLD, 2);
    }

    double start = MPI_Wtime();
    MPI_Scatterv(all, counts, offsets, MPI_DOUBLE, chunk, counts[rank], MPI_DOUBLE, 0, MPI_COMM_WORLD);
    double local_sum = 0.0;
    for (int i = 0; i < counts[rank]; i++) {
        local_sum += chunk[i] * chunk[i];
        chunk[i] = 2.0 * chunk[i] + 1.0;
    }
    MPI_Gatherv(chunk, counts[rank], MPI_DOUBLE, result, counts, offsets, MPI_DOUBLE, 0, MPI_COMM_WORLD);
    double total = 0.0;
    MPI_Reduce(&local_sum, &total, 1, MPI_DOUBLE, MPI_SUM, 0, MPI_COMM_WORLD);
    double elapsed = MPI_Wtime() - start;

    int ok = 1;
    if (rank == 0) {
        double sequential = 0.0;
        long wrong = 0;
        for (long i = 0; i < n; i++) {
            sequential += all[i] * all[i];
            if (result[i] != 2.0 * all[i] + 1.0) {
                wrong++;
            }
        }
        double relative = fabs(total - sequential) / sequential;
        ok = wrong == 0 && relative < 1e-12;
        printf("scatter_gather: %ld elements over %d processes (chunks %d..%d)\n", n, size, counts[size - 1],
               counts[0]);
        printf("  gathered transform: %ld wrong elements\n", wrong);
        printf("  reduced sum of squares %.6f, sequential %.6f, relative difference %.2e\n", total, sequential,
               relative);
        printf("  scatter + compute + gather + reduce: %.3f ms -> %s\n", elapsed * 1e3, ok ? "OK" : "MISMATCH");
        free(all);
        free(result);
    }
    MPI_Bcast(&ok, 1, MPI_INT, 0, MPI_COMM_WORLD);
    free(chunk);
    free(counts);
    free(offsets);
    MPI_Finalize();
    return ok ? 0 : 1;
}
