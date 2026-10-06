package io.github.fludakit.data.metadata;

/** One LEFT JOIN edge a navigated property path contributes: join
 *  {@code <parentQualifier>.<parentColumn> = <alias>.<childColumn>}, where parentQualifier is the
 *  root table for the first join and the previous join's alias afterwards. */
public record JoinRef(String table, String alias, String parentColumn, String childColumn) { }
