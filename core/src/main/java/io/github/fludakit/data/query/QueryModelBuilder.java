package io.github.fludakit.data.query;

import java.util.ArrayList;
import java.util.List;

public final class QueryModelBuilder {
    private final Class<?> entityClass;
    private final Operation operation;
    private final List<Predicate> predicates = new ArrayList<>();
    private final List<SortSpec> sorts = new ArrayList<>();
    private final List<QueryModel.ParamRef> paramRefs = new ArrayList<>();
    private String sql;
    private ResultKind resultKind = ResultKind.LIST;
    private int pageParamIndex = -1, sortParamIndex = -1, orderParamIndex = -1, limitParamIndex = -1;
    private int staticLimit;

    public QueryModelBuilder(Class<?> entityClass, Operation operation) {
        this.entityClass = entityClass;
        this.operation = operation;
    }

    public QueryModelBuilder predicate(Predicate p) { predicates.add(p); return this; }
    public QueryModelBuilder sort(SortSpec s) { sorts.add(s); return this; }
    public QueryModelBuilder sql(String s) { this.sql = s; return this; }
    public QueryModelBuilder paramRef(int index, String name) { paramRefs.add(new QueryModel.ParamRef(index, name)); return this; }
    public QueryModelBuilder resultKind(ResultKind rk) { this.resultKind = rk; return this; }
    public QueryModelBuilder pageParamIndex(int i) { this.pageParamIndex = i; return this; }
    public QueryModelBuilder sortParamIndex(int i) { this.sortParamIndex = i; return this; }
    public QueryModelBuilder orderParamIndex(int i) { this.orderParamIndex = i; return this; }
    public QueryModelBuilder limitParamIndex(int i) { this.limitParamIndex = i; return this; }
    public QueryModelBuilder staticLimit(int l) { this.staticLimit = l; return this; }

    public QueryModel build() {
        return new QueryModel(entityClass, operation, List.copyOf(predicates), List.copyOf(sorts),
                sql, List.copyOf(paramRefs), resultKind, pageParamIndex, sortParamIndex,
                orderParamIndex, limitParamIndex, staticLimit);
    }
}
