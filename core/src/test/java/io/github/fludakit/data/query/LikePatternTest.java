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

    @Test
    void theSubstringOperatorsDecorateTheBoundValue() {
        assertEquals("%dd%", LikePattern.of(Operator.CONTAINS).decorate("dd"));
        assertEquals("dd%", LikePattern.of(Operator.STARTS_WITH).decorate("dd"));
        assertEquals("%dd", LikePattern.of(Operator.ENDS_WITH).decorate("dd"));
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
        assertEquals("%DD%", LikePattern.of(Operator.LIKE).decorate("%DD%"));
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
        assertEquals(LikePattern.NONE, new ParamBinding(0, "text").pattern());
        assertEquals(LikePattern.BOTH, new ParamBinding(0, "text", LikePattern.BOTH).pattern());
        assertEquals("%dd%", new ParamBinding(0, "text", LikePattern.BOTH).pattern().decorate("dd"));
    }
}
