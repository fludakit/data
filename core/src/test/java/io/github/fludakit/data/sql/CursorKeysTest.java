package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.metadata.ValueAccess;
import jakarta.data.page.PageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks cursor-key construction: the min/max reduction of a to-many value list by sort
 * direction, the null edge, multi-key composition, and the non-comparable guard.
 */
class CursorKeysTest {

    static class Node { }

    /** Overrides navigation for one to-many and one single-valued path, mirroring generated code. */
    static class WithValues<T> implements ValueAccess<T> {
        private final List<Object> itemsName;
        private final Object name;

        WithValues(List<Object> itemsName, Object name) {
            this.itemsName = itemsName;
            this.name = name;
        }

        @Override
        public Object value(String path, T root) {
            switch (path) {
                case "items.name": {
                    return itemsName;
                }
                case "name": {
                    return name;
                }
                default: {
                    throw new UnknownAttributePathException(path, "Order");
                }
            }
        }
    }

    private static ValueAccess<Node> withValues(List<Object> itemsName) {
        return new WithValues<Node>(itemsName, "z");
    }

    @Test
    void reducesToManyListByDirection() {
        ValueAccess<Node> md = withValues(List.of("b", "a", "c"));
        Node node = new Node();

        PageRequest.Cursor asc = CursorKeys.forEntity(List.of("items.name"), List.of(true), md, node);
        PageRequest.Cursor desc = CursorKeys.forEntity(List.of("items.name"), List.of(false), md, node);

        assertThat(asc.get(0)).isEqualTo("a");
        assertThat(desc.get(0)).isEqualTo("c");
    }

    @Test
    void reducesEmptyToManyListToNullKey() {
        ValueAccess<Node> md = withValues(List.of());

        PageRequest.Cursor cursor =
                CursorKeys.forEntity(List.of("items.name"), List.of(true), md, new Node());

        assertThat(cursor.get(0)).isNull();
    }

    @Test
    void passesSingleValuesThroughAndComposesMultiKeyCursors() {
        ValueAccess<Node> md = withValues(List.of("b", "a", "c"));

        PageRequest.Cursor cursor = CursorKeys.forEntity(
                List.of("name", "items.name"), List.of(false, true), md, new Node());

        assertThat(cursor.size()).isEqualTo(2);
        assertThat(cursor.get(0)).isEqualTo("z");
        assertThat(cursor.get(1)).isEqualTo("a");
    }

    @Test
    void failsFastOnNonComparableListElements() {
        // A cursor sort through e.g. "workHistory.profile" yields a List<Profile> from metadata path
        // navigation; Profile is not Comparable, and silently ClassCastException-ing at page-two seek
        // time is worse than a named, immediate failure.
        ValueAccess<Node> md = withValues(List.of(new Node(), new Node()));

        assertThatThrownBy(() -> CursorKeys.forEntity(
                List.of("items.name"), List.of(true), md, new Node()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor sort on non-comparable path element");
    }

    @Test
    void rejectsMismatchedDirections() {
        ValueAccess<Node> md = withValues(List.of("b"));

        assertThatThrownBy(() -> CursorKeys.forEntity(
                List.of("name", "items.name"), List.of(true), md, new Node()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one direction per property");
    }
}
