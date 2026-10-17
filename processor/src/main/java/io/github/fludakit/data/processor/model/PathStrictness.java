package io.github.fludakit.data.processor.model;


/**
 * What an UNRESOLVABLE property means, decided by where the property came from.
 *
 * <dl>
 *   <dt>{@link #STRICT}</dt>
 *   <dd>The property was written down AS the query — a query-by-method-name segment or an
 *       {@code @By} value. A typo would otherwise silently query a column that does not exist, so an
 *       unresolvable property is a compile ERROR and the method is skipped.</dd>
 *   <dt>{@link #LENIENT}</dt>
 *   <dd>The property was a method PARAMETER name, chosen for the signature rather than as criteria;
 *       an unresolvable name degrades to its snake-cased column instead of failing the compile.</dd>
 * </dl>
 */
public enum PathStrictness {
    STRICT, LENIENT;

    /** True for {@link #STRICT}: an unresolvable path is an error rather than a fallback. */
    public boolean failsOnUnresolved() {
        return this == STRICT;
    }
}
