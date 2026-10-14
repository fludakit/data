package io.github.fludakit.data.sql;

import java.util.List;
import java.util.Objects;

/**
 * The keyset (cursor) seek predicate: the WHERE clause that positions a {@code CURSOR_NEXT} /
 * {@code CURSOR_PREVIOUS} page relative to the last row of the previous one.
 *
 * <p>Expanded over PRE-RENDERED sort terms as
 * {@code (t0 > :cursor0) OR (t0 = :cursor0 AND t1 > :cursor1) OR ...}.</p>
 *
 * <p>Two rules are load-bearing and both are easy to get wrong:</p>
 * <ul>
 * <li><b>Every PRECEDING key compares with {@code =}, never with the direction operator.</b> Using
 *     {@code >} there makes each disjunct a subset of the previous one, so the whole predicate
 *     degenerates to {@code t0 > :cursor0} and every row sharing the leading key becomes unreachable
 *     from page two onward.</li>
 * <li><b>Preceding keys use NULL-safe equality.</b> A cursor key is legitimately null — an empty or
 *     all-null to-many list reduces to a null edge — and {@code t = NULL} is UNKNOWN, not true, so a
 *     plain {@code =} would silently truncate the page.</li>
 * </ul>
 *
 * <p>Terms arrive already rendered (quoted, and for a relation-sorted read table-qualified or an
 * aggregate subquery), so they are spliced verbatim; callers pass the same terms the ORDER BY
 * renders, so seek and sort agree byte-for-byte.</p>
 */
public final class SeekClause {

    private SeekClause() {
    }

    /**
     * @param terms     the rendered sort terms, in cursor-key order
     * @param ascending the sort direction of each key
     * @param forward   {@code true} for {@code CURSOR_NEXT}, {@code false} for {@code CURSOR_PREVIOUS}
     * @return the parenthesised predicate, binding {@code :cursor0 .. :cursorN-1}
     * @throws IllegalArgumentException when no key is supplied, or the direction list does not match
     */
    public static String render(List<String> terms, List<Boolean> ascending, boolean forward) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(ascending, "ascending");
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("seek clause requires at least one cursor key");
        }
        if (terms.size() != ascending.size()) {
            throw new IllegalArgumentException("seek clause requires one direction per key: "
                    + terms.size() + " terms, " + ascending.size() + " directions");
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append('(');
            for (int j = 0; j <= i; j++) {
                if (j > 0) {
                    sb.append(" AND ");
                }
                sb.append(comparison(terms.get(j), j, i, ascending.get(j), forward));
            }
            sb.append(')');
        }
        return sb.append(')').toString();
    }

    private static String comparison(String term, int index, int last, boolean ascending, boolean forward) {
        String param = ":cursor" + index;
        if (index < last) {
            return "(" + term + " = " + param + " OR (" + term + " IS NULL AND " + param + " IS NULL))";
        }
        return term + (ascending == forward ? " > " : " < ") + param;
    }
}
