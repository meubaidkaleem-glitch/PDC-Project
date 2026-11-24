package org.districompute.app.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A restricted parser for polynomials in one variable, such as {@code 3x^2 - 2x + 1} or {@code -0.5*x^3 + 4}.
 * It produces the ascending coefficient arrays Core's numerical algorithms take ({@code [c0, c1, c2, ...]}).
 *
 * <p>Grammar ({@code docs/user-guide.md}): {@code polynomial = ["+"|"-"] term { ("+"|"-") term }},
 * {@code term = number ["*"] [variable ["^" exponent]] | variable ["^" exponent]}, where {@code number} is a
 * decimal number with an optional exponent ({@code 1.5e-3}) and {@code exponent} a non-negative integer of at most
 * {@link #MAX_DEGREE}. Nothing else is accepted: no functions, no parentheses, no names other than the variable.
 * The input is parsed, never evaluated.
 */
public final class PolynomialParser {

    /** Highest degree accepted (Core allows at most 64 coefficients). */
    public static final int MAX_DEGREE = 63;
    /** Longest expression accepted. */
    public static final int MAX_LENGTH = 2000;

    private final String text;
    private final char variable;
    private int pos;

    private PolynomialParser(String text, char variable) {
        this.text = text;
        this.variable = variable;
    }

    /**
     * Parses a polynomial.
     *
     * @param expression the expression
     * @param variable the variable (a single letter)
     * @return ascending coefficients, trailing zeros removed (at least one element)
     * @throws IllegalArgumentException with the position of the first error
     */
    public static double[] parse(String expression, char variable) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("The expression is empty");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The expression is longer than " + MAX_LENGTH + " characters");
        }
        if (!Character.isLetter(variable)) {
            throw new IllegalArgumentException("The variable must be a letter");
        }
        PolynomialParser parser = new PolynomialParser(expression.toLowerCase(Locale.ROOT),
                Character.toLowerCase(variable));
        return parser.polynomial();
    }

    /**
     * Formats coefficients as a polynomial (for confirmation in the user interface).
     *
     * @param coefficients ascending coefficients
     * @param variable variable
     * @return for example {@code 3x^2 - 2x + 1}
     */
    public static String format(double[] coefficients, char variable) {
        StringBuilder out = new StringBuilder();
        for (int degree = coefficients.length - 1; degree >= 0; degree--) {
            double c = coefficients[degree];
            if (c == 0 && !(degree == 0 && out.isEmpty())) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(c < 0 ? " - " : " + ");
            } else if (c < 0) {
                out.append('-');
            }
            double magnitude = Math.abs(c);
            if (magnitude != 1 || degree == 0) {
                out.append(number(magnitude));
            }
            if (degree >= 1) {
                out.append(variable);
            }
            if (degree >= 2) {
                out.append('^').append(degree);
            }
        }
        return out.toString();
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }

    private double[] polynomial() {
        List<double[]> terms = new ArrayList<>();
        skipSpaces();
        boolean negative = sign(true);
        terms.add(term(negative));
        skipSpaces();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != '+' && c != '-') {
                throw error("expected + or -");
            }
            pos++;
            skipSpaces();
            terms.add(term(c == '-'));
            skipSpaces();
        }
        int degree = 0;
        for (double[] term : terms) {
            degree = Math.max(degree, (int) term[1]);
        }
        double[] coefficients = new double[degree + 1];
        for (double[] term : terms) {
            coefficients[(int) term[1]] += term[0];
        }
        int length = coefficients.length;
        while (length > 1 && coefficients[length - 1] == 0) {
            length--;
        }
        double[] trimmed = new double[length];
        System.arraycopy(coefficients, 0, trimmed, 0, length);
        for (double c : trimmed) {
            if (!Double.isFinite(c)) {
                throw new IllegalArgumentException("A coefficient is not a finite number");
            }
        }
        return trimmed;
    }

    private boolean sign(boolean optional) {
        if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
            boolean negative = text.charAt(pos) == '-';
            pos++;
            skipSpaces();
            return negative;
        }
        if (!optional) {
            throw error("expected a sign");
        }
        return false;
    }

    /** Returns {coefficient, degree}. */
    private double[] term(boolean negative) {
        double coefficient = 1;
        boolean hasNumber = false;
        if (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
            coefficient = number();
            hasNumber = true;
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '*') {
                pos++;
                skipSpaces();
                if (pos >= text.length() || text.charAt(pos) != variable) {
                    throw error("expected " + variable + " after *");
                }
            }
        }
        int degree = 0;
        if (pos < text.length() && text.charAt(pos) == variable) {
            pos++;
            degree = 1;
            skipSpaces();
            if (pos < text.length() && text.charAt(pos) == '^') {
                pos++;
                skipSpaces();
                degree = exponent();
            }
        } else if (!hasNumber) {
            throw error("expected a number or " + variable);
        }
        return new double[] {negative ? -coefficient : coefficient, degree};
    }

    private double number() {
        int start = pos;
        while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
            pos++;
        }
        if (pos < text.length() && text.charAt(pos) == 'e' && variable != 'e') {
            int mark = pos;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = mark;
            }
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("invalid number");
        }
    }

    private int exponent() {
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw error("expected a non-negative integer exponent");
        }
        if (pos - start > 3) {
            throw error("the exponent is larger than " + MAX_DEGREE);
        }
        int value = Integer.parseInt(text.substring(start, pos));
        if (value > MAX_DEGREE) {
            pos = start;
            throw error("the exponent is larger than " + MAX_DEGREE);
        }
        return value;
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        String found = pos < text.length() ? "'" + text.charAt(pos) + "'" : "the end";
        return new IllegalArgumentException(what + " at position " + (pos + 1) + ", found " + found);
    }
}
