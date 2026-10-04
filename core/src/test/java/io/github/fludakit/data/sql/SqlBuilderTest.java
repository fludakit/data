package io.github.fludakit.data.sql;

import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.EntityMetadata;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlBuilderTest {

    static EntityMetadata customer() {
        return EntityMetadata.of(String.class, "customer",
                new EntityMetadata.Property("id", Long.class, EntityMetadata.Kind.ID, "id", null, null),
                List.of(new EntityMetadata.Property("name", String.class, EntityMetadata.Kind.SCALAR, "name", null, null)));
    }

    @Test
    void rendersSelectWithWhereAndOrder() {
        String sql = new SqlBuilder().selectBy(customer(),
                List.of(Predicate.of("name", Operator.EQ, "name")),
                List.of(SortSpec.of("id", true, false)), 0, null);
        assertThat(sql).isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name ORDER BY \"id\" ASC");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder().insert(customer()))
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }
}
