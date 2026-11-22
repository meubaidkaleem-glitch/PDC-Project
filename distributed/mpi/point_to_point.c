/*
 * Point-to-point communication with MPI_Send and MPI_Recv.
 *
 * 1. Ring: a token travels from rank 0 through every rank and back; each rank adds its rank number, so rank 0
 *    can check that the message really passed through all processes.
 * 2. Ping-pong: ranks 0 and 1 bounce messages of growing size; rank 0 reports the round-trip latency and the
 *    bandwidth, and both sides verify the payload.
 *
 * Run with at least 2 processes: mpirun -np 4 ./point_to_point
 */
#include <mpi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG_RING 1
#define TAG_PING 2
#define REPETITIONS 50

static int ring(int rank, int size) {
    long token;
    if (rank == 0) {
        token = 0;
        MPI_Send(&token, 1, MPI_LONG, 1 % size, TAG_RING, MPI_COMM_WORLD);
        MPI_Recv(&token, 1, MPI_LONG, size - 1, TAG_RING, MPI_COMM_WORLD, MPI_STATUS_IGNORE);
        long expected = (long) size * (size - 1) / 2;
        printf("ring: token visited %d processes, sum of ranks %ld (expected %ld) %s\n", size, token, expected,
               token == expected ? "OK" : "MISMATCH");
        return token == expected ? 0 : 1;
    }
    MPI_Recv(&token, 1, MPI_LONG, rank - 1, TAG_RING, MPI_COMM_WORLD, MPI_STATUS_IGNORE);
    token += rank;
    MPI_Send(&token, 1, MPI_LONG, (rank + 1) % size, TAG_RING, MPI_COMM_WORLD);
    return 0;
}

static int ping_pong(int rank) {
    int errors = 0;
    if (rank > 1) {
        return 0;
    }
    if (rank == 0) {
        printf("ping-pong between ranks 0 and 1 (%d round trips per size):\n", REPETITIONS);
        printf("  %10s  %14s  %14s\n", "bytes", "latency (us)", "bandwidth (MB/s)");
    }
    for (int bytes = 1; bytes <= (4 << 20); bytes *= 16) {
        unsigned char *buffer = malloc((size_t) bytes);
        if (buffer == NULL) {
            fprintf(stderr, "out of memory\n");
            MPI_Abort(MPI_COMM_WORLD, 2);
        }
        for (int i = 0; i < bytes; i++) {
            buffer[i] = (unsigned char) (i * 31 + 7);
        }
        double start = MPI_Wtime();
        for (int r = 0; r < REPETITIONS; r++) {
            if (rank == 0) {
                MPI_Send(buffer, bytes, MPI_UNSIGNED_CHAR, 1, TAG_PING, MPI_COMM_WORLD);
                MPI_Recv(buffer, bytes, MPI_UNSIGNED_CHAR, 1, TAG_PING, MPI_COMM_WORLD, MPI_STATUS_IGNORE);
            } else {
                MPI_Recv(buffer, bytes, MPI_UNSIGNED_CHAR, 0, TAG_PING, MPI_COMM_WORLD, MPI_STATUS_IGNORE);
                MPI_Send(buffer, bytes, MPI_UNSIGNED_CHAR, 0, TAG_PING, MPI_COMM_WORLD);
            }
        }
        double elapsed = MPI_Wtime() - start;
        for (int i = 0; i < bytes; i++) {
            if (buffer[i] != (unsigned char) (i * 31 + 7)) {
                errors++;
                break;
            }
        }
        if (rank == 0) {
            double round_trip = elapsed / REPETITIONS;
            printf("  %10d  %14.2f  %14.1f\n", bytes, round_trip * 1e6, 2.0 * bytes / round_trip / 1e6);
        }
        free(buffer);
    }
    return errors;
}

int main(int argc, char **argv) {
    MPI_Init(&argc, &argv);
    int rank;
    int size;
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    MPI_Comm_size(MPI_COMM_WORLD, &size);
    if (size < 2) {
        if (rank == 0) {
            fprintf(stderr, "point_to_point needs at least 2 processes\n");
        }
        MPI_Finalize();
        return 1;
    }
    int errors = ring(rank, size);
    MPI_Barrier(MPI_COMM_WORLD);
    errors += ping_pong(rank);
    int total = 0;
    MPI_Reduce(&errors, &total, 1, MPI_INT, MPI_SUM, 0, MPI_COMM_WORLD);
    if (rank == 0) {
        printf("point_to_point: %s\n", total == 0 ? "all messages verified" : "ERRORS");
    }
    MPI_Finalize();
    return total == 0 ? 0 : 1;
}
