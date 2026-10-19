package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.List;
import java.util.Map;

/**
 * Builds the small mappings {@link SqlBuilderTest} needs using TypeDefinition records.
 */
final class TestMappings {

    private TestMappings() {
    }

    /** A plain entity with explicit columns and relations. */
    static TypeDefinition entity(String typeName, String tableName, TypeDefinition.Prop.Id idProp,
                                 List<TypeDefinition.Prop.Scalar> scalars, List<TypeDefinition.Prop> relations) {
        return new TypeDefinition.Entity(
                typeName,
                tableName,
                idProp,
                null, // no version
                scalars,
                List.of(), // no embedded
                filterType(relations, TypeDefinition.Prop.ToOne.class),
                filterType(relations, TypeDefinition.Prop.ToMany.class),
                filterType(relations, TypeDefinition.Prop.ElementCollection.class)
        );
    }

    /** An aggregate root with explicit columns and relations. */
    static TypeDefinition aggregateRoot(String typeName, String tableName, TypeDefinition.Prop.Id idProp,
                                        List<TypeDefinition.Prop.Scalar> scalars, List<TypeDefinition.Prop> relations) {
        return new TypeDefinition.Aggregate(
                typeName,
                tableName,
                idProp,
                null, // no version
                scalars,
                List.of(), // no embedded
                filterType(relations, TypeDefinition.Prop.ToOne.class),
                filterType(relations, TypeDefinition.Prop.ToMany.class),
                filterType(relations, TypeDefinition.Prop.ElementCollection.class),
                filterType(relations, TypeDefinition.Prop.Composition.class)
        );
    }

    /**
     * An aggregate root with an explicit columnRefs index.
     */
    static TypeDefinition aggregateRootWithRefs(String typeName, String tableName, TypeDefinition.Prop.Id idProp,
                                                List<TypeDefinition.Prop.Scalar> scalars, List<TypeDefinition.Prop> relations,
                                                Map<String, ColumnRef> columnRefs) {
        // For tests that need custom columnRefs, we'd need a wrapper - simplified for now
        return aggregateRoot(typeName, tableName, idProp, scalars, relations);
    }

    @SuppressWarnings("unchecked")
    private static <T extends TypeDefinition.Prop> List<T> filterType(List<TypeDefinition.Prop> props, Class<T> type) {
        return props.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
