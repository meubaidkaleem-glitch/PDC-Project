package org.districompute.core.algorithm.numeric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class NumericAlgorithmsTest {

    private static final ExecutionContext CONTEXT = ExecutionContext.unmanaged();

    private static Parameters parameters(ComputeAlgorithm<?, ?> algorithm, Map<String, Object> raw) {
        return ParameterValidator.validate(algorithm.descriptor().parameters(), ParameterValues.normalizeAll(raw));
    }

    @Test
    void simpsonIsExactForCubicsAndAccurateForSmoothFunctions() {
        NumericIntegrationAlgorithm integrate = new NumericIntegrationAlgorithm();
        NumericIntegrationAlgorithm.Problem cubic = integrate.prepareInput(parameters(integrate, Map.of(
                "coefficients", List.of(1.0, -2.0, 0.0, 4.0), "lowerLimit", -1.0, "upperLimit", 2.0, "panels", 7)),
                CONTEXT);
        double cubicResult = integrate.computeSequential(cubic, CONTEXT);
        assertEquals(15.0, cubicResult, 1e-12, "x^3 terms integrate exactly: 3 - 3 + 15");
        assertTrue(integrate.verify(cubic, cubicResult, CONTEXT).passed());

        NumericIntegrationAlgorithm.Problem sine = integrate.prepareInput(parameters(integrate, Map.of(
                "function", "SIN", "lowerLimit", 0.0, "upperLimit", Math.PI, "panels", 1_000)), CONTEXT);
        double sineResult = integrate.computeSequential(sine, CONTEXT);
        assertEquals(2.0, sineResult, 1e-11);
        assertTrue(integrate.verify(sine, sineResult, CONTEXT).passed());
        assertFalse(integrate.verify(sine, sineResult + 1e-6, CONTEXT).passed(), "a wrong integral must be caught");
    }

    @Test
    void integrationRejectsEmptyIntervalsAndOverflow() {
        NumericIntegrationAlgorithm integrate = new NumericIntegrationAlgorithm();
        assertEquals(Violation.Codes.INCONSISTENT, integrate.validate(parameters(integrate,
                Map.of("lowerLimit", 1.0, "upperLimit", 1.0))).get(0).code());
        assertEquals(Violation.Codes.TOO_LARGE, integrate.validate(parameters(integrate,
                Map.of("function", "EXP", "lowerLimit", 0.0, "upperLimit", 800.0))).get(0).code());
    }

    @Test
    void polynomialValuesMatchDirectEvaluationAndCorruptionIsCaught() {
        PolynomialEvaluationAlgorithm evaluate = new PolynomialEvaluationAlgorithm();
        PolynomialEvaluationAlgorithm.Points points = evaluate.prepareInput(parameters(evaluate, Map.of(
                "coefficients", List.of(1.0, -3.0, 2.0), "input", "INLINE", "xValues", List.of(-1.0, 0.5, 2.0))),
                CONTEXT);
        double[] values = evaluate.computeSequential(points, CONTEXT);
        assertArrayEquals(new double[] {6.0, 0.0, 3.0}, values, 0.0);
        assertTrue(evaluate.verify(points, values, CONTEXT).passed());
        values[0] += 1e-9;
        assertFalse(evaluate.verify(points, values, CONTEXT).passed());

        PolynomialEvaluationAlgorithm.Points grid = evaluate.prepareInput(parameters(evaluate,
                Map.of("points", 5, "xMin", 0.0, "xMax", 1.0)), CONTEXT);
        assertArrayEquals(new double[] {0, 0.25, 0.5, 0.75, 1.0}, grid.values(), 0.0);
        assertEquals(Violation.Codes.TOO_LARGE, evaluate.validate(parameters(evaluate, Map.of(
                "coefficients", java.util.Collections.nCopies(60, 1e6), "xMin", -1e6, "xMax", 1e6))).get(0).code());
    }

    @Test
    void rootsOfPolynomialsWithSignChangesAreFoundWithinTolerance() {
        RootFindingAlgorithm roots = new RootFindingAlgorithm();
        RootFindingAlgorithm.Problem sqrt2 = roots.prepareInput(parameters(roots, Map.of()), CONTEXT);
        double[] found = roots.computeSequential(sqrt2, CONTEXT);
        assertEquals(2, found.length);
        assertEquals(-Math.sqrt(2), found[0], 1e-12);
        assertEquals(Math.sqrt(2), found[1], 1e-12);
        assertTrue(roots.verify(sqrt2, found, CONTEXT).passed());

        RootFindingAlgorithm.Problem cubic = roots.prepareInput(parameters(roots, Map.of(
                "coefficients", List.of(-6.0, 11.0, -6.0, 1.0), "lowerLimit", 0.0, "upperLimit", 4.0,
                "subintervals", 8)), CONTEXT);
        double[] cubicRoots = roots.computeSequential(cubic, CONTEXT);
        assertArrayEquals(new double[] {1, 2, 3}, cubicRoots, 1e-12, "roots on grid nodes are reported once");
        assertTrue(roots.verify(cubic, cubicRoots, CONTEXT).passed());
        assertFalse(roots.verify(cubic, new double[] {1, 2.5, 3}, CONTEXT).passed(), "2.5 is not a root");
    }

    @Test
    void doubleRootsWithoutSignChangeAreADocumentedLimitation() {
        RootFindingAlgorithm roots = new RootFindingAlgorithm();
        RootFindingAlgorithm.Problem square = roots.prepareInput(parameters(roots, Map.of(
                "coefficients", List.of(0.0, 0.0, 1.0), "lowerLimit", -1.0, "upperLimit", 2.0,
                "subintervals", 7)), CONTEXT);
        assertEquals(0, roots.computeSequential(square, CONTEXT).length, "x^2 touches zero without a sign change");
    }

    @Test
    void monteCarloIsPartitionInvariantAndStatisticallyVerified() {
        MonteCarloPiAlgorithm monteCarlo = new MonteCarloPiAlgorithm();
        MonteCarloPiAlgorithm.Problem problem = monteCarlo.prepareInput(parameters(monteCarlo,
                Map.of("samples", 2_000_000, "seed", 7)), CONTEXT);
        MonteCarloPiAlgorithm.Estimate estimate = monteCarlo.computeSequential(problem, CONTEXT);
        assertTrue(monteCarlo.verify(problem, estimate, CONTEXT).passed());
        assertEquals(Math.PI, estimate.value(), 5 * estimate.standardError());

        long split = monteCarlo.computePartition(new MonteCarloPiAlgorithm.Work(7, 0, 123_457), CONTEXT)
                + monteCarlo.computePartition(new MonteCarloPiAlgorithm.Work(7, 123_457, 2_000_000), CONTEXT);
        assertEquals(estimate.hits(), split, "the hit count does not depend on how samples are split");
        assertFalse(monteCarlo.verify(problem, new MonteCarloPiAlgorithm.Estimate(2_000_000, estimate.hits() + 20_000),
                CONTEXT).passed());
    }
}
