package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MutationPlanTest {

    @Test
    void emptyPlanHasNoCascadeSpecs() {
        var plan = new MutationPlan(
                MutationPlan.MutationType.INSERT,
                List.of());
        assertThat(plan.cascadeSpecs()).isEmpty();
        assertThat(plan.hasCascade()).isFalse();
    }

    @Test
    void planWithStepsReportsHasCascade() {
        var ec = new TypeDefinition.Prop.ElementCollection(
                "tags", "java.util.List",
                new TypeDefinition.ValueObject("Tag", List.of(), List.of(), List.of(), List.of(), List.of()),
                "tag_table", "parent_id");
        var step = new MutationPlan.CascadeSpec(
                null, ec, MutationPlan.CascadeMode.REPLACE, List.of());
        var plan = new MutationPlan(MutationPlan.MutationType.INSERT, List.of(step));
        assertThat(plan.hasCascade()).isTrue();
    }

    @Test
    void nestedStepsFormTree() {
        var innerEc = new TypeDefinition.Prop.ElementCollection(
                "tags", "java.util.List",
                new TypeDefinition.ValueObject("Tag", List.of(), List.of(), List.of(), List.of(), List.of()),
                "tag_table", "work_id");
        var innerStep = new MutationPlan.CascadeSpec(
                null, innerEc, MutationPlan.CascadeMode.REPLACE, List.of());
        var outerEc = new TypeDefinition.Prop.EntityCollection(
                "workHistory", "java.util.List", null, "profile_id", "profile");
        var outerStep = new MutationPlan.CascadeSpec(
                null, outerEc, MutationPlan.CascadeMode.DIFF_SYNC, List.of(innerStep));
        var plan = new MutationPlan(MutationPlan.MutationType.INSERT, List.of(outerStep));

        assertThat(plan.cascadeSpecs()).hasSize(1);
        assertThat(plan.cascadeSpecs().getFirst().children()).hasSize(1);
        assertThat(plan.cascadeSpecs().getFirst().children().getFirst().mode())
                .isEqualTo(MutationPlan.CascadeMode.REPLACE);
    }
}
