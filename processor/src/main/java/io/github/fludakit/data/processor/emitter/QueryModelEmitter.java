package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.LikePattern;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.util.List;

/**
 * Generates {@code XXXRepositoryQueryModel} classes containing static
 * {@link io.github.fludakit.data.query.QueryDefinition} fields — one per query method.
 *
 * <p>Each field is a {@code QueryDefinition} variant matching the method's operation:</p>
 * <ul>
 *   <li>{@code FIND} &rarr; {@code QueryDefinition.Read}</li>
 *   <li>{@code COUNT} &rarr; {@code QueryDefinition.Count}</li>
 *   <li>{@code EXISTS} &rarr; {@code QueryDefinition.Exists}</li>
 *   <li>{@code DELETE} (with predicates) &rarr; {@code QueryDefinition.DeleteWhere}</li>
 *   <li>{@code DELETE} (without predicates) &rarr; {@code QueryDefinition.DeleteById}</li>
 *   <li>{@code SAVE} &rarr; {@code QueryDefinition.Upsert}</li>
 *   <li>{@code INSERT} &rarr; {@code QueryDefinition.Insert}</li>
 *   <li>{@code UPDATE} &rarr; {@code QueryDefinition.Update}</li>
 * </ul>
 *
 * <p>Naming: repository {@code CustomerRepository} produces {@code CustomerRepositoryQueryModel}
 * in the same package.</p>
 */
public final class QueryModelEmitter {

    private static final String QUERY_PACKAGE = "io.github.fludakit.data.query";

    private static final ClassName QUERY_DEFINITION =
            ClassName.get(QUERY_PACKAGE, "QueryDefinition");
    private static final ClassName QD_READ = QUERY_DEFINITION.nestedClass("Read");
    private static final ClassName QD_COUNT = QUERY_DEFINITION.nestedClass("Count");
    private static final ClassName QD_EXISTS = QUERY_DEFINITION.nestedClass("Exists");
    private static final ClassName QD_DELETE_WHERE = QUERY_DEFINITION.nestedClass("DeleteWhere");
    private static final ClassName QD_DELETE_BY_ID = QUERY_DEFINITION.nestedClass("DeleteById");
    private static final ClassName QD_UPSERT = QUERY_DEFINITION.nestedClass("Upsert");
    private static final ClassName QD_INSERT = QUERY_DEFINITION.nestedClass("Insert");
    private static final ClassName QD_UPDATE = QUERY_DEFINITION.nestedClass("Update");

    private static final ClassName RESULT_KIND =
            ClassName.get(QUERY_PACKAGE, "ResultKind");
    private static final ClassName PAGING_MODE =
            ClassName.get(QUERY_PACKAGE, "PagingMode");
    private static final ClassName PREDICATE =
            ClassName.get(QUERY_PACKAGE, "Predicate");
    private static final ClassName OPERATOR =
            ClassName.get(QUERY_PACKAGE, "Operator");
    private static final ClassName CONNECTOR =
            ClassName.get(QUERY_PACKAGE, "Connector");
    private static final ClassName SORT_SPEC =
            ClassName.get(QUERY_PACKAGE, "SortSpec");
    private static final ClassName PARAM_BINDING =
            ClassName.get(QUERY_PACKAGE, "ParamBinding");
    private static final ClassName LIKE_PATTERN =
            ClassName.get(QUERY_PACKAGE, "LikePattern");
    private static final ClassName LIMIT_SPEC =
            ClassName.get(QUERY_PACKAGE, "LimitSpec");

    private static final ClassName LIST = ClassName.get(List.class);

    private final Filer filer;

    public QueryModelEmitter(Filer filer) {
        this.filer = filer;
    }

    /**
     * Generates the {@code RepositoryQueryModel} class for the given repository.
     *
     * @param repository the repository type element
     * @param ctx        the repository context
     * @param methods    the resolved method contexts
     */
    public void emit(TypeElement repository, RepositoryContext ctx, List<MethodContext> methods) {
        String repoFqn = repository.getQualifiedName().toString();
        String packageName = packageNameOf(repoFqn);
        String repoSimpleName = repository.getSimpleName().toString();
        String modelSimpleName = repoSimpleName + "QueryModel";

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(modelSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        for (MethodContext method : methods) {
            FieldSpec field = buildQueryDefinitionField(ctx, method);
            if (field != null) {
                classBuilder.addField(field);
            }
        }

        try {
            JavaFile.builder(packageName, classBuilder.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            // Filer will report the error; silently skip
        }
    }

    // ── Field construction ───────────────────────────────────────────────

    private FieldSpec buildQueryDefinitionField(RepositoryContext ctx, MethodContext method) {
        String fieldName = toFieldBaseName(method.name());
        String uniqueName = ctx.uniqueQueryName(fieldName);
        CodeBlock initializer = buildInitializer(ctx, method);
        if (initializer == null) {
            return null;
        }

        ClassName fieldType = resolveFieldType(method);
        if (fieldType == null) {
            return null;
        }

        return FieldSpec.builder(fieldType, uniqueName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .build();
    }

    // ── Field type resolution ────────────────────────────────────────────

    private ClassName resolveFieldType(MethodContext method) {
        Operation op = method.operation();
        return switch (op) {
            case FIND -> QD_READ;
            case COUNT -> QD_COUNT;
            case EXISTS -> QD_EXISTS;
            case DELETE -> {
                if (method.predicates().isEmpty()) {
                    yield QD_DELETE_BY_ID;
                } else {
                    yield QD_DELETE_WHERE;
                }
            }
            case SAVE -> QD_UPSERT;
            case INSERT -> QD_INSERT;
            case UPDATE -> QD_UPDATE;
        };
    }

    // ── Initializer construction ─────────────────────────────────────────

    private CodeBlock buildInitializer(RepositoryContext ctx, MethodContext method) {
        Operation op = method.operation();
        return switch (op) {
            case FIND -> buildReadInitializer(method);
            case COUNT -> buildCountInitializer(method);
            case EXISTS -> buildExistsInitializer(method);
            case DELETE -> {
                if (method.predicates().isEmpty()) {
                    yield buildDeleteByIdInitializer(method);
                } else {
                    yield buildDeleteWhereInitializer(method);
                }
            }
            case SAVE -> buildUpsertInitializer(method);
            case INSERT -> buildInsertInitializer(ctx, method);
            case UPDATE -> buildUpdateInitializer(method);
        };
    }

    // ── QueryDefinition.Read ─────────────────────────────────────────────

    private CodeBlock buildReadInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L, $L, $L, $L, $T.none(), $L, $L, $L)",
                QD_READ,
                resultKindRef(method.resultKind()),
                predicatesList(method.predicates()),
                sortsList(method.sorts()),
                pagingModeRef(method.pagingMode()),
                LIMIT_SPEC,
                seekTermsList(),
                paramBindingsList(method.paramBindings()),
                method.distinct());
    }

    // ── QueryDefinition.Count ────────────────────────────────────────────

    private CodeBlock buildCountInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L, $L, $L)",
                QD_COUNT,
                predicatesList(method.predicates()),
                paramBindingsList(method.paramBindings()),
                method.distinct());
    }

    // ── QueryDefinition.Exists ───────────────────────────────────────────

    private CodeBlock buildExistsInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L, $L)",
                QD_EXISTS,
                predicatesList(method.predicates()),
                paramBindingsList(method.paramBindings()));
    }

    // ── QueryDefinition.DeleteWhere ──────────────────────────────────────

    private CodeBlock buildDeleteWhereInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L, $L, $L)",
                QD_DELETE_WHERE,
                predicatesList(method.predicates()),
                paramBindingsList(method.paramBindings()),
                resultKindRef(method.resultKind()));
    }

    // ── QueryDefinition.DeleteById ───────────────────────────────────────

    private CodeBlock buildDeleteByIdInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L)",
                QD_DELETE_BY_ID,
                resultKindRef(method.resultKind()));
    }

    // ── QueryDefinition.Upsert ───────────────────────────────────────────

    private CodeBlock buildUpsertInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L)",
                QD_UPSERT,
                resultKindRef(method.resultKind()));
    }

    // ── QueryDefinition.Insert ───────────────────────────────────────────

    private CodeBlock buildInsertInitializer(RepositoryContext ctx, MethodContext method) {
        // Determine includeId from the entity's ID strategy
        boolean includeId = isAppAssignedId(ctx);
        return CodeBlock.of("new $T($L, $L)",
                QD_INSERT,
                resultKindRef(method.resultKind()),
                includeId);
    }

    // ── QueryDefinition.Update ───────────────────────────────────────────

    private CodeBlock buildUpdateInitializer(MethodContext method) {
        return CodeBlock.of("new $T($L)",
                QD_UPDATE,
                resultKindRef(method.resultKind()));
    }

    // ── Predicate list construction ──────────────────────────────────────

    private CodeBlock predicatesList(List<Predicate> predicates) {
        if (predicates.isEmpty()) {
            return CodeBlock.of("$T.of()", LIST);
        }
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("$T.of(\n", LIST);
        for (int i = 0; i < predicates.size(); i++) {
            Predicate p = predicates.get(i);
            String trailing = (i < predicates.size() - 1) ? "," : "";
            builder.add("    $L$L\n", predicateCode(p), trailing);
        }
        builder.add(")");
        return builder.build();
    }

    private CodeBlock predicateCode(Predicate p) {
        // Use the simple factory when connector is AND and ignoreCase is false
        if (p.connector() == Connector.AND && !p.ignoreCase()) {
            return CodeBlock.of("$T.of($S, $T.$L, $S)",
                    PREDICATE, p.property(), OPERATOR, p.operator(), p.param());
        }
        // Use the full factory with connector
        if (!p.ignoreCase()) {
            return CodeBlock.of("$T.of($S, $T.$L, $S, $T.$L)",
                    PREDICATE, p.property(), OPERATOR, p.operator(),
                    p.param(), CONNECTOR, p.connector());
        }
        // Full factory with connector and ignoreCase
        return CodeBlock.of("$T.of($S, $T.$L, $S, $T.$L, $L)",
                PREDICATE, p.property(), OPERATOR, p.operator(),
                p.param(), CONNECTOR, p.connector(), p.ignoreCase());
    }

    // ── SortSpec list construction ───────────────────────────────────────

    private CodeBlock sortsList(List<SortSpec> sorts) {
        if (sorts.isEmpty()) {
            return CodeBlock.of("$T.of()", LIST);
        }
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("$T.of(\n", LIST);
        for (int i = 0; i < sorts.size(); i++) {
            SortSpec s = sorts.get(i);
            String trailing = (i < sorts.size() - 1) ? "," : "";
            builder.add("    $T.of($S, $L, $L)$L\n",
                    SORT_SPEC, s.property(), s.ascending(), s.ignoreCase(), trailing);
        }
        builder.add(")");
        return builder.build();
    }

    // ── ParamBinding list construction ───────────────────────────────────

    private CodeBlock paramBindingsList(List<ParamBinding> bindings) {
        if (bindings.isEmpty()) {
            return CodeBlock.of("$T.of()", LIST);
        }
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("$T.of(\n", LIST);
        for (int i = 0; i < bindings.size(); i++) {
            ParamBinding b = bindings.get(i);
            String trailing = (i < bindings.size() - 1) ? "," : "";
            if (b.pattern() == LikePattern.NONE) {
                builder.add("    new $T($L, $S)$L\n",
                        PARAM_BINDING, b.argIndex(), b.paramName(), trailing);
            } else {
                builder.add("    new $T($L, $S, $T.$L)$L\n",
                        PARAM_BINDING, b.argIndex(), b.paramName(),
                        LIKE_PATTERN, b.pattern(), trailing);
            }
        }
        builder.add(")");
        return builder.build();
    }

    // ── SeekTerms list (always empty for static model) ───────────────────

    private CodeBlock seekTermsList() {
        return CodeBlock.of("$T.of()", LIST);
    }

    // ── Enum references ─────────────────────────────────────────────────

    private CodeBlock resultKindRef(ResultKind rk) {
        return CodeBlock.of("$T.$L", RESULT_KIND, rk);
    }

    private CodeBlock pagingModeRef(PagingMode pm) {
        return CodeBlock.of("$T.$L", PAGING_MODE, pm);
    }

    // ── ID strategy ──────────────────────────────────────────────────────

    /**
     * Returns true when the entity uses app-assigned IDs (i.e. the ID column
     * should be included in INSERT statements).
     */
    private boolean isAppAssignedId(RepositoryContext ctx) {
        String idTypeFqn = ctx.entity().id().typeFqn();
        return switch (idTypeFqn) {
            case "java.lang.Long", "long", "java.lang.Integer", "int",
                 "java.lang.Short", "short", "java.util.UUID" -> false;
            default -> true;
        };
    }

    // ── Naming utilities ─────────────────────────────────────────────────

    /**
     * Converts a camelCase method name to an UPPER_SNAKE_CASE field name base.
     * E.g. {@code findByName} &rarr; {@code FIND_BY_NAME}.
     */
    static String toFieldBaseName(String methodName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = methodName.charAt(i - 1);
                if (Character.isLowerCase(prev)) {
                    sb.append('_');
                } else if (i + 1 < methodName.length()
                        && Character.isLowerCase(methodName.charAt(i + 1))) {
                    sb.append('_');
                }
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static String packageNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(0, lastDot) : "";
    }
}
