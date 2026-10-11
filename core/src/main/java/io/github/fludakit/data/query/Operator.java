package io.github.fludakit.data.query;

public enum Operator {
    EQ("="), NOT_EQ("<>"), LESS_THAN("<"), LESS_THAN_EQUAL("<="),
    GREATER_THAN(">"), GREATER_THAN_EQUAL(">="), LIKE("LIKE"),
    NOT_LIKE("NOT LIKE"), BETWEEN("BETWEEN"), NOT_BETWEEN("NOT BETWEEN"),
    IN("IN"), NOT_IN("NOT IN"),
    IS_NULL("IS NULL"), IS_NOT_NULL("IS NOT NULL"), TRUE("= TRUE"), FALSE("= FALSE"),
    CONTAINS("LIKE"), STARTS_WITH("LIKE"), ENDS_WITH("LIKE"),
    NOT_CONTAINS("NOT LIKE"), NOT_STARTS_WITH("NOT LIKE"), NOT_ENDS_WITH("NOT LIKE"),
    IGNORE_CASE_EQ("=");

    private final String sql;
    Operator(String sql) { this.sql = sql; }
    public String sql() { return sql; }
}
