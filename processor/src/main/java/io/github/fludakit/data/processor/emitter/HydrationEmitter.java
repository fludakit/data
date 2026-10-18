package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates hydration methods for loading to-many collections on aggregate root entities.
 *
 * <p>After the main query returns parent entities, hydration methods execute separate queries
 * for each {@link TypeMapping.Prop.EntityCollection} and {@link TypeMapping.Prop.ElementCollection},
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
     * Returns whether the entity has any to-many collections that need hydration.
     */
    public boolean hasCollectionsToHydrate(TypeMapping<?> entity) {
        for (TypeMapping.Prop prop : entity.props()) {
            if (prop instanceof TypeMapping.Prop.EntityCollection
                    || prop instanceof TypeMapping.Prop.ElementCollection) {
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
        TypeMapping<?> entity = ctx.entity();
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

        for (TypeMapping.Prop prop : entity.props()) {
            if (prop instanceof TypeMapping.Prop.EntityCollection ec) {
                body.add("load$L(entity);\n", capitalize(ec.name()));
            } else if (prop instanceof TypeMapping.Prop.ElementCollection ec) {
                body.add("load$L(entity);\n", capitalize(ec.name()));
            }
        }

        builder.addStatement(body.build());
        return builder.build();
    }

    /**
     * Emits the {@code hydrateList(List<Entity> entities)} method for batch hydration.
     * Returns null if there are no to-many collections.
     */
    public MethodSpec emitHydrateList(RepositoryContext ctx) {
        TypeMapping<?> entity = ctx.entity();
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

        builder.addStatement(body.build());
        return builder.build();
    }

    /**
     * Emits per-collection loader methods for each to-many collection.
     * Returns a list of method specs (may be empty).
     */
    public List<MethodSpec> emitCollectionLoaders(RepositoryContext ctx) {
        List<MethodSpec> methods = new ArrayList<>();
        TypeMapping<?> entity = ctx.entity();

        for (TypeMapping.Prop prop : entity.props()) {
            if (prop instanceof TypeMapping.Prop.EntityCollection ec) {
                MethodSpec loader = emitEntityCollectionLoader(ctx, ec);
                if (loader != null) {
                    methods.add(loader);
                }
            } else if (prop instanceof TypeMapping.Prop.ElementCollection ec) {
                MethodSpec loader = emitElementCollectionLoader(ctx, ec);
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
                                                   TypeMapping.Prop.EntityCollection ec) {
        TypeMapping<?> childMapping = ec.target();
        if (childMapping == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        ClassName childClass = ClassName.bestGuess(childMapping.typeName());
        String methodName = "load" + capitalize(ec.name());

        // Build SELECT SQL for children by parent FK
        String childTable = dialect.quoteIdentifier(childMapping.tableName());
        String fkColumn = dialect.quoteIdentifier(ec.inverseFkColumn());

        // Build column list from child mapping
        List<String> columns = new ArrayList<>();
        if (childMapping.id() != null) {
            columns.add(dialect.quoteIdentifier(childMapping.id().column()) + " AS "
                    + dialect.quoteIdentifier(childMapping.id().name()));
        }
        for (TypeMapping.Prop prop : childMapping.rowProps()) {
            if (prop instanceof TypeMapping.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.column()) + " AS "
                        + dialect.quoteIdentifier(s.name()));
            } else if (prop instanceof TypeMapping.Prop.Reference r && !r.backReference()) {
                columns.add(dialect.quoteIdentifier(r.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(r.name()));
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + ec.name().toUpperCase(), selectSql);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        // Determine the collection type (List<X>)
        String setterName = "set" + Character.toUpperCase(ec.name().charAt(0)) + ec.name().substring(1);

        // Derive child row mapper class name
        ClassName childRowMapper = deriveRowMapperType(childMapping.typeName());

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T<$T> items = jdbcClient\n", List.class, childClass);
        body.add("    .sql($L)\n", constName);
        body.add("    .param($S, entity.getId())\n", "parentId");
        body.add("    .query(new $T())\n", childRowMapper);
        body.add("    .list();\n");
        body.add("entity.$L(items);", setterName);

        builder.addStatement(body.build());
        return builder.build();
    }

    /**
     * Emits a loader method for an ElementCollection: queries the child table by parent FK,
     * maps results to value objects, and sets them on the parent entity.
     */
    private MethodSpec emitElementCollectionLoader(RepositoryContext ctx,
                                                    TypeMapping.Prop.ElementCollection ec) {
        TypeMapping<?> voMapping = ec.target();
        if (voMapping == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String methodName = "load" + capitalize(ec.name());

        // Build SELECT SQL for elements by parent FK
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.parentFkColumn());

        // Build column list from value object mapping
        List<String> columns = new ArrayList<>();
        for (TypeMapping.Prop prop : voMapping.props()) {
            if (prop instanceof TypeMapping.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.column()) + " AS "
                        + dialect.quoteIdentifier(s.name()));
            } else if (prop instanceof TypeMapping.Prop.Reference r) {
                columns.add(dialect.quoteIdentifier(r.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(r.name()));
            } else if (prop instanceof TypeMapping.Prop.Embedded emb) {
                if (emb.target() != null) {
                    for (TypeMapping.Prop ep : emb.target().props()) {
                        if (ep instanceof TypeMapping.Prop.Scalar es) {
                            columns.add(dialect.quoteIdentifier(
                                    io.github.fludakit.data.Names.columnBase(emb.name()) + "_" + es.column())
                                    + " AS "
                                    + dialect.quoteIdentifier(
                                    io.github.fludakit.data.Names.columnBase(emb.name()) + "_" + es.name()));
                        }
                    }
                }
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + ec.name().toUpperCase(), selectSql);

        // Determine element type
        String elementFqn = ec.elementTypeName();
        ClassName elementClass = ClassName.bestGuess(elementFqn);

        String setterName = "set" + Character.toUpperCase(ec.name().charAt(0)) + ec.name().substring(1);

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

        builder.addStatement(body.build());
        return builder.build();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
