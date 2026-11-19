package org.districompute.core.algorithm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version of an algorithm <em>contract</em> (parameters, result semantics, partition payload format),
 * independent of the Core library version. In JSON it is a plain string such as {@code "1.0.0"}.
 *
 * <ul>
 *   <li>MAJOR: incompatible change to parameters, results or payload format;</li>
 *   <li>MINOR: backward-compatible addition (for example a new optional parameter);</li>
 *   <li>PATCH: implementation change with an identical contract (for example a performance fix).</li>
 * </ul>
 *
 * @param major major version, at least 0
 * @param minor minor version, at least 0
 * @param patch patch version, at least 0
 */
public record AlgorithmVersion(int major, int minor, int patch) implements Comparable<AlgorithmVersion> {

    private static final String PART = "(0|[1-9]\\d{0,8})";
    private static final Pattern FORMAT = Pattern.compile("^" + PART + "\\." + PART + "\\." + PART + "$");

    /** Validates that all parts are non-negative. */
    public AlgorithmVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version parts must be non-negative");
        }
    }

    /**
     * Parses {@code MAJOR.MINOR.PATCH}.
     *
     * @param text version text
     * @return the version
     * @throws IllegalArgumentException if the text is not a valid semantic version
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AlgorithmVersion parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Version must not be null");
        }
        Matcher matcher = FORMAT.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid algorithm version '" + text + "': expected MAJOR.MINOR.PATCH");
        }
        return new AlgorithmVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    /**
     * Whether this (registered) version satisfies a requested version: same MAJOR and a MINOR at least as high.
     *
     * @param requested requested version
     * @return {@code true} if compatible
     */
    public boolean satisfies(AlgorithmVersion requested) {
        return major == requested.major && minor >= requested.minor;
    }

    @Override
    public int compareTo(AlgorithmVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        return result != 0 ? result : Integer.compare(patch, other.patch);
    }

    /**
     * Returns {@code MAJOR.MINOR.PATCH}.
     *
     * @return the version text
     */
    @JsonValue
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
