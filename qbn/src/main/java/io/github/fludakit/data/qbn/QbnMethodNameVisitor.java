package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

public class QbnMethodNameVisitor extends QbnBaseVisitor<ParsedMethodName> {

    @Override
    public ParsedMethodName visitMethodName(QbnParser.MethodNameContext ctx) {
        String action = ctx.action().getText();
        int limit = 0;
        if (ctx.first() != null) {
            // FIRST token text is "First" optionally followed by the max-results digits.
            String digits = ctx.first().FIRST().getText().substring("First".length());
            limit = digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }

        List<Predicate> predicates = new ArrayList<>();
        List<SortSpec> sorts = new ArrayList<>();
        if (ctx.conditionList() != null) {
            for (QbnParser.ConditionContext c : ctx.conditionList().condition()) {
                predicates.add(predicate(c));
            }
        }
        if (ctx.orderList() != null) {
            for (QbnParser.OrderItemContext o : ctx.orderList().orderItem()) {
                boolean asc = o.DESC() == null;
                sorts.add(SortSpec.of(propertyName(o.property()), asc, false));
            }
        }
        return new ParsedMethodName(action, predicates, sorts, limit);
    }

    private Predicate predicate(QbnParser.ConditionContext ctx) {
        String prop = propertyName(ctx.property());
        return Predicate.of(prop, operator(ctx), prop);
    }

    private Operator operator(QbnParser.ConditionContext ctx) {
        boolean ignoreCase = ctx.IGNORE_CASE() != null;
        boolean not = ctx.NOT() != null;
        QbnParser.OperatorContext op = ctx.operator();

        Operator base;
        if (op == null) {
            base = Operator.EQ;
        } else if (op.BETWEEN() != null) base = Operator.BETWEEN;
        else if (op.CONTAINS() != null) base = Operator.CONTAINS;
        else if (op.STARTS_WITH() != null) base = Operator.STARTS_WITH;
        else if (op.ENDS_WITH() != null) base = Operator.ENDS_WITH;
        else if (op.LESS_THAN_EQUAL() != null) base = Operator.LESS_THAN_EQUAL;
        else if (op.GREATER_THAN_EQUAL() != null) base = Operator.GREATER_THAN_EQUAL;
        else if (op.LESS_THAN() != null) base = Operator.LESS_THAN;
        else if (op.GREATER_THAN() != null) base = Operator.GREATER_THAN;
        else if (op.LIKE() != null) base = Operator.LIKE;
        else if (op.IN() != null) base = Operator.IN;
        else if (op.IS_NULL() != null) base = Operator.IS_NULL;
        else if (op.IS_TRUE() != null) base = Operator.TRUE;
        else if (op.IS_FALSE() != null) base = Operator.FALSE;
        else base = Operator.EQ;

        if (not) {
            if (base == Operator.LIKE) base = Operator.NOT_LIKE;
            else if (base == Operator.IS_NULL) base = Operator.IS_NOT_NULL;
            else base = Operator.NOT_EQ;
        }
        if (ignoreCase && base == Operator.EQ) base = Operator.IGNORE_CASE_EQ;
        return base;
    }

    private String propertyName(QbnParser.PropertyContext ctx) {
        List<TerminalNode> humps = ctx.HUMP();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < humps.size(); i++) {
            String h = humps.get(i).getText();
            if (i == 0) sb.append(Character.toLowerCase(h.charAt(0))).append(h.substring(1));
            else sb.append(h);
        }
        return sb.toString();
    }
}
