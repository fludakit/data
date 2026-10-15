package io.github.fludakit.data.processor.analysis;
import io.github.fludakit.data.processor.analysis.PathResolver;

/**
 * Where a query property came from, which decides what an UNRESOLVABLE property means.
 *
 * <p>Replaces the {@code boolean strict} that {@link PathResolver#resolveCompilePaths} used to take.
 * A boolean left the call site reading {@code resolveCompilePaths(..., declaredProperties)}, where
 * {@code true} meant "a property that does not resolve is a compile ERROR" — a meaning available only
 * from the callee's javadoc. Spelling the mode at the call site puts the consequence where the
 * decision is made.</p>
 *
 * <dl>
 *   <dt>{@link #DECLARED}</dt>
 *   <dd>The property was written down AS the query — a query-by-method-name segment, or a
 *       {@code @By} value. A typo, or a keyword suffix the lexer did not recognise, would otherwise
 *       silently generate a predicate against a column that does not exist, so failing to resolve is
 *       a compile ERROR and the method is skipped.</dd>
 *   <dt>{@link #INFERRED}</dt>
 *   <dd>The property was taken from a method PARAMETER name. The application chose those names for
 *       the signature, not as criteria, so a name that matches no mapped property falls back to its
 *       snake-cased column rather than failing the compile.</dd>
 * </dl>
 */
public enum PathOrigin {
    DECLARED, INFERRED;

    /** True for {@link #DECLARED}: an unresolvable path is an error rather than a fallback. */
    public boolean failsOnUnresolved() {
        return this == DECLARED;
    }
}
