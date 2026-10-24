package io.github.fludakit.data.metadata;

import java.util.Objects;

/**
 * The physical join table of a many-to-many entity collection.
 *
 * <p>Both sides of a mutual {@link TypeDefinition.Prop.EntityCollection} pair share the SAME
 * canonical instance, so either direction resolves through the same table. The owner side is the
 * alphabetically-first type (by simple name): its {@code fkColumn}-derived column names the
 * {@link #ownerColumn()}, and the opposite side names the {@link #inverseColumn()}. The two
 * {@link TypeDefinition#typeName()} values are carried so a resolver can pick the column that names
 * its own side with {@link #columnFor(String)}.</p>
 *
 * @param table          the join table name (e.g. {@code author_book})
 * @param ownerColumn    the FK column naming the alphabetically-first side (e.g. {@code author_id})
 * @param inverseColumn  the FK column naming the opposite side (e.g. {@code book_id})
 * @param ownerType      the {@code typeName()} of the alphabetically-first side
 * @param inverseType    the {@code typeName()} of the opposite side
 */
public record JoinTable(String table, String ownerColumn, String inverseColumn,
                        String ownerType, String inverseType) {

    public JoinTable {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(ownerColumn, "ownerColumn");
        Objects.requireNonNull(inverseColumn, "inverseColumn");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(inverseType, "inverseType");
    }

    /**
     * The FK column on the join table that names the given side's type.
     *
     * @throws IllegalArgumentException when {@code typeFqn} is neither side of this join table
     */
    public String columnFor(String typeFqn) {
        if (typeFqn.equals(ownerType)) return ownerColumn;
        if (typeFqn.equals(inverseType)) return inverseColumn;
        throw new IllegalArgumentException(
                "type '" + typeFqn + "' is not a side of join table '" + table + "'");
    }
}
