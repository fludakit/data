package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fluent {@link Sql} entry point: what a caller gets when they want to read the statement they
 * are building.
 *
 * <p>These tests assert the rendered SQL, because the whole point of the API is that the chain reads
 * like the statement it produces.</p>
 */
class SqlTest {

    static class Customer { }

    static class Book { }

    static class Author { }

    static TypeMapping<Customer> customer() {
        return TestMappings.aggregateRoot("Customer", "customer",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")),
                List.of(new ColumnRef.Simple("id", "customer"), new ColumnRef.Simple("name", "customer")));
    }

    static TypeMapping<Author> author() {
        return TestMappings.aggregateRoot("Author", "author",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")),
                List.of(new ColumnRef.Simple("id", "author"), new ColumnRef.Simple("name", "author")));
    }

    /** A book with a to-one {@code author} reference, so a projection can reach across it. */
    static TypeMapping<Book> book() {
        return TestMappings.aggregateRoot("Book", "book",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("title", "title", "java.lang.String"),
                        new Prop.Scalar("year", "year", "int"),
                        new Prop.Reference("author", "author_id", "Author", author(), true, false)),
                List.of(new ColumnRef.Simple("id", "book"),
                        new ColumnRef.Simple("title", "book"),
                        new ColumnRef.Simple("year", "book"),
                        new ColumnRef.ForeignKey("author_id", "book")));
    }

    @Test
    void selectProjectsEveryRootColumnWhenNoneIsAskedFor() {
        assertThat(Sql.select(customer()).toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\"");
    }

    @Test
    void selectReadsAsTheStatementItRenders() {
        String sql = Sql.select(book())
                .columns("title", "author.name")
                .where("year", Operator.GREATER_THAN_EQUAL)
                .orderBy(SortSpec.of("year", false, false))
                .limit(10)
                .toSql();
        assertThat(sql).isEqualTo("SELECT \"book\".\"title\", \"author\".\"name\" FROM \"book\""
                + " LEFT JOIN \"author\" AS \"author\" ON \"book\".\"author_id\" = \"author\".\"id\""
                + " WHERE \"book\".\"year\" >= :year"
                + " ORDER BY \"year\" DESC"
                + " LIMIT 10");
    }

    /**
     * The projection's join must reach the plan before it qualifies anything: without it the WHERE
     * term would stay bare {@code "year"} next to a qualified projection.
     */
    @Test
    void aProjectionDrivenJoinQualifiesTheWholeStatement() {
        assertThat(Sql.select(book()).columns("author.name").where("year", Operator.EQ).toSql())
                .contains("WHERE \"book\".\"year\" = :year");
    }

    @Test
    void andConjoinsAndOrDisjoins() {
        assertThat(Sql.select(customer()).where("name", Operator.EQ).and("id", Operator.NOT_EQ).toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name AND \"id\" <> :id");
        assertThat(Sql.select(customer()).where("name", Operator.EQ).or("id", Operator.EQ).toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name OR \"id\" = :id");
    }

    @Test
    void ascAndDescAreShorthandForOneSortSpec() {
        assertThat(Sql.select(customer()).asc("name").toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" ORDER BY \"name\" ASC");
        assertThat(Sql.select(customer()).desc("name").toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" ORDER BY \"name\" DESC");
    }

    @Test
    void offsetRendersAfterTheLimit() {
        assertThat(Sql.select(customer()).asc("name").limit(5).offset(20).toSql())
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\""
                        + " ORDER BY \"name\" ASC LIMIT 5 OFFSET 20");
    }

    @Test
    void aDialectThatOverridesTheCombinedClauseIsConsultedForIt() {
        // A limit AND an offset go out through ONE method — limitOffsetClause — not through
        // limitClause + offsetClause. The default composes the two, which is why pinning only the
        // separate methods (SqlBuilderTest does) still passes on a renderer that ignores the
        // override: the concatenation happens to be right for every dialect shipped today. A
        // database whose paging is one clause rather than two — ROWS n TO m, OFFSET n ROWS FETCH
        // NEXT m — overrides this method and is correct ONLY if the renderer asks for it. So the
        // fake dialect below overrides it to something that is deliberately NOT the concatenation of
        // its own two halves; "LIMIT 5 OFFSET 20" appearing here means the override was skipped.
        SqlDialect windowed = new SqlDialect() {
            @Override
            public String quoteIdentifier(String identifier) {
                return "[" + identifier + "]";
            }

            @Override
            public String limitClause(String limitExpression) {
                return " FETCH FIRST " + limitExpression + " ROWS ONLY";
            }

            @Override
            public String offsetClause(String offsetExpression) {
                return " SKIP " + offsetExpression;
            }

            @Override
            public String limitOffsetClause(String limitExpression, String offsetExpression) {
                return " ROWS " + offsetExpression + " TO " + limitExpression;
            }
        };
        assertThat(Sql.select(customer()).dialect(windowed).asc("name").limit(5).offset(20).toSql())
                .isEqualTo("SELECT [id], [name] FROM [customer]"
                        + " ORDER BY [name] ASC ROWS 20 TO 5");
        // …and a limit alone still goes through limitClause, so the combined override is not
        // consulted when only one bound is present.
        assertThat(Sql.select(customer()).dialect(windowed).limit(5).toSql())
                .isEqualTo("SELECT [id], [name] FROM [customer] FETCH FIRST 5 ROWS ONLY");
    }

    @Test
    void distinctFoldsIntoTheProjection() {
        assertThat(Sql.select(customer()).distinct().toSql())
                .isEqualTo("SELECT DISTINCT \"id\", \"name\" FROM \"customer\"");
    }

    @Test
    void distinctComposesWithAPredicate() {
        // DISTINCT is a projection modifier, so it stays on the draft stage: it can be stated before
        // the predicate rather than only after it. The fluent flag lands on the same model component
        // a Distinct method name would set, so both paths render identically.
        assertThat(Sql.select(customer()).distinct().where("name", Operator.EQ).toSql())
                .isEqualTo("SELECT DISTINCT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name");
    }

    @Test
    void countTakesPredicatesAndNothingElse() {
        assertThat(Sql.count(customer()).where("name", Operator.EQ).toSql())
                .isEqualTo("SELECT COUNT(*) FROM \"customer\" WHERE \"name\" = :name");
    }

    @Test
    void deleteByPredicateFiltersWithoutAProjection() {
        assertThat(Sql.delete(customer()).where("name", Operator.EQ).and("id", Operator.NOT_EQ).toSql())
                .isEqualTo("DELETE FROM \"customer\" WHERE \"name\" = :name AND \"id\" <> :id");
    }

    // --------------------------------------------------------------- lifecycle

    @Test
    void insertIncludesTheIdentifierByDefault() {
        assertThat(Sql.insert(customer()).toSql())
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }

    /** Binding an explicit NULL to an identity column is rejected by H2, so it must be omittable. */
    @Test
    void insertCanOmitADatabaseGeneratedIdentifier() {
        assertThat(Sql.insert(customer()).withIdentityGenerated().toSql())
                .isEqualTo("INSERT INTO \"customer\" (\"name\") VALUES (:name)");
    }

    @Test
    void updateSetsEveryNonIdentifierColumn() {
        assertThat(Sql.update(customer()).toSql())
                .isEqualTo("UPDATE \"customer\" SET \"name\" = :name WHERE \"id\" = :id");
    }

    @Test
    void deleteByIdTargetsTheIdentifier() {
        assertThat(Sql.deleteById(customer()).toSql())
                .isEqualTo("DELETE FROM \"customer\" WHERE \"id\" = :id");
    }

    @Test
    void everyStageHonoursAnOverriddenDialect() {
        SqlDialect bracketed = new SqlDialect() {
            @Override
            public String quoteIdentifier(String identifier) {
                return "[" + identifier + "]";
            }

            @Override
            public String limitClause(String limitExpression) {
                return " FETCH FIRST " + limitExpression + " ROWS ONLY";
            }

            @Override
            public String offsetClause(String offsetExpression) {
                return " SKIP " + offsetExpression;
            }
        };
        assertThat(Sql.select(customer()).dialect(bracketed).toSql())
                .isEqualTo("SELECT [id], [name] FROM [customer]");
        assertThat(Sql.select(customer()).dialect(bracketed).where("name", Operator.EQ)
                .asc("name").limit(3).toSql())
                .isEqualTo("SELECT [id], [name] FROM [customer] WHERE [name] = :name"
                        + " ORDER BY [name] ASC FETCH FIRST 3 ROWS ONLY");
        assertThat(Sql.deleteById(customer()).dialect(bracketed).toSql())
                .isEqualTo("DELETE FROM [customer] WHERE [id] = :id");
    }
}
