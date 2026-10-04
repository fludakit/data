package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QueryByMethodNameParser {

    private static final String[] ACTIONS = {"find", "delete", "count", "exists"};

    public ParsedMethodName parse(String name) {
        String action = null;
        for (String a : ACTIONS) {
            if (name.startsWith(a)) { action = a; break; }
        }
        if (action == null) throw new IllegalArgumentException("Not a query method name: " + name);

        String rest = name.substring(action.length());
        int limit = 0;
        Matcher first = Pattern.compile("^First(\\d+)?").matcher(rest);
        if (first.find()) {
            String g = first.group(1);
            limit = g == null ? 1 : Integer.parseInt(g);
            rest = rest.substring(first.end());
        }

        // strip any non-By/OrderBy lead text (ignored)
        int orderIdx = rest.indexOf("OrderBy");
        String orderPart = "";
        if (orderIdx >= 0) {
            orderPart = rest.substring(orderIdx + "OrderBy".length());
            rest = rest.substring(0, orderIdx);
        }
        int byIdx = rest.indexOf("By");
        String conditions = byIdx >= 0 ? rest.substring(byIdx + "By".length()) : "";

        List<Predicate> predicates = new ArrayList<>();
        if (!conditions.isBlank()) {
            for (String part : splitByLogical(conditions)) {
                predicates.add(parseCondition(part));
            }
        }
        List<SortSpec> sorts = parseOrder(orderPart);
        return new ParsedMethodName(action, predicates, sorts, limit);
    }

    private List<String> splitByLogical(String conditions) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(And|Or)").matcher(conditions);
        int start = 0;
        while (m.find()) { out.add(conditions.substring(start, m.start())); start = m.end(); }
        out.add(conditions.substring(start));
        return out;
    }

    private Predicate parseCondition(String cond) {
        String property = "";
        Operator op = Operator.EQ;
        boolean ignoreCase = false;

        if (cond.endsWith("IgnoreCase")) { ignoreCase = true; cond = cond.substring(0, cond.length() - "IgnoreCase".length()); }

        Operator[] ops = {Operator.LESS_THAN_EQUAL, Operator.GREATER_THAN_EQUAL, Operator.LESS_THAN,
                Operator.GREATER_THAN, Operator.BETWEEN, Operator.CONTAINS, Operator.STARTS_WITH,
                Operator.ENDS_WITH, Operator.LIKE, Operator.IN, Operator.NOT_LIKE, Operator.NOT_EQ};
        String[] tokens = {"LessThanEqual", "GreaterThanEqual", "LessThan", "GreaterThan", "Between",
                "Contains", "StartsWith", "EndsWith", "Like", "In", "NotLike", "Not"};
        for (int i = 0; i < tokens.length; i++) {
            if (cond.endsWith(tokens[i])) {
                property = cond.substring(0, cond.length() - tokens[i].length());
                op = ops[i];
                break;
            }
        }
        if (property.isEmpty() && cond.endsWith("Null")) { property = cond.substring(0, cond.length() - 4); op = Operator.IS_NULL; }
        else if (property.isEmpty() && cond.endsWith("True")) { property = cond.substring(0, cond.length() - 4); op = Operator.TRUE; }
        else if (property.isEmpty() && cond.endsWith("False")) { property = cond.substring(0, cond.length() - 5); op = Operator.FALSE; }
        else if (property.isEmpty()) { property = cond; }

        if (ignoreCase) op = (op == Operator.EQ) ? Operator.IGNORE_CASE_EQ : op;
        String beanProperty = decapitalize(property);
        return Predicate.of(beanProperty, op, beanProperty);
    }

    /** Method-name fragments are capitalised (they follow By/And/Or); map them to the bean property name. */
    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private List<SortSpec> parseOrder(String orderPart) {
        List<SortSpec> out = new ArrayList<>();
        if (orderPart == null || orderPart.isBlank()) return out;
        for (String item : orderPart.split("(?<=Desc|Asc)")) {
            if (item.isBlank()) continue;
            boolean asc = item.endsWith("Asc");
            int suffix = asc ? "Asc".length() : "Desc".length();
            String prop = decapitalize(item.substring(0, item.length() - suffix));
            out.add(SortSpec.of(prop, asc, false));
        }
        return out;
    }
}
