package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.List;

public final class MethodContext {
    private final ExecutableElement method;
    private final String name;
    private final Operation operation;
    private final ResultKind resultKind;
    private final boolean distinct;
    private final List<? extends VariableElement> parameters;
    private final List<ParamBinding> paramBindings;
    private final List<Predicate> predicates;
    private final List<SortSpec> sorts;
    private final PagingMode pagingMode;
    private final String customSql;

    public MethodContext(ExecutableElement method, String name, Operation operation,
                         ResultKind resultKind, boolean distinct,
                         List<? extends VariableElement> parameters,
                         List<ParamBinding> paramBindings,
                         List<Predicate> predicates, List<SortSpec> sorts,
                         PagingMode pagingMode, String customSql) {
        this.method = method;
        this.name = name;
        this.operation = operation;
        this.resultKind = resultKind;
        this.distinct = distinct;
        this.parameters = parameters;
        this.paramBindings = paramBindings;
        this.predicates = predicates;
        this.sorts = sorts;
        this.pagingMode = pagingMode;
        this.customSql = customSql;
    }

    public ExecutableElement method() { return method; }
    public String name() { return name; }
    public Operation operation() { return operation; }
    public ResultKind resultKind() { return resultKind; }
    public boolean distinct() { return distinct; }
    public List<? extends VariableElement> parameters() { return parameters; }
    public List<ParamBinding> paramBindings() { return paramBindings; }
    public List<Predicate> predicates() { return predicates; }
    public List<SortSpec> sorts() { return sorts; }
    public PagingMode pagingMode() { return pagingMode; }
    public String customSql() { return customSql; }

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
