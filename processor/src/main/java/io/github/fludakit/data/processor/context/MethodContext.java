package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.CascadePlan.WriteKind;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.List;

/**
 * Processor binding for one repository method: the lang.model element, lifecycle
 * {@link Operation}, and the pure {@link QueryDefinition} plan.
 *
 * <p>Query shape lives only on {@link #query()}. Thin projections below are for emitter
 * convenience — they do not store a second copy of the plan.</p>
 */
public final class MethodContext {

    /**
     * Repository-method intent for emitter/analyzer routing (processor-only).
     * Distinct from {@link QueryDefinition} variants — e.g. {@link #SAVE} maps to
     * {@link QueryDefinition.Upsert} for SQL.
     */
    public enum Operation {
        FIND, COUNT, EXISTS, DELETE, INSERT, UPDATE, SAVE
    }

    private final ExecutableElement method;
    private final String name;
    private final Operation operation;
    private final QueryDefinition query;

    public MethodContext(ExecutableElement method, String name, Operation operation, QueryDefinition query) {
        this.method = method;
        this.name = name;
        this.operation = operation;
        this.query = query;
    }

    public ExecutableElement method() { return method; }
    public String name() { return name; }
    public Operation operation() { return operation; }
    public QueryDefinition query() { return query; }

    /** Method parameters — delegated from {@link #method()}, not stored separately. */
    public List<? extends VariableElement> parameters() {
        return method.getParameters();
    }

    public ResultKind resultKind() {
        return query.resultKind();
    }

    public List<Predicate> predicates() {
        return query instanceof QueryDefinition.Criteria c ? c.predicates() : List.of();
    }

    public List<ParamBinding> paramBindings() {
        return switch (query) {
            case QueryDefinition.Criteria c -> c.paramBindings();
            case QueryDefinition.Native n -> n.paramBindings();
            default -> List.of();
        };
    }

    public List<SortSpec> sorts() {
        return query instanceof QueryDefinition.Read r ? r.sorts() : List.of();
    }

    public String customSql() {
        return query instanceof QueryDefinition.Native n ? n.sql() : null;
    }

    /**
     * Base name of the generated SQL constant for this method, before
     * {@code RepositoryContext.registerConstant} de-duplicates it.
     * E.g. {@code findByName} &rarr; {@code FIND_BY_NAME_SQL}.
     */
    public String sqlConstantBase() {
        return toSqlConstantName(name);
    }

    /**
     * Base name of a prefixed SQL constant for this method, e.g. {@code sqlConstantBase("COUNT_")} for
     * {@code findByName} &rarr; {@code COUNT_FIND_BY_NAME_SQL}.
     *
     * <p>{@code prefix + name()} is converted as ONE string: underscores are inserted from the
     * neighbouring characters, so this is not always {@code prefix + sqlConstantBase()}.</p>
     */
    public String sqlConstantBase(String prefix) {
        return toSqlConstantName(prefix + name);
    }

    /** Maps write operations to {@link WriteKind}; throws for non-write ops. */
    public WriteKind writeKind() {
        return switch (operation) {
            case INSERT -> WriteKind.INSERT;
            case SAVE -> WriteKind.SAVE;
            case UPDATE -> WriteKind.UPDATE;
            case DELETE -> WriteKind.DELETE;
            default -> throw new IllegalStateException("Not a write operation: " + operation);
        };
    }

    /**
     * Whether the first parameter is the entity itself rather than its identifier.
     *
     * <p>A built-in CRUD method is inherited from {@code BasicRepository<T, K>}, and
     * {@link javax.lang.model.util.Elements#getAllMembers} hands back the member AS DECLARED: its
     * parameter type is the type variable — {@code T} for {@code delete(T)} and
     * {@code deleteById(K)} alike — never the entity the repository substitutes for it. A type
     * variable is therefore resolved by method name; a concrete parameter type is compared to the
     * entity's type name.</p>
     */
    public boolean firstParameterIsEntity(TypeDefinition entity) {
        List<? extends VariableElement> parameters = parameters();
        if (parameters.isEmpty()) {
            return true;
        }
        TypeMirror type = parameters.getFirst().asType();
        if (type.getKind() == TypeKind.TYPEVAR) {
            return !"deleteById".equals(name);
        }
        return type.toString().equals(entity.typeName());
    }

    /** Converts a method name to a SQL constant name base. */
    private static String toSqlConstantName(String methodName) {
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
        sb.append("_SQL");
        return sb.toString();
    }
}
