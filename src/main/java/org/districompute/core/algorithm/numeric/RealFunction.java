package org.districompute.core.algorithm.numeric;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.Parameters;

/**
 * The explicit, extensible catalog of real functions supported by the numerical algorithms. Arbitrary expressions
 * are deliberately not supported (see the project brief). Every function has a closed-form antiderivative, which
 * gives an analytic reference value for verification, and computable bounds for its magnitude and fourth
 * derivative, which give rigorous error tolerances.
 *
 * @param kind function family
 * @param coefficients polynomial coefficients {@code a0, a1, …} (empty for non-polynomial kinds)
 */
public record RealFunction(Kind kind, double[] coefficients) {

    /** Supported function families. */
    public enum Kind {
        /** {@code a0 + a1·x + … + an·x^n}. */
        POLYNOMIAL,
        /** {@code sin(x)}. */
        SIN,
        /** {@code cos(x)}. */
        COS,
        /** {@code exp(x)}. */
        EXP
    }

    /** Largest supported polynomial degree plus one. */
    public static final int MAX_COEFFICIENTS = 64;

    /** Validates the components. */
    public RealFunction {
        Objects.requireNonNull(kind, "kind");
        coefficients = coefficients == null ? new double[0] : coefficients.clone();
        if (kind == Kind.POLYNOMIAL && coefficients.length == 0) {
            throw new IllegalArgumentException("A polynomial needs at least one coefficient");
        }
    }

    /**
     * Parameter schemas that describe a function.
     *
     * @return schemas for {@code function} and {@code coefficients}
     */
    public static List<ParameterSpec> parameters() {
        return List.of(
                ParameterSpec.choice("function", "Function to use",
                        Arrays.stream(Kind.values()).map(Enum::name).toList()).defaultValue("POLYNOMIAL").build(),
                ParameterSpec.decimalArray("coefficients",
                                "Polynomial coefficients a0, a1, ... (function = POLYNOMIAL)")
                        .length(1, MAX_COEFFICIENTS).range(-1e6, 1e6).defaultValue(List.of(0.0, 0.0, 1.0)).build());
    }

    /**
     * Builds the function described by validated parameters.
     *
     * @param parameters validated parameters
     * @return the function
     */
    public static RealFunction from(Parameters parameters) {
        Kind kind = parameters.getEnum("function", Kind.class);
        return new RealFunction(kind, kind == Kind.POLYNOMIAL ? parameters.getDoubleArray("coefficients") : null);
    }

    /**
     * Evaluates the function (Horner's rule for polynomials).
     *
     * @param x argument
     * @return f(x)
     */
    public double value(double x) {
        return switch (kind) {
            case POLYNOMIAL -> horner(coefficients, x);
            case SIN -> Math.sin(x);
            case COS -> Math.cos(x);
            case EXP -> Math.exp(x);
        };
    }

    /**
     * Evaluates an antiderivative F with F' = f.
     *
     * @param x argument
     * @return F(x)
     */
    public double antiderivative(double x) {
        return switch (kind) {
            case POLYNOMIAL -> {
                double[] integral = new double[coefficients.length + 1];
                for (int i = 0; i < coefficients.length; i++) {
                    integral[i + 1] = coefficients[i] / (i + 1);
                }
                yield horner(integral, x);
            }
            case SIN -> -Math.cos(x);
            case COS -> Math.sin(x);
            case EXP -> Math.exp(x);
        };
    }

    /**
     * Upper bound of |f| on {@code [a, b]}.
     *
     * @param a interval start
     * @param b interval end
     * @return the bound
     */
    public double maxAbs(double a, double b) {
        double m = Math.max(Math.abs(a), Math.abs(b));
        return switch (kind) {
            case POLYNOMIAL -> absPolynomial(coefficients, m);
            case SIN, COS -> 1.0;
            case EXP -> Math.exp(Math.max(a, b));
        };
    }

    /**
     * Upper bound of |f''''| on {@code [a, b]} (for Simpson's error term).
     *
     * @param a interval start
     * @param b interval end
     * @return the bound
     */
    public double maxAbsFourthDerivative(double a, double b) {
        double m = Math.max(Math.abs(a), Math.abs(b));
        return switch (kind) {
            case POLYNOMIAL -> {
                double bound = 0;
                for (int i = 4; i < coefficients.length; i++) {
                    bound += Math.abs(coefficients[i]) * (double) i * (i - 1) * (i - 2) * (i - 3) * Math.pow(m, i - 4);
                }
                yield bound;
            }
            case SIN, COS -> 1.0;
            case EXP -> Math.exp(Math.max(a, b));
        };
    }

    /**
     * Σ|a_i|·m^i, the scale used in Horner's rounding-error bound.
     *
     * @param coefficients polynomial coefficients
     * @param m |x|
     * @return the scale
     */
    public static double absPolynomial(double[] coefficients, double m) {
        double result = 0;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result * m + Math.abs(coefficients[i]);
        }
        return result;
    }

    /**
     * Horner evaluation of {@code a0 + a1·x + …}.
     *
     * @param coefficients coefficients, lowest degree first
     * @param x argument
     * @return the value
     */
    public static double horner(double[] coefficients, double x) {
        double result = 0;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result * x + coefficients[i];
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RealFunction f && kind == f.kind && Arrays.equals(coefficients, f.coefficients);
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + Arrays.hashCode(coefficients);
    }

    @Override
    public String toString() {
        return kind == Kind.POLYNOMIAL ? "POLYNOMIAL" + Arrays.toString(coefficients) : kind.name();
    }
}
