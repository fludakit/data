package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.MutationPlan;
import io.github.fludakit.data.metadata.MutationPlan.*;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MutationPlanAnalyzerTest {

    @Test
    void aggregateInsertHasPreInsertCompositionAndPostInsertCollections() {
        // Build a minimal Aggregate TypeDefinition with:
        // - one composing Association (to-one child)
        // - one EntityCollection
        // - one ElementCollection
        TypeDefinition.Aggregate aggregate = buildTestAggregate();

        MutationPlan plan = MutationPlanAnalyzer.analyze(aggregate, Operation.INSERT);

        assertEquals(MutationType.INSERT, plan.mutationType());
        assertTrue(plan.hasCascade());

        // Pre-root: composition to-one (PRE_INSERT)
        // Post-root: entity collection (DIFF_SYNC), element collection (REPLACE)
        var specs = plan.cascadeSpecs();
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association
                && s.mode() == CascadeMode.PRE_INSERT),
                "Should have a composition association with PRE_INSERT");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection
                && s.mode() == CascadeMode.DIFF_SYNC),
                "Should have an entity collection with DIFF_SYNC");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.ElementCollection
                && s.mode() == CascadeMode.REPLACE),
                "Should have an element collection with REPLACE");
    }

    @Test
    void entityInsertOnlyHasElementCollections() {
        TypeDefinition.Entity entity = buildTestEntity();

        MutationPlan plan = MutationPlanAnalyzer.analyze(entity, Operation.INSERT);

        assertEquals(MutationType.INSERT, plan.mutationType());
        var specs = plan.cascadeSpecs();
        assertTrue(specs.stream().noneMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association),
                "Entity plan should have no associations");
        assertTrue(specs.stream().noneMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection),
                "Entity plan should have no entity collections");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.ElementCollection
                && s.mode() == CascadeMode.REPLACE),
                "Should have an element collection with REPLACE");
    }

    @Test
    void aggregateDeleteCascadesCompositionsAndCollectionsBeforeRoot() {
        TypeDefinition.Aggregate aggregate = buildTestAggregate();

        MutationPlan plan = MutationPlanAnalyzer.analyze(aggregate, Operation.DELETE);

        assertEquals(MutationType.DELETE, plan.mutationType());
        var specs = plan.cascadeSpecs();
        // All delete specs should be CASCADE_DELETE
        assertTrue(specs.stream().allMatch(s -> s.mode() == CascadeMode.CASCADE_DELETE),
                "All delete specs should be CASCADE_DELETE");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association),
                "Should have a composition association delete");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection),
                "Should have an entity collection delete");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.ElementCollection),
                "Should have an element collection delete");
    }

    @Test
    void entityCollectionOfAggregateTargetIsSkipped() {
        // Build aggregate with entity collection whose target is an Aggregate
        TypeDefinition.Aggregate aggregate = buildAggregateWithAggregateTarget();

        MutationPlan plan = MutationPlanAnalyzer.analyze(aggregate, Operation.INSERT);

        // The entity collection targeting an aggregate should NOT appear in the plan
        assertTrue(plan.cascadeSpecs().stream()
                .noneMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection
                        && s.target() instanceof TypeDefinition.Aggregate),
                "Entity collection targeting an Aggregate should be skipped (Rule 4)");
    }

    @Test
    void aggregateUpdateHasPostUpdateCompositionAndCollections() {
        TypeDefinition.Aggregate aggregate = buildTestAggregate();

        MutationPlan plan = MutationPlanAnalyzer.analyze(aggregate, Operation.UPDATE);

        assertEquals(MutationType.UPDATE, plan.mutationType());
        assertTrue(plan.hasCascade());
        var specs = plan.cascadeSpecs();
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association
                && s.mode() == CascadeMode.UPDATE),
                "Should have a composition association with UPDATE");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection
                && s.mode() == CascadeMode.DIFF_SYNC),
                "Should have an entity collection with DIFF_SYNC on update");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.ElementCollection
                && s.mode() == CascadeMode.REPLACE),
                "Should have an element collection with REPLACE on update");
    }

    @Test
    void entityDeleteOnlyHasElementCollectionCascadeDelete() {
        TypeDefinition.Entity entity = buildTestEntity();

        MutationPlan plan = MutationPlanAnalyzer.analyze(entity, Operation.DELETE);

        assertEquals(MutationType.DELETE, plan.mutationType());
        var specs = plan.cascadeSpecs();
        assertTrue(specs.stream().allMatch(s -> s.mode() == CascadeMode.CASCADE_DELETE),
                "All entity delete specs should be CASCADE_DELETE");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.ElementCollection),
                "Should have an element collection delete");
        assertTrue(specs.stream().noneMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association),
                "Entity delete should have no associations");
        assertTrue(specs.stream().noneMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection),
                "Entity delete should have no entity collections");
    }

    @Test
    void saveOperationBehavesLikeInsert() {
        TypeDefinition.Aggregate aggregate = buildTestAggregate();

        MutationPlan plan = MutationPlanAnalyzer.analyze(aggregate, Operation.SAVE);

        assertEquals(MutationType.SAVE, plan.mutationType());
        var specs = plan.cascadeSpecs();
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.Association
                && s.mode() == CascadeMode.PRE_INSERT),
                "SAVE should have PRE_INSERT for compositions (like INSERT)");
        assertTrue(specs.stream().anyMatch(s -> s.prop() instanceof TypeDefinition.Prop.EntityCollection
                && s.mode() == CascadeMode.DIFF_SYNC),
                "SAVE should have DIFF_SYNC for entity collections (like INSERT)");
    }

    @Test
    void compositionCascadeHasNestedChildren() {
        // Build an aggregate whose composition child has its own element collection
        TypeDefinition.Prop.Id childId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.ValueObject childVo = new TypeDefinition.ValueObject(
                "ChildElement", List.of(new TypeDefinition.Prop.Scalar("value", "java.lang.String", "value", false)),
                List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.ElementCollection childElementCollection = new TypeDefinition.Prop.ElementCollection(
                "tags", "java.util.List", childVo, "child_element_table", "child_id");
        TypeDefinition.Entity childEntity = new TypeDefinition.Entity(
                "ChildEntity", "child_entity", childId, null,
                List.of(), List.of(), List.of(), List.of(), List.of(childElementCollection));
        TypeDefinition.Prop.Association composition = new TypeDefinition.Prop.Association(
                "child", "ChildEntity", childEntity, "child_id", false, true,
                TypeDefinition.Prop.Association.Cascade.ALL);

        TypeDefinition.Prop.Id rootId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Aggregate root = new TypeDefinition.Aggregate(
                "Root", "root", rootId, null,
                List.of(), List.of(), List.of(composition), List.of(), List.of());

        MutationPlan plan = MutationPlanAnalyzer.analyze(root, Operation.INSERT);

        var specs = plan.cascadeSpecs();
        assertEquals(1, specs.size());
        CascadeSpec compositionSpec = specs.get(0);
        assertEquals(CascadeMode.PRE_INSERT, compositionSpec.mode());
        // The composition's children should include the nested element collection
        assertFalse(compositionSpec.children().isEmpty(), "Composition should have nested cascade children");
        assertTrue(compositionSpec.children().stream().anyMatch(s ->
                s.prop() instanceof TypeDefinition.Prop.ElementCollection
                && s.mode() == CascadeMode.REPLACE),
                "Nested cascade should include element collection REPLACE");
    }

    // Helper methods to build test TypeDefinitions
    private static TypeDefinition.Aggregate buildTestAggregate() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar name = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);

        // Composing to-one child entity
        TypeDefinition.Prop.Id childId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar childName = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);
        TypeDefinition.Entity childEntity = new TypeDefinition.Entity(
                "ChildEntity", "child_entity", childId, null,
                List.of(childName), List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.Association composition = new TypeDefinition.Prop.Association(
                "child", "ChildEntity", childEntity, "child_id", false, true,
                TypeDefinition.Prop.Association.Cascade.ALL);

        // Entity collection child
        TypeDefinition.Prop.Id ecChildId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar ecChildName = new TypeDefinition.Prop.Scalar("company", "java.lang.String", "company", false);
        TypeDefinition.Entity ecChildEntity = new TypeDefinition.Entity(
                "CollectionChild", "collection_child", ecChildId, null,
                List.of(ecChildName), List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.EntityCollection entityCollection = new TypeDefinition.Prop.EntityCollection(
                "items", "java.util.List", ecChildEntity, "parent_id", "parent");

        // Element collection (value object)
        TypeDefinition.ValueObject vo = new TypeDefinition.ValueObject(
                "Element", List.of(new TypeDefinition.Prop.Scalar("value", "java.lang.String", "value", false)),
                List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.ElementCollection elementCollection = new TypeDefinition.Prop.ElementCollection(
                "elements", "java.util.List", vo, "element_table", "parent_id");

        return new TypeDefinition.Aggregate(
                "TestAggregate", "test_aggregate", id, null,
                List.of(name), List.of(), List.of(composition), List.of(entityCollection),
                List.of(elementCollection));
    }

    private static TypeDefinition.Entity buildTestEntity() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar name = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);

        // Element collection only (entity rules: no composition, no entity collection cascade)
        TypeDefinition.ValueObject vo = new TypeDefinition.ValueObject(
                "Element", List.of(new TypeDefinition.Prop.Scalar("value", "java.lang.String", "value", false)),
                List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.ElementCollection elementCollection = new TypeDefinition.Prop.ElementCollection(
                "elements", "java.util.List", vo, "element_table", "parent_id");

        return new TypeDefinition.Entity(
                "TestEntity", "test_entity", id, null,
                List.of(name), List.of(), List.of(), List.of(), List.of(elementCollection));
    }

    private static TypeDefinition.Aggregate buildAggregateWithAggregateTarget() {
        TypeDefinition.Prop.Id id = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar name = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);

        // Entity collection whose target is an Aggregate (should be skipped by Rule 4)
        TypeDefinition.Prop.Id aggChildId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
                TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        TypeDefinition.Prop.Scalar aggChildName = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);
        TypeDefinition.Aggregate aggChild = new TypeDefinition.Aggregate(
                "AggregateChild", "aggregate_child", aggChildId, null,
                List.of(aggChildName), List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.EntityCollection entityCollection = new TypeDefinition.Prop.EntityCollection(
                "aggregateItems", "java.util.List", aggChild, "parent_id", "parent");

        return new TypeDefinition.Aggregate(
                "TestAggregate", "test_aggregate", id, null,
                List.of(name), List.of(), List.of(), List.of(entityCollection), List.of());
    }
}
