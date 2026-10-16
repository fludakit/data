package io.github.fludakit.data.sql;

import java.util.Set;

/**
 * Turns a single-line SQL statement into the clause-per-line layout the generated artifacts carry, so
 * a statement is READABLE in the source file that is written to disk.
 *
 * <p>Formatting is a property of the SQL, not of the file that happens to hold it: the value is
 * formatted once, when a statement is assembled, and every consumer (the generated
 * {@code <Repository>Impl}, a {@code @Query} passthrough, a test that pins a constant) reads the SAME
 * ready string. Nothing downstream re-flows it, so there is exactly one definition of "what formatted
 * SQL looks like."</p>
 *
 * <p>Three invariants make that safe:</p>
 * <ul>
 * <li><b>Reversible.</b> A line break is inserted only where the statement already had a space, so
 * folding every run of whitespace back to a single space reconstructs the input. Formatting can never
 * change the SQL a database sees — newline is whitespace.</li>
 * <li><b>Idempotent.</b> Formatting an already-formatted statement returns it unchanged, so a caller
 * may format defensively and a second pass cannot double-space.</li>
 * <li><b>Fragment-preserving.</b> A string with leading or trailing whitespace is a RUNTIME splice
 * fragment — the paged/keyset {@code ... ASC LIMIT } onto which the row count is concatenated, or a
 * {@code WHERE fk IN (} seed onto which the key list is appended. Its boundary whitespace is
 * load-bearing and a text block would strip it, so such a fragment is returned byte-for-byte.</li>
 * </ul>
 *
 * <p>A clause-leading keyword opens a new line at the current paren depth, a {@code SET}/{@code VALUES}
 * item sits one level deeper than its clause, and a subquery body is indented under the line that
 * opened it. A parenthesis stays welded to its neighbours (no {@code ( SELECT}, no {@code ) }) — the
 * statement arrived that way and the splice points must keep their exact tokens.</p>
 */
public final class SqlFormatter {

    /**
     * The words that head a clause and therefore open a new line. Multi-word clauses are represented by
     * their leading word ({@code GROUP}/{@code ORDER} for {@code GROUP BY}/{@code ORDER BY},
     * {@code LEFT}/{@code RIGHT}/{@code INNER}/{@code FULL}/{@code CROSS}/{@code JOIN} for the join
     * family) — the trailing words ride the same line.
     */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "SET", "VALUES",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS");

    /** The clauses whose comma-separated items each get their own indented line. */
    private static final Set<String> INDENTED_LIST_CLAUSES = Set.of("SET", "VALUES");

    private static final int INDENT_WIDTH = 4;

    private SqlFormatter() {
    }

    /**
     * The formatted statement as a multi-line string with RELATIVE indentation (no base indent): a
     * clause-leading keyword starts a new line at the current paren depth, a {@code SET}/{@code VALUES}
     * item is one level deeper than its clause, and a parenthesized subquery body is indented under the
     * line that opened it. A caller that renders a Java text block prepends its base indent to every
     * line; the compiler then strips that common margin, leaving the relative indentation here.
     *
     * <p>A null/blank statement or one carrying boundary whitespace (a runtime splice fragment) is
     * returned unchanged.</p>
     */
    public static String format(String sql) {
        if (sql == null || sql.isBlank() || !sql.equals(sql.strip())) {
            return sql;
        }
        String normalized = sql.replaceAll("\\s+", " ");
        StringBuilder out = new StringBuilder(normalized.length());
        int depth = 0;
        String clause = "";
        char quote = 0;

        int i = 0;
        int n = normalized.length();
        while (i < n) {
            char c = normalized.charAt(i);

            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    // A doubled quote is an embedded literal quote, not the end of the token.
                    if (i + 1 < n && normalized.charAt(i + 1) == quote) {
                        out.append(normalized.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    quote = 0;
                }
                i++;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }

            if (isWordChar(c)) {
                int start = i;
                while (i < n && isWordChar(normalized.charAt(i))) {
                    i++;
                }
                String word = normalized.substring(start, i);
                String upper = word.toUpperCase();
                // Break only where the statement already had a space (so the transform reverses), never
                // weld a keyword onto a preceding '(' nor split the leading token off the line start,
                // and keep DELETE FROM on one line.
                boolean precededBySpace = start > 0 && normalized.charAt(start - 1) == ' ';
                boolean deleteFrom = upper.equals("FROM") && currentLineTrimmed(out).equals("DELETE");
                if (precededBySpace && !deleteFrom && CLAUSE_KEYWORDS.contains(upper)) {
                    newLine(out, depth);
                    clause = upper;
                }
                out.append(word);
                continue;
            }

            if (c == '(') {
                depth++;
                out.append(c);
                i++;
                continue;
            }

            if (c == ')') {
                depth = Math.max(0, depth - 1);
                out.append(c);
                i++;
                continue;
            }

            if (c == ',') {
                out.append(c);
                if (depth == 0 && INDENTED_LIST_CLAUSES.contains(clause)) {
                    newLine(out, depth + 1);
                    // The one space that separated the items became the line break; consume it so the
                    // next item sits at exactly the indent level (and whitespace folding still reverses).
                    while (i + 1 < n && normalized.charAt(i + 1) == ' ') {
                        i++;
                    }
                }
                i++;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Closes the current line and opens the next at {@code depth} indent levels, turning the one space
     * that separated the tokens into the line break (so folding whitespace restores it).
     */
    private static void newLine(StringBuilder out, int depth) {
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        out.setLength(end);
        out.append('\n');
        out.append(" ".repeat(INDENT_WIDTH * depth));
    }

    /** The text after the last line break, with surrounding spaces removed. */
    private static String currentLineTrimmed(StringBuilder out) {
        return out.substring(out.lastIndexOf("\n") + 1).trim();
    }
}
