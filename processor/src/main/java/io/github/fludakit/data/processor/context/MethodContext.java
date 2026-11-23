package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MutationPlan.MutationType;
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

    /** Maps write operations to {@link MutationType}; throws for non-write ops. */
    public MutationType mutationType() {
        return switch (operation) {
            case INSERT -> MutationType.INSERT;
            case SAVE -> MutationType.SAVE;
            case UPDATE -> MutationType.UPDATE;
            case DELETE -> MutationType.DELETE;
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
}
