package io.github.fludakit.data.query;

import java.util.List;

public record QueryModel(
        Class<?> entityClass,
        Operation operation,
        List<Predicate> predicates,
        List<SortSpec> sorts,
        String sql,                    // explicit native SQL from @Query, or null
        List<ParamRef> paramRefs,      // @Query/@Param: maps arg index -> named placeholder
        ResultKind resultKind,
        int pageParamIndex,            // -1 if none
        int sortParamIndex,            // -1 if none (jakarta.data.Sort param)
        int orderParamIndex,           // -1 if none (jakarta.data.Order param)
        int limitParamIndex,           // -1 if none (jakarta.data.Limit param)
        int staticLimit)               // @Limit / QBN First (0 = none)
{
    public record ParamRef(int argIndex, String name) {}

    public static QueryModelBuilder builder(Class<?> entityClass, Operation operation) {
        return new QueryModelBuilder(entityClass, operation);
    }
}
