package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;

public record ParsedMethodName(String action, List<Predicate> predicates, List<SortSpec> sorts, int limit) {}
