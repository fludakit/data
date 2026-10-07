package io.github.fludakit.data.metadata;

import io.github.fludakit.data.metamodel.IdAttribute;
import io.github.fludakit.data.metamodel.TextColumnAttributeRecord;
import io.github.fludakit.data.page.CursorSupport;
import jakarta.data.metamodel.Attribute;
import jakarta.data.page.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the {@link EntityDescriptor#value(String, Object)} contract (the interface default throws,
 * an override answers local and relation dot paths, unknown paths fail fast) and the descriptor-driven
 * {@link CursorSupport#cursorFor(List, List, EntityDescriptor, Object)} overload's min/max reduction of
 * a to-many value list by sort direction.
 */
class EntityDescriptorValueTest {

    static class Node { }

    /** R2-style hand-rolled descriptor: exactly the interface members, NO value override. */
    record Bare<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                   List<Attribute<T>> attributes, List<ColumnRef> columns)
            implements EntityDescriptor<T> { }

    /** Overrides value for one to-many and one single-valued path, mirroring the generated switch. */
    record WithValues<T>(Class<T> entityClass, String tableName, IdAttribute<T> id,
                         List<Attribute<T>> attributes, List<ColumnRef> columns,
                         List<Object> itemsName, Object name) implements EntityDescriptor<T> {
        @Override
        public Object value(String dotPath, Object entity) {
            switch (dotPath) {
                case "items.name": {
                    return itemsName;
                }
                case "name": {
                    return name;
                }
                default: {
                    throw new IllegalArgumentException("Unknown property path '" + dotPath
                            + "' on " + entityClass.getSimpleName());
                }
            }
        }
    }

    private static EntityDescriptor<Node> bare() {
        return new Bare<>(Node.class, "bare_node", null,
                List.<Attribute<Node>>of(new TextColumnAttributeRecord<Node>("name", "name")),
                List.of(new ColumnRef("name", "name")));
    }

    private static EntityDescriptor<Node> withValues(List<Object> itemsName) {
        return new WithValues<>(Node.class, "node", null,
                List.<Attribute<Node>>of(new TextColumnAttributeRecord<Node>("name", "name")),
                List.of(new ColumnRef("name", "name")),
                itemsName, "z");
    }

    @Test
    void defaultThrowsUnsupportedOperationException() {
        EntityDescriptor<Node> descriptor = bare();

        assertThatThrownBy(() -> descriptor.value("name", new Object()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("descriptor " + descriptor.getClass().getSimpleName()
                        + " does not support value extraction");
    }

    @Test
    void cursorForReducesToManyListByDirection() {
        EntityDescriptor<Node> md = withValues(List.of("b", "a", "c"));
        Object node = new Object();

        PageRequest.Cursor asc =
                CursorSupport.cursorFor(List.of("items.name"), List.of(true), md, node);
        PageRequest.Cursor desc =
                CursorSupport.cursorFor(List.of("items.name"), List.of(false), md, node);

        assertThat(asc.get(0)).isEqualTo("a");
        assertThat(desc.get(0)).isEqualTo("c");
    }

    @Test
    void cursorForReducesEmptyToManyListToNullKey() {
        EntityDescriptor<Node> md = withValues(List.of());

        PageRequest.Cursor cursor =
                CursorSupport.cursorFor(List.of("items.name"), List.of(true), md, new Object());

        assertThat(cursor.get(0)).isNull();
    }

    @Test
    void cursorForPassesSingleValuesThroughAndComposesMultiKeyCursors() {
        EntityDescriptor<Node> md = withValues(List.of("b", "a", "c"));

        PageRequest.Cursor cursor = CursorSupport.cursorFor(
                List.of("name", "items.name"), List.of(false, true), md, new Object());

        assertThat(cursor.size()).isEqualTo(2);
        assertThat(cursor.get(0)).isEqualTo("z");
        assertThat(cursor.get(1)).isEqualTo("a");
    }

    @Test
    void overriddenValueStillFailsFastForUnknownPath() {
        EntityDescriptor<Node> md = withValues(List.of("b"));

        assertThatThrownBy(() -> md.value("nope", new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property path 'nope'")
                .hasMessageContaining("Node");
    }
}
