package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MutationPlan.CascadeMode;
import io.github.fludakit.data.processor.context.MutationPlan.CascadeSpec;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.H2Dialect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the consolidated post-cascade emission: one renderer, one sink, and a step that
 * contributes no code never reaches the sink.
 */
class CascadeSupportTest {

    /** The paths under test never resolve a type, so they need no {@code Elements}. */
    private static final WriteSupport SUPPORT = new WriteSupport(null);

    @Test
    void stepWithoutCodeNeverReachesTheSink() {
        CascadeSupport cascades = new CascadeSupport(H2Dialect.INSTANCE, SUPPORT);

        List<CodeBlock> emitted = new ArrayList<>();
        // PRE_INSERT is rendered by the pre-insert path; the post-cascade renderer has no code for it.
        cascades.emitPostCascadeSpec(
                new CascadeSpec(null, new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", true),
                        CascadeMode.PRE_INSERT, List.of()),
                null, "entity", emitted::add);

        assertTrue(emitted.isEmpty(), "a step with no post-cascade code must not reach the sink");
    }

    @Test
    void stepWithCodeReachesTheSinkExactlyOnce() {
        CascadeSupport cascades = new CascadeSupport(H2Dialect.INSTANCE, SUPPORT);

        TypeDefinition.Entity child = new TypeDefinition.Entity(
                "casc.Child", "child",
                new TypeDefinition.Prop.Id("id", "java.lang.Long", "id"),
                null, List.of(), List.of(), List.of(), List.of(), List.of());
        TypeDefinition.Prop.EntityCollection collection =
                new TypeDefinition.Prop.EntityCollection("children", "java.util.List", child, "parent_id", null);
        RepositoryContext ctx = new RepositoryContext(
                child, ClassName.get("casc", "ChildRowMapper"), null, List.of(), null);

        List<CodeBlock> emitted = new ArrayList<>();
        cascades.emitPostCascadeSpec(
                new CascadeSpec(child, collection, CascadeMode.CASCADE_DELETE, List.of()),
                ctx, "entity", emitted::add);

        assertEquals(1, emitted.size(), "the step must reach the sink exactly once");
        assertTrue(emitted.getFirst().toString().contains("jdbcClient.sql(EC_COLL_DELETE_CHILDREN)"),
                "the rendered step must delete the child set by fk: " + emitted);
    }
}