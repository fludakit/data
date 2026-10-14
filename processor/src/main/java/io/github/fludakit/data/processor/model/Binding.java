package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;

import java.util.List;

/**
 * One resolved predicate and the method arguments that feed it. Most operators take a single
 * argument; {@link Operator#BETWEEN} takes two (the lower and upper bound) and the nullary ones
 * take none, so the count comes from {@code SqlBuilder#parameterCount}. The connector and case
 * flag are carried through from the method-name parse so the rendered SQL matches the declaration.
 */
public record Binding(String property, Operator operator, List<Integer> paramIndices,
               Connector connector, boolean ignoreCase) {
    public Binding(String property, Operator operator, int paramIndex) {
        this(property, operator, List.of(paramIndex), Connector.AND, false);
    }

    public Binding(String property, Operator operator, List<Integer> paramIndices) {
        this(property, operator, paramIndices, Connector.AND, false);
    }
}
