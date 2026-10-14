package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.Consumer;

/**
 * The JavaPoet literal builders for the generated classes — the {@code new ColumnRef.Simple(...)},
 * {@code new Predicate.of(...)} and {@code public static final String X = "..."} shapes that the
 * metadata descriptors and the query-definition holders both emit.
 *
 * <p>They live apart from {@link RepositoryProcessor} because two different emitters need the same
 * literal for the same value, and a literal that is built twice drifts: a change to how a
 * {@code ColumnRef} is spelled has to land in every emitter at once. Nothing here reads the
 * annotation-processing environment — every method is a pure function of its arguments, so the
 * literal for a given model value is the same wherever it is emitted from.
 */
public final class Literals {

    /** {@code ColumnRef} — the leaf of every generated placement and column map. */
    public static final ClassName MD_COLUMN_REF =
            ClassName.get("io.github.fludakit.data.metadata", "ColumnRef");
    /** {@code JoinEdge} — how a generated descriptor reaches another table. */
    public static final ClassName MD_JOIN_EDGE =
            ClassName.get("io.github.fludakit.data.metadata", "JoinEdge");
    /** {@code ColumnPlacement} — a resolved path: the terminal column plus the edges crossed. */
    public static final ClassName MD_COLUMN_PLACEMENT =
            ClassName.get("io.github.fludakit.data.metadata", "ColumnPlacement");

    // the query-metadata types the generated <Repository>Queries serialises literals with.
    public static final ClassName Q_PREDICATE =
            ClassName.get("io.github.fludakit.data.query", "Predicate");
    public static final ClassName Q_SORT_SPEC =
            ClassName.get("io.github.fludakit.data.query", "SortSpec");
    public static final ClassName Q_OPERATOR =
            ClassName.get("io.github.fludakit.data.query", "Operator");
    public static final ClassName Q_CONNECTOR =
            ClassName.get("io.github.fludakit.data.query", "Connector");
    public static final ClassName Q_SEEK_TERM =
            ClassName.get("io.github.fludakit.data.query", "SeekTerm");

    private Literals() {
    }

    public static CodeBlock listLiteral(Consumer<CodeBlock.Builder> items) {
        CodeBlock.Builder builder = CodeBlock.builder().add("$T.of(", ClassName.get(List.class));
        items.accept(builder);
        return builder.add(")").build();
    }

    public static CodeBlock sortListLiteral(List<SortSpec> sorts) {
        return listLiteral(builder -> {
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) {
                    builder.add(", ");
                }
                builder.add(sortLiteral(sorts.get(i)));
            }
        });
    }

    public static CodeBlock seekListLiteral(List<SeekTerm> terms) {
        return listLiteral(builder -> {
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) {
                    builder.add(", ");
                }
                SeekTerm term = terms.get(i);
                builder.add("new $T($L, $L)", Q_SEEK_TERM, columnRefLiteral(term.column()), term.ascending());
            }
        });
    }

    /**
     * The {@code ColumnRef} literal for one of the two placement cases ({@code Simple},
     * {@code ForeignKey}) — the shape the metadata generator emits for {@code columnRefs()} and
     * for the leaves of the generated {@code resolve} switch.
     *
     * <p>There is no literal for a relation edge here: an edge is a {@code JoinEdge}, emitted by
     * {@link #joinEdgeLiteral(JoinEdge)}. A column placement carries no table-to-table knowledge, so
     * the two never appear in the same literal.</p>
     */
    public static CodeBlock columnRefLiteral(ColumnRef columnRef) {
        if (columnRef instanceof ColumnRef.Simple(String column1, String table)) {
            return CodeBlock.of("new $T.Simple($S, $L)", MD_COLUMN_REF, column1,
                    nullableString(table));
        }
        if (columnRef instanceof ColumnRef.ForeignKey(String column, String ownerTable)) {
            return CodeBlock.of("new $T.ForeignKey($S, $L)", MD_COLUMN_REF, column,
                    nullableString(ownerTable));
        }
        throw new IllegalStateException("no literal for ColumnRef case: " + columnRef);
    }

    /**
     * The {@code JoinEdge} literal for one relation edge: {@code ToOne} for a to-one traversal,
     * {@code ToMany} for a to-many head. The case is part of the literal, so a generated descriptor
     * never loses the cardinality the way a bare {@code ColumnRef.Join} did.
     */
    public static CodeBlock joinEdgeLiteral(JoinEdge edge) {
        String variant = edge instanceof JoinEdge.ToOne ? "ToOne" : "ToMany";
        return CodeBlock.builder()
                .add("new $T.$L(", MD_JOIN_EDGE, variant)
                .add(columnRefLiteral(edge.owner())).add(", ")
                .add(columnRefLiteral(edge.child()))
                .add(")")
                .build();
    }

    /** {@code null}, or the quoted string — the same literal the generated code needs for either. */
    public static CodeBlock nullableString(String value) {
        return value == null ? CodeBlock.of("null") : CodeBlock.of("$S", value);
    }

    /** A {@code List.of(...)} literal of column refs for a generated {@code columns()} accessor. */
    public static CodeBlock columnRefList(List<ColumnRef> refs) {
        CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", ClassName.get(List.class));
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                list.add(", ");
            }
            list.add(columnRefLiteral(refs.get(i)));
        }
        return list.add(")").build();
    }

    /**
     * The local-only {@code Prop} literal for a generated {@code props()} accessor: the declared
     * property shape with a {@code null} relation target, because the generated descriptor overrides
     * {@code columnRefs()} and {@code ValueAccess.value()} and never walks the transitive mapping.
     */
    public static CodeBlock propLiteral(ClassName descriptor, Prop prop) {
        ClassName propClass = descriptor.nestedClass("Prop");
        if (prop instanceof Prop.Scalar(String name, String column, String typeFqn)) {
            return CodeBlock.of("new $T($S, $S, $S)", propClass.nestedClass("Scalar"),
                    name, column, typeFqn);
        }
        if (prop instanceof Prop.Reference reference) {
            return CodeBlock.of("new $T($S, $S, $S, null, $L, $L)", propClass.nestedClass("Reference"),
                    reference.name(), reference.fkColumn(), reference.targetTypeName(),
                    reference.composition(), reference.backReference());
        }
        if (prop instanceof Prop.Embedded embedded) {
            return CodeBlock.of("new $T($S, null)", propClass.nestedClass("Embedded"),
                    embedded.name());
        }
        if (prop instanceof Prop.EntityCollection entityCollection) {
            return CodeBlock.of("new $T($S, null, $S, $S)", propClass.nestedClass("EntityCollection"),
                    entityCollection.name(), entityCollection.inverseName(), entityCollection.inverseFkColumn());
        }
        if (prop instanceof Prop.ElementCollection elementCollection) {
            return CodeBlock.of("new $T($S, null, $L)", propClass.nestedClass("ElementCollection"),
                    elementCollection.name(), columnRefList(elementCollection.elementColumns()));
        }
        throw new IllegalStateException("unknown property kind: " + prop.getClass().getName());
    }

    public static CodeBlock predicateLiteral(Predicate predicate) {
        return CodeBlock.of("$T.of($S, $T.$L, $S, $T.$L, $L)", Q_PREDICATE, predicate.property(), Q_OPERATOR,
                predicate.operator().name(), predicate.param(), Q_CONNECTOR,
                predicate.connector().name(), predicate.ignoreCase());
    }

    public static CodeBlock sortLiteral(SortSpec sort) {
        return CodeBlock.of("$T.of($S, $L, $L)", Q_SORT_SPEC, sort.property(),
                sort.ascending(), sort.ignoreCase());
    }

    /** The UPPER_SNAKE constant name a column-bearing property publishes in its descriptor. */
    public static String constName(String property) {
        return Names.toSnakeCase(property).toUpperCase();
    }

    public static FieldSpec stringConstant(String name, String value) {
        return FieldSpec.builder(String.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", value).build();
    }

    /**
     * A {@code public String name()} override returning a compile-time constant — the descriptor's
     * {@code tableName()} / {@code typeName()} shape. A {@code null} value becomes the literal
     * {@code null}, which is what a value object (no table of its own) has to answer.
     */
    public static MethodSpec stringAccessor(String name, String value) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(String.class)
                .addStatement("return $L", nullableString(value)).build();
    }

    /**
     * Whether a value of this type can be read straight off a {@code ResultSet}. {@code byte[]} is the
     * one array the JDBC layer hands back as itself; everything else must be a known scalar.
     */
    public static boolean isJdbcSafe(TypeName type) {
        if (type instanceof ArrayTypeName array) {
            return array.componentType.equals(TypeName.BYTE);
        }
        return JdbcSafeTypes.contains(type.toString());
    }

    /**
     * Field types the reflection-free {@code <Entity>RowMapper} can read straight from a
     * {@code ResultSet} (via {@code getObject(...)} or a primitive getter). Any entity carrying a
     * field outside this allowlist (enums, non-embeddable types, ...) is skipped so it keeps
     * using the existing reflective mapping instead of generating code that would not compile.
     * {@code @ValueObject}-typed fields are not in this set: they pass the gate through the
     * {@code EMBEDDED} recursion in {@link RowMapperGenerator#canGenerateRowMapper} and are read leaf-by-leaf from
     * their flattened columns.
     * Note that {@code java.time.Instant}, {@code java.time.ZonedDateTime} and
     * {@code java.math.BigInteger} are read via {@code rs.getObject(col, X.class)}, whose driver
     * support beyond the types guaranteed by JDBC 4.2 ({@code LocalDate}/{@code LocalTime}/
     * {@code LocalDateTime}/{@code OffsetDateTime} and the basic types) is driver-dependent; an
     * unsupported driver fails fast at runtime with {@code SQLFeatureNotSupportedException}.
     */
    private static final java.util.Set<String> JdbcSafeTypes = java.util.Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.sql.Date", "java.sql.Time", "java.sql.Timestamp",
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime",
            "java.util.UUID",
            "boolean", "byte", "short", "int", "long", "float", "double");
}
