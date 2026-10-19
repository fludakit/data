package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ColumnMapping;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An upsert is the one statement with no portable spelling, so the form belongs to the database and
 * the text to the renderer. These tests pin both halves: which form each shipped dialect declares,
 * and what each form renders.
 */
class UpsertTest {

    static class Note { }

    @Test
    void eachShippedDialectDeclaresTheFormItsDatabaseAccepts() {
        assertEquals(UpsertForm.MERGE, H2Dialect.INSTANCE.upsertForm());
        assertEquals(UpsertForm.ON_CONFLICT, PostgreSqlDialect.INSTANCE.upsertForm());
    }

    @Test
    void aDialectThatDeclaresNothingIsASupportedAnswer() {
        // NONE is how a database with no one-statement upsert opts out; a caller falls back to an
        // UPDATE followed by an INSERT rather than failing to generate.
        SqlDialect silent = new SqlDialect() {
            @Override
            public String quoteIdentifier(String identifier) {
                return "\"" + identifier + "\"";
            }

            @Override
            public String limitClause(String limitExpression) {
                return " LIMIT " + limitExpression;
            }

            @Override
            public String offsetClause(String offsetExpression) {
                return " OFFSET " + offsetExpression;
            }
        };
        assertEquals(UpsertForm.NONE, silent.upsertForm());
        assertThatThrownBy(() -> new SqlBuilder(note()).dialect(silent).upsert())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no single-statement upsert");
    }

    @Test
    void h2SpellsItMergeWithTheIdentifierAsTheKey() {
        // The identifier must ride along — it is the match key — and the KEY list names it. The
        // USING (SELECT ...) form is NOT usable: H2 cannot infer the type of a bound parameter there.
        assertThat(new SqlBuilder(note()).upsert())
                .isEqualTo("MERGE INTO \"note\" (\"id\", \"title\") KEY (\"id\") VALUES (:id, :title)");
    }

    @Test
    void postgreSqlSpellsItInsertOnConflictDoUpdate() {
        // EXCLUDED is the row the INSERT tried to write, so the update half rebinds nothing and both
        // halves share the same named parameters. The identifier is the conflict target and is never
        // re-assigned, so it is absent from the SET list.
        assertThat(new SqlBuilder(note()).dialect(PostgreSqlDialect.INSTANCE).upsert())
                .isEqualTo("INSERT INTO \"note\" (\"id\", \"title\") VALUES (:id, :title)"
                        + " ON CONFLICT (\"id\") DO UPDATE SET \"title\" = EXCLUDED.\"title\"");
    }

    @Test
    void anUpsertCarriesTheIdentifierAnInsertMayOmit() {
        // The contrast an upsert exists to resolve: the same table renders an INSERT that drops the
        // identity column for a generated key, and an upsert that keeps it because it has to match on
        // something.
        SqlBuilder builder = new SqlBuilder(note());
        assertThat(builder.insert(false))
                .isEqualTo("INSERT INTO \"note\" (\"title\") VALUES (:title)");
        assertThat(builder.upsert()).contains("(\"id\", \"title\")");
    }

    @Test
    void anUpsertIsASaveThatReportsWhatTheMethodDeclares() {
        QueryDefinition.Upsert upsert = new QueryDefinition.Upsert(ResultKind.AFFECTED);
        assertEquals(Operation.SAVE, upsert.operation());
        assertEquals(ResultKind.AFFECTED, upsert.resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.Upsert().resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.Upsert(null).resultKind());
    }

    @Test
    void everyFormRendersTheSameColumnOrderSoTheSameParamsBind() {
        // Whichever form the database picks, the column list and therefore the parameter names must
        // be identical — the generated .param(...) chain does not know which spelling it feeds.
        String h2 = new SqlBuilder(note()).upsert();
        String pg = new SqlBuilder(note()).dialect(PostgreSqlDialect.INSTANCE).upsert();
        assertThat(h2).contains("(\"id\", \"title\")").contains("VALUES (:id, :title)");
        assertThat(pg).contains("(\"id\", \"title\") VALUES (:id, :title)");
    }

    static TypeDefinition note() {
        return TestMappings.aggregateRoot("Note", "note",
                new ColumnMapping("id", "id", "java.lang.Long", true, false),
                List.of(new ColumnMapping("title", "title", "java.lang.String", false, false)),
                List.of());
    }
}
