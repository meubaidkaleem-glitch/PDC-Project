package org.districompute.distributed;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.districompute.core.CoreVersion;
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
            case "coordinator", "worker" -> runCommand(args, out, err, args.get(0));
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
                default -> throw new IllegalStateException(command);
            };
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar districompute-distributed-all.jar <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  coordinator  run a coordinator: --port 7650 --secret-file <file> [--bind 0.0.0.0]");
        out.println("               [--tls-keystore <p12> --tls-keystore-password <pw>] [--config <file>]");
        out.println("  worker       run a worker: --coordinator <host:port> --secret-file <file> [--id <id>]");
        out.println("               [--max-concurrent-tasks N] [--labels k=v,...] [--algorithms id,...]");
        out.println("               [--tls-truststore <p12> --tls-truststore-password <pw>] [--config <file>]");
        out.println("  version      print the runtime and Core versions");
        out.println("  help         print this help");
        out.println();
        out.println("The shared secret comes from --secret-file or the DISTRICOMPUTE_SECRET environment variable.");
    }
}
