package io.github.fludakit.data.processor.model;

import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.support.MemberNames;

/**
 * A mapped non-id property: the descriptor {@link Prop} plus the two facts the descriptor cannot
 * carry.
 *
 * <p>What {@code Prop} already knows is DERIVED here, not stored again — {@link #name()} and
 * {@link #column()} read through to it. This record exists to add what the runtime descriptor
 * cannot hold, not to restate what it does: a second stored copy is a second place for the two to
 * disagree, and a reader has to work out which one is authoritative.
 *
 * @param type          the erased Java type of the property, as a JavaPoet {@code TypeName}. The
 *                      descriptor carries the same type as an FQN {@code String}, because a
 *                      generated {@code <Entity>Metadata} ships in the user's jar and must not
 *                      depend on JavaPoet — this is the compile-time rendering of that string
 * @param prop          the descriptor property whose sealed shape drives the gate and the codegen
 *                      loops (embedded recursion, fk-only skips)
 * @param embeddedModel the compile-time {@link EntityModel} of an {@link Prop.Embedded} target — the
 *                      read mapper recurses on it — {@code null} otherwise or when the VO build
 *                      cycles. The descriptor holds the target's own {@code TypeMapping} instead,
 *                      and prints it as {@code null} in {@code <Entity>Metadata}; the two are
 *                      different views of the same value object, not the same thing twice
 */
public record Property(TypeName type, Prop prop, EntityModel embeddedModel) {

    /** The declared property name. {@link Prop#name()} is the single source. */
    public String name() {
        return prop.name();
    }

    /**
     * The physical column this property maps to (a leaf prefix for an EMBEDDED).
     *
     * <p>Derived through {@link MemberNames#memberColumn}, the one place that knows which member of each
     * {@link Prop} shape holds the column. It has to be a derivation rather than a stored field
     * because the answer is not uniformly a field of the prop: an {@code Embedded} carries no
     * column of its own and its column is a naming rule.
     */
    public String column() {
        return MemberNames.memberColumn(prop);
    }
}
