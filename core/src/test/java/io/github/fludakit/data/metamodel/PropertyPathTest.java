package io.github.fludakit.data.metamodel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PropertyPath}.
 */
class PropertyPathTest {

    @Test
    void rootPath_hasSingleSegment() {
        PropertyPath path = PropertyPath.of("id");
        assertThat(path.segment()).isEqualTo("id");
        assertThat(path.parent()).isNull();
        assertThat(path.depth()).isEqualTo(1);
        assertThat(path.isNested()).isFalse();
    }

    @Test
    void rootPath_toDotPath_returnsSegment() {
        PropertyPath path = PropertyPath.of("name");
        assertThat(path.toDotPath()).isEqualTo("name");
    }

    @Test
    void rootPath_toParamName_returnsSegment() {
        PropertyPath path = PropertyPath.of("email");
        assertThat(path.toParamName()).isEqualTo("email");
    }

    @Test
    void rootPath_toColumnPrefix_returnsSegmentWithUnderscore() {
        PropertyPath path = PropertyPath.of("address");
        assertThat(path.toColumnPrefix()).isEqualTo("address_");
    }

    @Test
    void childPath_hasTwoSegments() {
        PropertyPath path = PropertyPath.of("customer").child("name");
        assertThat(path.segment()).isEqualTo("name");
        assertThat(path.parent()).isNotNull();
        assertThat(path.parent().segment()).isEqualTo("customer");
        assertThat(path.parent().parent()).isNull();
        assertThat(path.depth()).isEqualTo(2);
        assertThat(path.isNested()).isTrue();
    }

    @Test
    void childPath_toDotPath_joinsWithDot() {
        PropertyPath path = PropertyPath.of("customer").child("name");
        assertThat(path.toDotPath()).isEqualTo("customer.name");
    }

    @Test
    void childPath_toParamName_joinsWithUnderscore() {
        PropertyPath path = PropertyPath.of("customer").child("name");
        assertThat(path.toParamName()).isEqualTo("customer_name");
    }

    @Test
    void deeplyNestedPath_computesCorrectly() {
        PropertyPath path = PropertyPath.of("order")
                .child("customer")
                .child("address")
                .child("city");
        assertThat(path.toDotPath()).isEqualTo("order.customer.address.city");
        assertThat(path.toParamName()).isEqualTo("order_customer_address_city");
        assertThat(path.depth()).isEqualTo(4);
        assertThat(path.isNested()).isTrue();
    }

    @Test
    void equality_sameStructure_areEqual() {
        PropertyPath path1 = PropertyPath.of("customer").child("id");
        PropertyPath path2 = PropertyPath.of("customer").child("id");
        assertThat(path1).isEqualTo(path2);
        assertThat(path1.hashCode()).isEqualTo(path2.hashCode());
    }

    @Test
    void equality_differentStructure_areNotEqual() {
        PropertyPath path1 = PropertyPath.of("customer").child("id");
        PropertyPath path2 = PropertyPath.of("customer").child("name");
        assertThat(path1).isNotEqualTo(path2);
    }

    @Test
    void equality_differentDepth_areNotEqual() {
        PropertyPath path1 = PropertyPath.of("customer");
        PropertyPath path2 = PropertyPath.of("customer").child("id");
        assertThat(path1).isNotEqualTo(path2);
    }

    @Test
    void toString_returnsDotPath() {
        PropertyPath path = PropertyPath.of("customer").child("name");
        assertThat(path.toString()).isEqualTo("customer.name");
    }

    @Test
    void sharedStructure_parentIsReused() {
        PropertyPath customer = PropertyPath.of("customer");
        PropertyPath customerId = customer.child("id");
        PropertyPath customerName = customer.child("name");
        
        assertThat(customerId.parent()).isSameAs(customerName.parent());
        assertThat(customerId.parent()).isSameAs(customer);
    }
}
