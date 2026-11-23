package org.districompute.distributed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.districompute.core.CoreVersion;
import org.districompute.distributed.cli.BenchCommand;
import org.districompute.distributed.cli.ClientCommand;
import org.districompute.distributed.cli.CoordinatorCommand;
import org.districompute.distributed.cli.Options;
import org.districompute.distributed.cli.WorkerCommand;

/**
 * Command-line entry point of the runnable jar ({@code java -jar districompute-distributed-...-all.jar}).
 * Subcommands are added as the components they start are implemented.
 */
public final class DistributedCli {

    private DistributedCli() {
    }

    /**
     * Runs a subcommand.
     *
     * @param args the subcommand and its options
     */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int status = run(Arrays.asList(args), out, err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * Runs a subcommand without exiting the JVM, for tests and embedding.
     *
     * @param args the subcommand and its options
     * @param out standard output
     * @param err error output
     * @return the process exit status
     */
    public static int run(List<String> args, PrintStream out, PrintStream err) {
        if (args.isEmpty() || "help".equals(args.get(0)) || "--help".equals(args.get(0))) {
            usage(out);
            return 0;
        }
        return switch (args.get(0)) {
            case "coordinator", "worker", "submit", "status", "advise", "bench" -> runCommand(args, out, err,
                    args.get(0));
            case "version" -> {
                out.println("districompute-distributed " + DistributedVersion.version() + " (core "
                        + CoreVersion.version() + ")");
                yield 0;
            }
            default -> {
                err.println("Unknown command: " + args.get(0));
                usage(err);
                yield 2;
            }
        };
    }

    private static int runCommand(List<String> args, PrintStream out, PrintStream err, String command) {
        try {
            List<String> rest = args.subList(1, args.size());
            return switch (command) {
                case "coordinator" -> CoordinatorCommand.run(Options.parse(rest, CoordinatorCommand.FLAGS), out);
                case "worker" -> WorkerCommand.run(Options.parse(rest, WorkerCommand.FLAGS), out);
                case "submit", "status", "advise" -> ClientCommand.run(command, Options.parse(rest,
                        ClientCommand.FLAGS), out);
                case "bench" -> BenchCommand.run(Options.parse(rest, BenchCommand.FLAGS), out);
                default -> throw new IllegalStateException(command);
            };
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar districompute-distributed-all.jar <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  coordinator  run a coordinator: --port 7650 --secret-file <file> [--bind 0.0.0.0]");
        out.println("               [--tls-keystore <p12> --tls-keystore-password <pw>] [--policy dynamic]");
        out.println("               [--http-port 8650 --http-bind 127.0.0.1 --http-token-file <file>]");
        out.println("               [--config <file>]");
        out.println("  worker       run a worker: --coordinator <host:port> --secret-file <file> [--id <id>]");
        out.println("               [--max-concurrent-tasks N] [--labels k=v,...] [--algorithms id,...]");
        out.println("               [--tls-truststore <p12> --tls-truststore-password <pw>] [--config <file>]");
        out.println("               [--fault-profile <file>]   (experiments only; see docs/fault-tolerance.md)");
        out.println("  submit       submit a task to a coordinator's HTTP API: <task.json> --api http://host:8650");
        out.println("               [--policy dynamic] [--max-workers N] [--override-reason <text>]");
        out.println("               [--wait [--timeout-s 3600]] [--token-file <file>]");
        out.println("  advise       ask which execution mode suits a task: <task.json> --api ...");
        out.println("  status       list workers, tasks and counters: --api ...");
        out.println("  bench        run the benchmark suite on this machine with real worker processes:");
        out.println("               [--suite standard|quick] [--output benchmark-results] [--repeat 5] [--warmup 1]");
        out.println("               [--only scaling,granularity,size,communication,imbalance] [--worker-heap-mb 1024]");
        out.println("  version      print the runtime and Core versions");
        out.println("  help         print this help");
        out.println();
        out.println("The shared secret comes from --secret-file or the DISTRICOMPUTE_SECRET environment variable.");
    }
}
