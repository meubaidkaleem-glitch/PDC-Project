package org.districompute.distributed;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.districompute.core.CoreVersion;

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

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar districompute-distributed-all.jar <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  version    print the runtime and Core versions");
        out.println("  help       print this help");
    }
}
