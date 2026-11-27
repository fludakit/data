package io.github.fludakit.data.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code %} rule is a fact about the OPERATOR, asserted here because it is the only place the
 * processor's generated {@code .param(...)} text and the published {@link ParamBinding} can both be
 * traced back to: a swap between STARTS_WITH and ENDS_WITH produces identical SQL and differs only
 * in which side the wildcard lands on.
 */
class LikePatternTest {

    /** The bound value as the generated {@code .param(...)} call builds it from the pattern. */
    private static String decorate(LikePattern pattern, String value) {
        return pattern.prefix() + value + pattern.suffix();
    }

    @Test
    void theSubstringOperatorsDecorateTheBoundValue() {
        assertEquals("%dd%", decorate(LikePattern.of(Operator.CONTAINS), "dd"));
        assertEquals("dd%", decorate(LikePattern.of(Operator.STARTS_WITH), "dd"));
        assertEquals("%dd", decorate(LikePattern.of(Operator.ENDS_WITH), "dd"));
    }

    @Test
    void aNegatedOperatorCarriesTheSameDecoration() {
        // The wildcard says WHAT is being matched; the negation only says which way the comparison
        // points, so folding it into the operator must not change the bound value.
        assertEquals(LikePattern.of(Operator.CONTAINS), LikePattern.of(Operator.NOT_CONTAINS));
        assertEquals(LikePattern.of(Operator.STARTS_WITH), LikePattern.of(Operator.NOT_STARTS_WITH));
        assertEquals(LikePattern.of(Operator.ENDS_WITH), LikePattern.of(Operator.NOT_ENDS_WITH));
    }

    @Test
    void likeBindsTheCallersOwnPattern() {
        // Like takes a PATTERN, not a substring: wrapping it would turn "%DD%" into "%%DD%%" and
        // make an anchored or single-character pattern impossible to express.
        assertEquals(LikePattern.NONE, LikePattern.of(Operator.LIKE));
        assertEquals(LikePattern.NONE, LikePattern.of(Operator.NOT_LIKE));
        assertEquals("%DD%", decorate(LikePattern.of(Operator.LIKE), "%DD%"));
    }

    @Test
    void everyOtherOperatorLeavesTheValueAlone() {
        for (Operator operator : Operator.values()) {
            if (operator == Operator.CONTAINS || operator == Operator.STARTS_WITH
                    || operator == Operator.ENDS_WITH || operator == Operator.NOT_CONTAINS
                    || operator == Operator.NOT_STARTS_WITH || operator == Operator.NOT_ENDS_WITH) {
                continue;
            }
            assertEquals(LikePattern.NONE, LikePattern.of(operator),
                    operator + " must bind its argument as-is");
        }
    }

    @Test
    void onlyTheSubstringFamilyIsWildcarded() {
        assertTrue(LikePattern.BOTH.wildcarded());
        assertTrue(LikePattern.LEADING.wildcarded());
        assertTrue(LikePattern.TRAILING.wildcarded());
        assertFalse(LikePattern.NONE.wildcarded());
        assertEquals("%", LikePattern.LEADING.prefix());
        assertEquals("", LikePattern.LEADING.suffix());
        assertEquals("", LikePattern.TRAILING.prefix());
        assertEquals("%", LikePattern.TRAILING.suffix());
    }

    @Test
    void aBindingWithoutAPatternBindsAsIs() {
        assertEquals(LikePattern.NONE, new ParamBinding(0, "text", null).pattern());
        assertEquals(LikePattern.BOTH, new ParamBinding(0, "text", LikePattern.BOTH).pattern());
        assertEquals("%dd%", decorate(new ParamBinding(0, "text", LikePattern.BOTH).pattern(), "dd"));
    }
}
