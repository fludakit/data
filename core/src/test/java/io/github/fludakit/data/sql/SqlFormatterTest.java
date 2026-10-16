package io.github.fludakit.data.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SQL formatter's three guarantees: it reflows a complete statement to a clause per line, it never
 * changes the SQL a database reads (every break was already a space, and a runtime splice fragment is
 * left byte-for-byte), and it is idempotent so a caller may format defensively.
 */
class SqlFormatterTest {

    @Test
    void opensEachClauseOnItsOwnLine() {
        assertThat(SqlFormatter.format("SELECT \"id\", \"name\" FROM \"item\" WHERE \"id\" = :id"))
                .isEqualTo("SELECT \"id\", \"name\"\nFROM \"item\"\nWHERE \"id\" = :id");
    }

    @Test
    void keepsDeleteFromOnOneLine() {
        assertThat(SqlFormatter.format("DELETE FROM \"item\" WHERE \"id\" = :id"))
                .isEqualTo("DELETE FROM \"item\"\nWHERE \"id\" = :id");
    }

    @Test
    void indentsEachUpdateSetItem() {
        assertThat(SqlFormatter.format("UPDATE \"item\" SET \"a\" = :a, \"b\" = :b WHERE \"id\" = :id"))
                .isEqualTo("UPDATE \"item\"\nSET \"a\" = :a,\n    \"b\" = :b\nWHERE \"id\" = :id");
    }

    @Test
    void indentsASubqueryBodyUnderItsParen() {
        // The engine renders EXISTS with no space after the opening paren, so the SELECT stays welded
        // to it; only a clause that already had a space opens a new line, and the FROM inside the
        // subquery is indented by the paren depth.
        assertThat(SqlFormatter.format("SELECT 1 FROM \"t\" WHERE EXISTS (SELECT 1 FROM \"u\")"))
                .isEqualTo("SELECT 1\nFROM \"t\"\nWHERE EXISTS (SELECT 1\n    FROM \"u\")");
    }

    @Test
    void leavesARuntimeSpliceFragmentByteForByte() {
        // The paged/keyset LIMIT is a whole statement with the row count concatenated at RUNTIME, so its
        // trailing space is load-bearing and a text block would strip it. Leading space (a spliced tail)
        // is guarded the same way.
        String fragment = "SELECT \"id\" FROM \"item\" ORDER BY \"id\" ASC LIMIT ";
        assertThat(SqlFormatter.format(fragment)).isEqualTo(fragment);
        assertThat(SqlFormatter.format(" ORDER BY \"id\" ASC")).isEqualTo(" ORDER BY \"id\" ASC");
    }

    @Test
    void formatsAnEmptyInputToItself() {
        assertThat(SqlFormatter.format(null)).isNull();
        assertThat(SqlFormatter.format("")).isEmpty();
        assertThat(SqlFormatter.format("   ")).isEqualTo("   ");
    }

    @Test
    void isIdempotentAndReversible() {
        String sql = "INSERT INTO \"item\" (\"id\", \"name\") VALUES (:id, :name)";
        String once = SqlFormatter.format(sql);
        assertThat(SqlFormatter.format(once)).isEqualTo(once);
        // Reversible: every inserted break replaced an existing space, so folding whitespace restores
        // the logical statement.
        assertThat(once.replaceAll("\\s+", " ")).isEqualTo(sql);
    }
}
