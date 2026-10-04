package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Operator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryByMethodNameParserTest {

    QueryByMethodNameParser parser = new QueryByMethodNameParser();

    @Test
    void parsesSimpleEquality() {
        var p = parser.parse("findByName");
        assertThat(p.action()).isEqualTo("find");
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("name");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.EQ);
    }

    @Test
    void parsesAndWithOperatorAndOrder() {
        var p = parser.parse("findByCategoryNameAndPriceLessThanOrderByPriceDesc");
        assertThat(p.predicates()).hasSize(2);
        assertThat(p.predicates().get(0).property()).isEqualTo("categoryName");
        assertThat(p.predicates().get(1).property()).isEqualTo("price");
        assertThat(p.predicates().get(1).operator()).isEqualTo(Operator.LESS_THAN);
        assertThat(p.sorts()).hasSize(1);
        assertThat(p.sorts().get(0).property()).isEqualTo("price");
        assertThat(p.sorts().get(0).ascending()).isFalse();
    }

    @Test
    void parsesIgnoreCaseAndFirst() {
        var p = parser.parse("findFirst10ByNameIgnoreCase");
        assertThat(p.limit()).isEqualTo(10);
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.IGNORE_CASE_EQ);
    }
}
