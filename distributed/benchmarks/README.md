# Benchmark results

Each directory holds one run of `bench --suite standard`, unedited: `results.json` (every run, with the
environment) and `summary.md` (the generated tables). [docs/benchmarks.md](../docs/benchmarks.md) explains the
method and interprets the results.

| Directory | Machine | Commit of the measured code |
|---|---|---|
| [2026-11-23](2026-11-23) | Cloud VM, 4 vCPUs (Intel Xeon @ 2.30 GHz), 14 GB, Linux, OpenJDK 21.0.13; coordinator and workers on loopback | A jar built from the working tree that became the commit adding this directory: the runtime of `3bf1a14` plus the `bench` command. Two edits were made after the measurement, before the commit, for Checkstyle and SpotBugs: a line break in `WorkerProcesses`, and one shared `SecureRandom` in `BenchmarkRunner` |

To add a run:

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar bench --suite standard --output distributed/benchmarks/$(date +%F)
```

Then delete the `logs/` subdirectory (worker logs), or keep it out of the commit.
