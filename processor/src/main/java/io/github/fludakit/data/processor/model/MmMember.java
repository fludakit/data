package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.TypeMapping.Prop;

/**
 * One metamodel member in emission order: the list entry shared by the {@code _Entity} interface and
 * the navigable member classes it points at.
 *
 * <p>The member {@code identifier} and its UPPER {@code constant} are DERIVED from {@code path}
 * rather than stored: both are fixed rewrites of it ({@code '.'} → {@code '_'}, then uppercased),
 * and storing them put the same string into this record three times.
 *
 * @param path   the dot-separated attribute path
 * @param column the flattened physical column (prefix applied) for column-bearing props, {@code null}
 *               for a to-many or element-collection prop. It stays a component rather than a
 *               derivation because the prefix is contextual — only the walk that reached this member
 *               knows it, so it cannot be recomputed from {@code path} and {@code prop} alone
 * @param prop   the descriptor property whose sealed shape drives every kind-specific decision
 *               downstream
 */
public record MmMember(String path, String column, Prop prop) {

    /** The Java identifier for this member: the path with its dots flattened. */
    public String identifier() {
        return path.replace('.', '_');
    }

    /** The {@code static final String} constant naming this member's path. */
    public String constant() {
        return identifier().toUpperCase();
    }
}
