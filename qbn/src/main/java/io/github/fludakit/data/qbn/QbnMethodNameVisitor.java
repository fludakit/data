package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Action;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import java.util.ArrayList;
import java.util.List;

public class QbnMethodNameVisitor extends QbnBaseVisitor<ParsedMethodName> {

    @Override
    public ParsedMethodName visitMethodName(QbnParser.MethodNameContext ctx) {
        Action action = Action.of(ctx.action().getText());
        if (action == null) {
            throw new IllegalArgumentException("Unknown action: " + ctx.action().getText());
        }
        int limit = 0;
        if (ctx.first() != null) {
            // FIRST token text is "First" optionally followed by the max-results digits.
            String digits = ctx.first().FIRST().getText().substring("First".length());
            limit = digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }

        List<Predicate> predicates = new ArrayList<>();
        List<SortSpec> sorts = new ArrayList<>();
        if (ctx.conditionList() != null) {
            List<QbnParser.ConnectorContext> connectors = ctx.conditionList().connector();
            List<QbnParser.ConditionContext> conditions = ctx.conditionList().condition();
            for (int i = 0; i < conditions.size(); i++) {
                // Condition i is preceded by connector i-1; the first predicate's connector is ignored.
                Connector connector = i == 0 ? Connector.AND
                        : connectors.get(i - 1).OR() != null ? Connector.OR : Connector.AND;
                predicates.add(predicate(conditions.get(i), connector));
            }
        }
        if (ctx.orderList() != null) {
            for (QbnParser.OrderItemContext o : ctx.orderList().orderItem()) {
                boolean asc = o.DESC() == null;
                sorts.add(SortSpec.of(propertyName(o.property()), asc, o.IGNORE_CASE() != null));
            }
        }
        return new ParsedMethodName(action, ctx.DISTINCT() != null, predicates, sorts, limit);
    }

    private Predicate predicate(QbnParser.ConditionContext ctx, Connector connector) {
        String prop = propertyName(ctx.property());
        return Predicate.of(prop, operator(ctx), prop, connector, ctx.IGNORE_CASE() != null);
    }

    private Operator operator(QbnParser.ConditionContext ctx) {
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
            base = negate(base);
        }
        // Case folding is an orthogonal modifier: it rides on the predicate, not on the operator, so
        // it composes with every operator rather than only with equality.
        return base;
    }

    /**
     * The spec's negated form of a predicate operator. Only the operators the spec composes with
     * {@code Not} are mapped — anything else is rejected rather than silently degraded to {@code <>},
     * which is what {@code findByNameNotIn} used to render.
     */
    private static Operator negate(Operator base) {
        return switch (base) {
            case EQ -> Operator.NOT_EQ;
            case LIKE -> Operator.NOT_LIKE;
            case CONTAINS -> Operator.NOT_CONTAINS;
            case STARTS_WITH -> Operator.NOT_STARTS_WITH;
            case ENDS_WITH -> Operator.NOT_ENDS_WITH;
            case IN -> Operator.NOT_IN;
            case BETWEEN -> Operator.NOT_BETWEEN;
            case IS_NULL -> Operator.IS_NOT_NULL;
            default -> throw new IllegalArgumentException("Not cannot be combined with " + base);
        };
    }

    /**
     * The condition/order property as written, minus the leading capital: the whole rule text is
     * taken verbatim so underscore separators survive ({@code address_street} stays
     * {@code address_street}, not {@code addressstreet}).
     */
    private String propertyName(QbnParser.PropertyContext ctx) {
        String text = ctx.getText();
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
