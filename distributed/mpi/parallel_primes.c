/*
 * A simple parallel computation: counting primes below n.
 *
 * The range [0, n) is split into one block per process (remainder-aware, like DistriCompute-Core's range
 * partitioner). Each process counts the primes in its block with a segmented sieve of Eratosthenes, and
 * MPI_Reduce adds the counts on rank 0. Rank 0 then checks the total against the known value of pi(n) when n is a
 * power of ten, and against its own sequential count otherwise, and reports the speedup over that sequential run.
 * The same problem is DistriCompute-Core's "primes.count", so results can be compared with the Java runtime.
 *
 * Run: mpirun -np 4 ./parallel_primes [n]
 */
#include <math.h>
#include <mpi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SEGMENT 32768

/* Counts primes p with low <= p < high using a segmented sieve. */
static int64_t count_primes(int64_t low, int64_t high) {
    if (high <= 2 || low >= high) {
        return 0;
    }
    if (low < 2) {
        low = 2;
    }
    int64_t root = (int64_t) sqrt((double) high) + 1;
    char *small = calloc((size_t) root + 1, 1);
    int64_t *primes = malloc(((size_t) root + 1) * sizeof *primes);
    char *segment = malloc(SEGMENT);
    if (small == NULL || primes == NULL || segment == NULL) {
        MPI_Abort(MPI_COMM_WORLD, 2);
    }
    int64_t count_small = 0;
    for (int64_t i = 2; i <= root; i++) {
        if (!small[i]) {
            primes[count_small++] = i;
            for (int64_t j = i * i; j <= root; j += i) {
                small[j] = 1;
            }
        }
    }
    int64_t count = 0;
    for (int64_t start = low; start < high; start += SEGMENT) {
        int64_t end = start + SEGMENT < high ? start + SEGMENT : high;
        memset(segment, 0, (size_t) (end - start));
        for (int64_t k = 0; k < count_small; k++) {
            int64_t p = primes[k];
            if (p * p >= end) {
                break;
            }
            int64_t first = (start + p - 1) / p * p;
            if (first < p * p) {
                first = p * p;
            }
            for (int64_t m = first; m < end; m += p) {
                segment[m - start] = 1;
            }
        }
        for (int64_t i = start; i < end; i++) {
            count += !segment[i - start];
        }
    }
    free(small);
    free(primes);
    free(segment);
    return count;
}

static int64_t known_pi(int64_t n) {
    switch (n) {
        case 10: return 4;
        case 100: return 25;
        case 1000: return 168;
        case 10000: return 1229;
        case 100000: return 9592;
        case 1000000: return 78498;
        case 10000000: return 664579;
        case 100000000: return 5761455;
        case 1000000000: return 50847534;
        default: return -1;
    }
}

int main(int argc, char **argv) {
    MPI_Init(&argc, &argv);
    int rank;
    int size;
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    MPI_Comm_size(MPI_COMM_WORLD, &size);
    int64_t n = argc > 1 ? atoll(argv[1]) : 100000000;
    MPI_Bcast(&n, 1, MPI_INT64_T, 0, MPI_COMM_WORLD);

    int64_t block = n / size;
    int64_t extra = n % size;
    int64_t low = rank * block + (rank < extra ? rank : extra);
    int64_t high = low + block + (rank < extra ? 1 : 0);

    MPI_Barrier(MPI_COMM_WORLD);
    double start = MPI_Wtime();
    int64_t local = count_primes(low, high);
    int64_t total = 0;
    MPI_Reduce(&local, &total, 1, MPI_INT64_T, MPI_SUM, 0, MPI_COMM_WORLD);
    double parallel = MPI_Wtime() - start;

    int ok = 1;
    if (rank == 0) {
        double sequential_start = MPI_Wtime();
        int64_t sequential = count_primes(0, n);
        double sequential_time = MPI_Wtime() - sequential_start;
        int64_t reference = known_pi(n);
        ok = total == sequential && (reference < 0 || total == reference);
        printf("parallel_primes: pi(%lld) with %d processes\n", (long long) n, size);
        printf("  parallel count   %lld in %.3f s\n", (long long) total, parallel);
        printf("  sequential count %lld in %.3f s (rank 0 alone)\n", (long long) sequential, sequential_time);
        if (reference >= 0) {
            printf("  known value      %lld\n", (long long) reference);
        }
        printf("  speedup %.2f, efficiency %.2f -> %s\n", sequential_time / parallel,
               sequential_time / parallel / size, ok ? "OK" : "MISMATCH");
    }
    MPI_Bcast(&ok, 1, MPI_INT, 0, MPI_COMM_WORLD);
    MPI_Finalize();
    return ok ? 0 : 1;
}
