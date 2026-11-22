# MPI demonstrations for DistriCompute (separate from the Java runtime).
#   make            build all programs
#   make run        run them with NP processes (default 4)
#   make clean
MPICC ?= mpicc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
LDLIBS = -lm
NP ?= 4
PROGRAMS = point_to_point broadcast scatter_gather parallel_primes

all: $(PROGRAMS)

%: %.c
	$(MPICC) $(CFLAGS) -o $@ $< $(LDLIBS)

run: all
	NP=$(NP) ./run-demos.sh

clean:
	rm -f $(PROGRAMS)

.PHONY: all run clean
