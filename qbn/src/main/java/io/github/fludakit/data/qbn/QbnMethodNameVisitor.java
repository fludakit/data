package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.antlr.v4.runtime.tree.TerminalNode;
import java.util.ArrayList;
import java.util.List;

public class QbnMethodNameVisitor extends QbnBaseVisitor<ParsedMethodName> {

    @Override
    public ParsedMethodName visitMethodName(QbnParser.MethodNameContext ctx) {
        // "find" is the only action with a limit and an order; delete/count/exists have neither, so
        // the two live on separate rules rather than on one rule with optional parts.
        QbnParser.FindQueryContext find = ctx.findQuery();
        String actionText = find != null ? find.FIND().getText() : ctx.actionQuery().action().getText();
        Action action = Action.of(actionText);
        if (action == null) {
            throw new IllegalArgumentException("Unknown action: " + actionText);
        }
        boolean distinct = find != null ? find.DISTINCT() != null : ctx.actionQuery().DISTINCT() != null;
        if (distinct && action != Action.FIND && action != Action.COUNT) {
            // De-duplicating rows the statement neither selects nor counts has no meaning, so the
            // name is rejected rather than parsed and quietly stripped of its Distinct.
            throw new IllegalArgumentException(
                    "Distinct is only meaningful for find and count, not for " + action.prefix());
        }
        int limit = 0;
        if (find != null && find.first() != null) {
            // FIRST token text is "First" optionally followed by the max-results digits.
            String digits = find.first().FIRST().getText().substring("First".length());
            limit = digits.isEmpty() ? 1 : Integer.parseInt(digits);
        }

        List<Predicate> predicates = new ArrayList<>();
        List<SortSpec> sorts = new ArrayList<>();
        QbnParser.RestrictionContext restriction =
                find != null ? find.restriction() : ctx.actionQuery().restriction();
        if (restriction != null) {
            QbnParser.ConditionListContext list = restriction.conditionList();
            List<QbnParser.ConnectorContext> connectors = list.connector();
            List<QbnParser.ConditionContext> conditions = list.condition();
            for (int i = 0; i < conditions.size(); i++) {
                // Condition i is preceded by connector i-1; the first predicate's connector is ignored.
                Connector connector = i == 0 ? Connector.AND
                        : connectors.get(i - 1).OR() != null ? Connector.OR : Connector.AND;
                predicates.add(predicate(conditions.get(i), connector));
            }
        }
        if (find != null && find.order() != null) {
            for (QbnParser.OrderItemContext o : find.order().orderList().orderItem()) {
                boolean asc = o.DESC() == null;
                sorts.add(SortSpec.of(propertyName(o.property()), asc, o.IGNORE_CASE() != null));
            }
        }
        return new ParsedMethodName(action, distinct, predicates, sorts, limit);
    }

    private Predicate predicate(QbnParser.ConditionContext ctx, Connector connector) {
        String prop = propertyName(ctx.property());
        return Predicate.of(prop, operator(ctx), prop, connector, ctx.IGNORE_CASE() != null);
    }

    private Operator operator(QbnParser.ConditionContext ctx) {
        // An absent operator token is equality — findByName means name = ?. That is a DIFFERENT case
        // from an operator token this visitor does not map, which is rejected in operatorOf.
        Operator base = ctx.operator() == null ? Operator.EQ : operatorOf(ctx.operator());
        if (ctx.NOT() != null) {
            base = negate(base);
        }
        // Case folding is an orthogonal modifier: it rides on the predicate, not on the operator, so
        // it composes with every operator rather than only with equality.
        return base;
    }

    /**
     * The operator a condition's {@code operator} token stands for.
     *
     * <p>A SWITCH over the token type rather than a chain of {@code op.X() != null} tests, because
     * the grammar's {@code operator} rule is a single token drawn from twelve: the mapping is a
     * table, and a table is what it should read as. The chain could not say so — its last {@code
     * else} fell back to {@code Operator.EQ}, so an operator the grammar grew into without this
     * method noticing rendered as {@code =} instead of failing, which is the one thing {@link
     * #negate} refuses to do on principle. The {@code default} arm is that same refusal, now the
     * switch's terminus rather than a tail that reads like a default.</p>
     */
    private static Operator operatorOf(QbnParser.OperatorContext op) {
        return switch (op.getStart().getType()) {
            case QbnLexer.BETWEEN -> Operator.BETWEEN;
            case QbnLexer.CONTAINS -> Operator.CONTAINS;
            case QbnLexer.STARTS_WITH -> Operator.STARTS_WITH;
            case QbnLexer.ENDS_WITH -> Operator.ENDS_WITH;
            case QbnLexer.LESS_THAN_EQUAL -> Operator.LESS_THAN_EQUAL;
            case QbnLexer.GREATER_THAN_EQUAL -> Operator.GREATER_THAN_EQUAL;
            case QbnLexer.LESS_THAN -> Operator.LESS_THAN;
            case QbnLexer.GREATER_THAN -> Operator.GREATER_THAN;
            case QbnLexer.LIKE -> Operator.LIKE;
            case QbnLexer.IN -> Operator.IN;
            case QbnLexer.IS_NULL -> Operator.IS_NULL;
            case QbnLexer.IS_TRUE -> Operator.TRUE;
            case QbnLexer.IS_FALSE -> Operator.FALSE;
            default -> throw new IllegalArgumentException("Unmapped operator '" + op.getText() + "'");
        };
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
     *
     * <p>Multi-segment properties separated by underscores ({@code Contacts_Value}) get
     * dot-separated to form nested paths ({@code contacts.value}), enabling EXISTS subquery
     * generation for ElementCollection predicates. CamelCase concatenation ({@code ContactsValue})
     * stays as-is ({@code contactsValue}) for backward compatibility.</p>
     */
    private String propertyName(QbnParser.PropertyContext ctx) {
        List<TerminalNode> humps = ctx.HUMP();
        List<TerminalNode> underscores = ctx.UNDERSCORE();
        
        // If there are underscores between humps, convert to dot-separated path
        if (!underscores.isEmpty() && humps.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < humps.size(); i++) {
                if (i > 0) sb.append('.');
                String text = humps.get(i).getText();
                sb.append(Character.toLowerCase(text.charAt(0))).append(text.substring(1));
            }
            return sb.toString();
        } else {
            // No underscores - treat as single camelCase property name
            String text = ctx.getText();
            return Character.toLowerCase(text.charAt(0)) + text.substring(1);
        }
    }
}
