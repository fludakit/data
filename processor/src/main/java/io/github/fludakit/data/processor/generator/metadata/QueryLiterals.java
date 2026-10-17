package io.github.fludakit.data.processor.generator.metadata;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import java.util.List;
import java.util.function.Consumer;

/**
 * The JavaPoet literal builders for the generated {@code <Repository>Queries} holder — the
 * {@code new Predicate.of(...)}, {@code new SortSpec.of(...)}, {@code new SeekTerm(...)} and
 * {@code List.of(...)} shapes a query-definition holder serialises.
 *
 * <p>Split from {@link Literals}, which stays for the descriptor literals ({@code ColumnRef},
 * {@code JoinEdge}, {@code Prop}): a query holder and a metadata descriptor emit two different type
 * families, so they no longer share one class.
 */
public final class QueryLiterals {

    public static final ClassName Q_PREDICATE = ClassName.get("io.github.fludakit.data.query", "Predicate");
    public static final ClassName Q_SORT_SPEC = ClassName.get("io.github.fludakit.data.query", "SortSpec");
    public static final ClassName Q_OPERATOR = ClassName.get("io.github.fludakit.data.query", "Operator");
    public static final ClassName Q_CONNECTOR = ClassName.get("io.github.fludakit.data.query", "Connector");
    public static final ClassName Q_SEEK_TERM = ClassName.get("io.github.fludakit.data.query", "SeekTerm");

    private QueryLiterals() {
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
                builder.add("new $T($L, $L)", Q_SEEK_TERM, Literals.columnRefLiteral(term.column()), term.ascending());
            }
        });
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
}
