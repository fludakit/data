package io.github.fludakit.data.processor.generator.metadata;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
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

    // the metadata types columnRefLiteral / joinEdgeLiteral spell into a generated descriptor.
    private static final ClassName MD_COLUMN_REF =
            ClassName.get("io.github.fludakit.data.metadata", "ColumnRef");
    private static final ClassName MD_JOIN_EDGE =
            ClassName.get("io.github.fludakit.data.metadata", "JoinEdge");

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
            // The target is null exactly as for Reference / Embedded / EntityCollection: the descriptor
            // publishes placements, not the transitive mapping, and the child table / parent FK /
            // flattened columns all come from the overridden columnRefs() / columns(). Nothing at
            // runtime reads a descriptor prop's elementColumns, so the prop carries only its name.
            return CodeBlock.of("new $T($S, null)", propClass.nestedClass("ElementCollection"),
                    elementCollection.name());
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
}
