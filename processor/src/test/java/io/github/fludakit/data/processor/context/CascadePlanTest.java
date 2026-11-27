package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CascadePlanTest {

    @Test
    void emptyPlanHasNoCascadeSpecs() {
        var plan = new CascadePlan(List.of());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void nestedStepsFormTree() {
        var innerEc = new TypeDefinition.Prop.ElementCollection(
                "tags", "java.util.List",
                new TypeDefinition.ValueObject("Tag", List.of(), List.of(), List.of(), List.of(), List.of()),
                "tag_table", "work_id");
        var innerStep = new CascadePlan.CascadeStep(
                null, innerEc, CascadePlan.CascadeMode.REPLACE, List.of());
        var outerEc = new TypeDefinition.Prop.EntityCollection(
                "workHistory", "java.util.List", null, "profile_id", "profile");
        var outerStep = new CascadePlan.CascadeStep(
                null, outerEc, CascadePlan.CascadeMode.DIFF_SYNC, List.of(innerStep));
        var plan = new CascadePlan(List.of(outerStep));

        assertEquals(1, plan.steps().size());
        assertEquals(1, plan.steps().getFirst().children().size());
        assertEquals(CascadePlan.CascadeMode.REPLACE,
                plan.steps().getFirst().children().getFirst().mode());
    }
}