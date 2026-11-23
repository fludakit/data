package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationPlanTest {

    @Test
    void emptyPlanHasNoCascadeSpecs() {
        var plan = new MutationPlan(
                MutationPlan.MutationType.INSERT,
                List.of());
        assertTrue(plan.cascadeSpecs().isEmpty());
        assertFalse(plan.hasCascade());
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
        assertTrue(plan.hasCascade());
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

        assertEquals(1, plan.cascadeSpecs().size());
        assertEquals(1, plan.cascadeSpecs().getFirst().children().size());
        assertEquals(MutationPlan.CascadeMode.REPLACE,
                plan.cascadeSpecs().getFirst().children().getFirst().mode());
    }
}