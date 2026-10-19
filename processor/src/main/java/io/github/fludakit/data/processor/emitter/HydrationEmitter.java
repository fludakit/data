package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.Modifier;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates hydration methods for loading to-many collections on aggregate root entities.
 *
 * <p>After the main query returns parent entities, hydration methods execute separate queries
 * for each {@link RelationKind#TO_MANY} and {@link RelationKind#ELEMENT_COLLECTION},
 * group results by parent FK, and set them on the parent entity.</p>
 *
 * <p>Generated methods:</p>
 * <ul>
 *   <li>{@code hydrate(Entity entity)} — umbrella method that calls per-collection loaders</li>
 *   <li>{@code hydrateList(List<Entity> entities)} — batch hydration for list results</li>
 *   <li>{@code loadCollectionName(Entity entity)} — per-collection loader (private)</li>
 * </ul>
 */
public final class HydrationEmitter {

    private final SqlDialect dialect;

    public HydrationEmitter(SqlDialect dialect) {
        this.dialect = dialect;
    }

    public HydrationEmitter() {
        this(io.github.fludakit.data.sql.H2Dialect.INSTANCE);
    }

    /**
     * Returns whether the entity has any to-many collections or composing references that need hydration.
     */
    public boolean hasCollectionsToHydrate(TypeDefinition entity) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.ToMany
                    || prop instanceof TypeDefinition.Prop.ElementCollection) {
                return true;
            }
            if (prop instanceof TypeDefinition.Prop.ToOne toOne && toOne.isComposition()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits the {@code hydrate(Entity entity)} umbrella method that calls all per-collection loaders.
     * Returns null if there are no to-many collections.
     */
    public MethodSpec emitHydrate(RepositoryContext ctx) {
        TypeDefinition entity = ctx.entity();
        if (!hasCollectionsToHydrate(entity)) {
            return null;
        }

        ClassName entityClass = ClassName.bestGuess(entity.typeName());
        MethodSpec.Builder builder = MethodSpec.methodBuilder("hydrate")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entity == null) return;\n");

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.ToMany toMany) {
                body.add("load$L(entity);\n", capitalize(toMany.propertyName()));
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                body.add("load$L(entity);\n", capitalize(ec.propertyName()));
            } else if (prop instanceof TypeDefinition.Prop.ToOne toOne && toOne.isComposition()) {
                body.add("load$L(entity);\n", capitalize(toOne.propertyName()));
            }
        }

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits the {@code hydrateList(List<Entity> entities)} method for batch hydration.
     * Returns null if there are no to-many collections.
     */
    public MethodSpec emitHydrateList(RepositoryContext ctx) {
        TypeDefinition entity = ctx.entity();
        if (!hasCollectionsToHydrate(entity)) {
            return null;
        }

        ClassName entityClass = ClassName.bestGuess(entity.typeName());
        MethodSpec.Builder builder = MethodSpec.methodBuilder("hydrateList")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), entityClass), "entities")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entities == null || entities.isEmpty()) return;\n");
        body.add("for ($T entity : entities) {\n", entityClass);
        body.add("    hydrate(entity);\n");
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits per-collection loader methods for each to-many collection.
     * Returns a list of method specs (may be empty).
     */
    public List<MethodSpec> emitCollectionLoaders(RepositoryContext ctx) {
        List<MethodSpec> methods = new ArrayList<>();
        TypeDefinition entity = ctx.entity();

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.ToMany toMany) {
                MethodSpec loader = emitEntityCollectionLoader(ctx, toMany);
                if (loader != null) {
                    methods.add(loader);
                }
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                MethodSpec loader = emitElementCollectionLoader(ctx, ec);
                if (loader != null) {
                    methods.add(loader);
                }
            } else if (prop instanceof TypeDefinition.Prop.ToOne toOne && toOne.isComposition()) {
                MethodSpec loader = emitCompositionReferenceLoader(ctx, toOne);
                if (loader != null) {
                    methods.add(loader);
                }
            }
        }

        return methods;
    }

    /**
     * Emits a loader method for an EntityCollection: queries the child table by parent FK,
     * maps results, and sets them on the parent entity.
     */
    private MethodSpec emitEntityCollectionLoader(RepositoryContext ctx,
                                                   TypeDefinition.Prop.ToMany toMany) {
        TypeDefinition.Entity childMapping = toMany.target();
        if (childMapping == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        ClassName childClass = ClassName.bestGuess(childMapping.typeName());
        String methodName = "load" + capitalize(toMany.propertyName());

        // Build SELECT SQL for children by parent FK
        String childTable = dialect.quoteIdentifier(childMapping.tableName());
        String fkColumn = dialect.quoteIdentifier(toMany.fkColumn());

        // Build column list from child mapping - include scalar columns AND TO_ONE FK columns
        // TO_ONE relations on entity collection elements are materialized as id-only partials
        List<String> columns = new ArrayList<>();
        TypeDefinition.Prop.Id childIdProp = idProp(childMapping);
        if (childIdProp != null) {
            columns.add(dialect.quoteIdentifier(childIdProp.columnName()) + " AS "
                    + dialect.quoteIdentifier(childIdProp.propertyName()));
        }
        for (TypeDefinition.Prop prop : childMapping.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s && !s.isId()) {
                columns.add(dialect.quoteIdentifier(s.columnName()) + " AS "
                        + dialect.quoteIdentifier(s.propertyName()));
            }
        }
        // Include TO_ONE FK columns so row mapper can create id-only partials for back-references
        for (TypeDefinition.Prop prop : childMapping.properties()) {
            if (prop instanceof TypeDefinition.Prop.ToOne toOneRel && toOneRel.fkColumn() != null) {
                // Use column name as alias (not property name) so RowMapper can find it
                columns.add(dialect.quoteIdentifier(toOneRel.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(toOneRel.fkColumn()));
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + toMany.propertyName().toUpperCase(), selectSql);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        // Determine the collection type (List<X>)
        String setterName = "set" + Character.toUpperCase(toMany.propertyName().charAt(0)) + toMany.propertyName().substring(1);

        // Derive child row mapper class name
        ClassName childRowMapper = deriveRowMapperType(childMapping.typeName());

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T<$T> items = jdbcClient\n", List.class, childClass);
        body.add("    .sql($L)\n", constName);
        body.add("    .param($S, entity.getId())\n", "parentId");
        body.add("    .query(new $T())\n", childRowMapper);
        body.add("    .list();\n");
        body.add("entity.$L(items);", setterName);

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits a loader method for an ElementCollection: queries the child table by parent FK,
     * maps results to value objects, and sets them on the parent entity.
     */
    private MethodSpec emitElementCollectionLoader(RepositoryContext ctx,
                                                    TypeDefinition.Prop.ElementCollection ec) {
        TypeDefinition.ValueObject voMapping = ec.target();
        if (voMapping == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String methodName = "load" + capitalize(ec.propertyName());

        // Build SELECT SQL for elements by parent FK
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());

        // Build column list from value object mapping - only scalar columns, NOT TO_ONE relations
        // TO_ONE relations on collection elements should remain null (not materialized during hydration)
        List<String> columns = new ArrayList<>();
        for (TypeDefinition.Prop prop : voMapping.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.columnName()) + " AS "
                        + dialect.quoteIdentifier(s.propertyName()));
            }
        }
        // NOTE: Do NOT include TO_ONE relation FK columns or EMBEDDED expansions - they should stay null during collection hydration

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + ec.propertyName().toUpperCase(), selectSql);

        // Determine element type
        String elementFqn = ec.targetTypeName();
        ClassName elementClass = ClassName.bestGuess(elementFqn);

        String setterName = "set" + Character.toUpperCase(ec.propertyName().charAt(0)) + ec.propertyName().substring(1);

        // Derive element row mapper class name
        ClassName elementRowMapper = deriveRowMapperType(elementFqn);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T<$T> items = jdbcClient\n", List.class, elementClass);
        body.add("    .sql($L)\n", constName);
        body.add("    .param($S, entity.getId())\n", "parentId");
        body.add("    .query(new $T())\n", elementRowMapper);
        body.add("    .list();\n");
        body.add("entity.$L(items);", setterName);

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits a loader method for a composing Reference: loads the full child entity by ID
     * from the child table, replacing the id-only partial set by the row mapper.
     */
    private MethodSpec emitCompositionReferenceLoader(RepositoryContext ctx,
                                                       TypeDefinition.Prop.ToOne ref) {
        TypeDefinition.Entity childMapping = ref.target();
        if (childMapping == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        ClassName childClass = ClassName.bestGuess(childMapping.typeName());
        String methodName = "load" + capitalize(ref.propertyName());

        // Build SELECT SQL for the child by ID
        String childTable = dialect.quoteIdentifier(childMapping.tableName());
        String idCol = childMapping.idColumn();
        String idColumn = dialect.quoteIdentifier(idCol);

        // Build column list from child mapping
        java.util.List<String> columns = new java.util.ArrayList<>();
        TypeDefinition.Prop.Id childIdProp = idProp(childMapping);
        if (childIdProp != null) {
            columns.add(dialect.quoteIdentifier(childIdProp.columnName()) + " AS "
                    + dialect.quoteIdentifier(childIdProp.propertyName()));
        }
        for (TypeDefinition.Prop prop : childMapping.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s && !s.isId()) {
                columns.add(dialect.quoteIdentifier(s.columnName()) + " AS "
                        + dialect.quoteIdentifier(s.propertyName()));
            }
        }
        for (TypeDefinition.Prop prop : childMapping.properties()) {
            if (prop instanceof TypeDefinition.Prop.ToOne toOneRel && toOneRel.fkColumn() != null) {
                columns.add(dialect.quoteIdentifier(toOneRel.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(toOneRel.propertyName()));
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + idColumn + " = :childId";
        String constName = ctx.registerConstant("HYDRATE_" + ref.propertyName().toUpperCase(), selectSql);

        // Derive child row mapper class name
        ClassName childRowMapper = deriveRowMapperType(childMapping.typeName());

        String getter = "get" + Character.toUpperCase(ref.propertyName().charAt(0)) + ref.propertyName().substring(1);
        String setter = "set" + Character.toUpperCase(ref.propertyName().charAt(0)) + ref.propertyName().substring(1);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entity.$L() != null && entity.$L().getId() != null) {\n", getter, getter);
        body.add("    $T full = jdbcClient\n", childClass);
        body.add("        .sql($L)\n", constName);
        body.add("        .param($S, entity.$L().getId())\n", "childId", getter);
        body.add("        .query(new $T())\n", childRowMapper);
        body.add("        .single();\n");
        body.add("    entity.$L(full);\n", setter);
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Returns the id Prop from a TypeDefinition, or null if none. */
    private static TypeDefinition.Prop.Id idProp(TypeDefinition def) {
        return switch (def) {
            case TypeDefinition.Entity e -> e.id();
            case TypeDefinition.Aggregate a -> a.id();
            default -> null;
        };
    }

    /**
     * Derives the row mapper class name for a given entity/value-object type.
     * Convention: {@code <package>.<SimpleName>RowMapper}.
     */
    private ClassName deriveRowMapperType(String typeFqn) {
        int lastDot = typeFqn.lastIndexOf('.');
        String pkg = lastDot > 0 ? typeFqn.substring(0, lastDot) : "";
        String simple = lastDot > 0 ? typeFqn.substring(lastDot + 1) : typeFqn;
        return ClassName.get(pkg, simple + "RowMapper");
    }
}
