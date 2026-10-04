package io.github.fludakit.data.query;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QueryModelTest {
    @Test
    void buildsFindModel() {
        QueryModel m = QueryModel.builder(String.class, Operation.FIND)
                .predicate(Predicate.of("name", Operator.EQ, "name"))
                .sort(SortSpec.of("name", true, false))
                .resultKind(ResultKind.LIST)
                .build();
        assertThat(m.operation()).isEqualTo(Operation.FIND);
        assertThat(m.predicates()).hasSize(1);
        assertThat(m.sorts()).hasSize(1);
        assertThat(m.resultKind()).isEqualTo(ResultKind.LIST);
    }
}
