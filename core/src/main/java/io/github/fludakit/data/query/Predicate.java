package io.github.fludakit.data.query;

public record Predicate(String property, Operator operator, String param) {
    public static Predicate of(String property, Operator operator, String param) {
        return new Predicate(property, operator, param);
    }
}
