package org.districompute.distributed.cli;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Command-line options: {@code --name value}, {@code --name=value} or a bare {@code --flag} (meaning
 * {@code true}). {@code --config file.properties} loads defaults from a properties file whose keys are the option
 * names without dashes; options given on the command line win.
 */
public final class Options {

    private final Map<String, String> values;
    private final List<String> positional;

    private Options(Map<String, String> values, List<String> positional) {
        this.values = values;
        this.positional = positional;
    }

    /**
     * Parses arguments (after the subcommand name) that contain no boolean flags.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException for malformed arguments or an unreadable configuration file
     */
    public static Options parse(List<String> args) {
        return parse(args, Set.of());
    }

    /**
     * Parses arguments (after the subcommand name).
     *
     * @param args the arguments
     * @param flags names of boolean options; a bare {@code --flag} never takes the next argument as its value
     * @return the options
     * @throws IllegalArgumentException for malformed arguments or an unreadable configuration file
     */
    public static Options parse(List<String> args, Set<String> flags) {
        Map<String, String> cli = new LinkedHashMap<>();
        List<String> positional = new java.util.ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!arg.startsWith("--") || arg.length() == 2) {
                positional.add(arg);
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                cli.put(body.substring(0, eq), body.substring(eq + 1));
            } else if (!flags.contains(body) && i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                cli.put(body, args.get(++i));
            } else {
                cli.put(body, "true");
            }
        }
        Map<String, String> merged = new LinkedHashMap<>();
        String config = cli.get("config");
        if (config != null) {
            Properties file = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(config), StandardCharsets.UTF_8)) {
                file.load(reader);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read configuration file " + config + ": "
                        + e.getMessage(), e);
            }
            file.stringPropertyNames().forEach(key -> merged.put(key.trim(), file.getProperty(key).trim()));
        }
        merged.putAll(cli);
        return new Options(merged, List.copyOf(positional));
    }

    /**
     * Arguments that are not options.
     *
     * @return the arguments in order
     */
    public List<String> positional() {
        return positional;
    }

    /**
     * An option's value.
     *
     * @param name option name
     * @param defaultValue value if absent
     * @return the value
     */
    public String get(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    /**
     * Whether an option is present.
     *
     * @param name option name
     * @return {@code true} if given
     */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    /**
     * An integer option.
     *
     * @param name option name
     * @param defaultValue value if absent
     * @return the value
     */
    public int getInt(String name, int defaultValue) {
        String value = values.get(name);
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer, got " + value, e);
        }
    }

    /**
     * A duration option given in milliseconds.
     *
     * @param name option name
     * @param defaultValue value if absent
     * @return the value
     */
    public Duration getMillis(String name, Duration defaultValue) {
        String value = values.get(name);
        try {
            return value == null ? defaultValue : Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number of milliseconds, got " + value, e);
        }
    }

    /**
     * A boolean option.
     *
     * @param name option name
     * @param defaultValue value if absent
     * @return the value
     */
    public boolean getBoolean(String name, boolean defaultValue) {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException("--" + name + " must be true or false, got " + value);
        };
    }

    /**
     * Rejects options that the command does not know, so typos do not go unnoticed.
     *
     * @param known option names the command accepts
     * @throws IllegalArgumentException naming the unknown options
     */
    public void requireKnown(Set<String> known) {
        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(known);
        unknown.remove("config");
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s): --" + String.join(", --", unknown));
        }
    }
}
