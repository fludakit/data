package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.PathAttribute;
import io.github.fludakit.data.metamodel.TextScalarAttributeRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the {@link EntityMetadata#navigate(String, Object)} contract (the interface default throws,
 * an override answers local and relation dot paths, unknown paths fail fast). The cursor keys built
 * from those navigated values are covered by {@code CursorKeysTest}.
 */
class EntityMetadataValueTest {

    static class Node { }

    /** Hand-written metadata without a navigation override. */
    record Bare<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                   List<PathAttribute<T>> attributes, List<ColumnRef> columns)
            implements EntityMetadata<T> {
        @Override
        public Stereotype stereotype() {
            return tableName == null ? Stereotype.VALUE_OBJECT : Stereotype.ENTITY;
        }
    }

    /** Overrides navigation for one to-many and one single-valued path, mirroring generated code. */
    record WithValues<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                         List<PathAttribute<T>> attributes, List<ColumnRef> columns,
                         List<Object> itemsName, Object name) implements EntityMetadata<T> {
        @Override
        public Stereotype stereotype() {
            return tableName == null ? Stereotype.VALUE_OBJECT : Stereotype.ENTITY;
        }

        @Override
        public Object navigate(String path, T root) {
            switch (path) {
                case "items.name": {
                    return itemsName;
                }
                case "name": {
                    return name;
                }
                default: {
                    throw new IllegalArgumentException("Unknown property path '" + path
                            + "' on " + entityClass.getSimpleName());
                }
            }
        }
    }

    private static EntityMetadata<Node> bare() {
        return new Bare<>(Node.class, "bare_node", null,
                List.<PathAttribute<Node>>of(new TextScalarAttributeRecord<Node>("name")),
                List.of(new ColumnRef("name")));
    }

    private static EntityMetadata<Node> withValues(List<Object> itemsName) {
        return new WithValues<>(Node.class, "node", null,
                List.<PathAttribute<Node>>of(new TextScalarAttributeRecord<Node>("name")),
                List.of(new ColumnRef("name")),
                itemsName, "z");
    }

    @Test
    void defaultThrowsUnsupportedOperationException() {
        EntityMetadata<Node> descriptor = bare();

        assertThatThrownBy(() -> descriptor.navigate("name", new Node()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(descriptor.getClass().getSimpleName()
                        + " does not support property navigation");
    }

    @Test
    void navigationOverrideFailsFastForUnknownPath() {
        EntityMetadata<Node> md = withValues(List.of("b"));

        assertThatThrownBy(() -> md.navigate("nope", new Node()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property path 'nope'")
                .hasMessageContaining("Node");
    }

    @Test
    void depthTwoRelationPathFailsFastNamingTheExactPath() {
        // C-2 honesty pin: generated navigation answers ONE relation hop by construction, so a
        // depth-2 path lands in the default branch and the IllegalArgumentException names the exact
        // path — the failure surface the README path-depth bullet documents for CURSOR page keys.
        EntityMetadata<Node> md = withValues(List.of("b"));

        assertThatThrownBy(() -> md.navigate("items.product.name", new Node()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property path 'items.product.name' on Node");
    }
}
