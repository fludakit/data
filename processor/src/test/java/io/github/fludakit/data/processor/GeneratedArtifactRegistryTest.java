package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name-claiming and dedup rules behind every generated type name, pinned WITHOUT driving javac.
 *
 * <p>{@link GeneratedArtifactRegistry} is the only collaborator in this package that touches no
 * {@code ProcessingEnvironment}: it holds four maps and a set, and every rule the generator relies on
 * — a claimed name stays claimed, a metadata type is visible to whoever asks next, a row mapper asked
 * for before it is emitted yields {@code null} rather than a placeholder — is expressible as a
 * three-line assertion. Before the registry existed those four collections were private fields of
 * {@link RepositoryProcessor}, so the only way to test any of it was to compile a repository and read
 * back what it wrote.</p>
 *
 * <p>Two of these tests pin a SHARING rule rather than a value:
 * {@link #theMetadataMapIsHandedOutLiveSoTheGeneratorThatFillsItIsTheOneCallersDedupeAgainst} and
 * {@link #aPreClaimedMetamodelNameIsAlreadyTakenWhenANavClassAsks}. Those matter because a
 * registry that handed out COPIES would let a caller deduplicate against a snapshot instead of against
 * what has actually been written, and the bug that produces is one source file written twice — which
 * {@code Filer} reports as an error about a type the user never declared.</p>
 */
class GeneratedArtifactRegistryTest {

    private static final ClassName MAPPER = ClassName.get("relpath", "OrderRowMapper");
    private static final ClassName METADATA = ClassName.get("relpath", "OrderMetadata");
    private static final String ORDER = "relpath.Order";

    @Test
    void aClaimedNameStaysClaimedAndAnUnclaimedOneIsNot() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        assertFalse(registry.isClaimed("relpath._Order"), "nothing has been claimed yet");

        registry.claim("relpath._Order");
        assertTrue(registry.isClaimed("relpath._Order"),
                "a pre-claimed _Order metamodel name must stay claimed, or a nav class derived from a"
                        + " DIFFERENT entity's member name wins the race and Filer throws");

        assertFalse(registry.isClaimed("relpath._OrderItem"),
                "claiming one name must not claim its neighbours — _OrderItem has to stay available"
                        + " to the OrderItem aggregate root's own metamodel");
    }

    @Test
    void aPreClaimedMetamodelNameIsAlreadyTakenWhenANavClassAsks() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        // The round pre-scan claims every aggregate-root metamodel BEFORE any nav class is generated,
        // which is what makes the outcome independent of the order the repositories are processed in.
        registry.claim("relpath._OrderItem");
        assertTrue(registry.isClaimed("relpath._OrderItem"),
                "an Order.item relation nav is relpath._OrderItem — the same FQN the OrderItem root's"
                        + " own metamodel claims, so the nav must find it taken and degrade to the NOTE"
                        + " path instead of colliding");
    }

    @Test
    void theMetadataMapIsHandedOutLiveSoTheGeneratorThatFillsItIsTheOneCallersDedupeAgainst() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        Map<String, ClassName> shared = registry.metadataTypes();
        assertFalse(registry.hasMetadata(ORDER));

        // TypeMappingGenerator populates the map BEFORE it emits the class — it is the value-object cycle
        // guard, not a record of what is finished. Callers must see that write immediately.
        shared.put(ORDER, METADATA);
        assertTrue(registry.hasMetadata(ORDER),
                "the map is handed out by reference on purpose: a copy would let a caller deduplicate"
                        + " against a snapshot instead of against what has actually been written");
        assertSame(METADATA, registry.metadataType(ORDER));
        assertSame(shared, registry.metadataTypes(),
                "every caller shares ONE map, so two generators over the same entity see each other");
    }

    @Test
    void aRowMapperAskedForBeforeItIsEmittedYieldsNullNotAPlaceholder() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        assertNull(registry.rowMapper(ORDER),
                "a target whose mapper is not emitted yet must read as absent, not as a guess: the"
                        + " caller turns null into the on-demand emission path");
        assertFalse(registry.hasRowMapper(ORDER));

        registry.rememberRowMapper(ORDER, MAPPER);
        assertTrue(registry.hasRowMapper(ORDER));
        assertSame(MAPPER, registry.rowMapper(ORDER),
                "the second repository over the same entity must get the FIRST one's mapper, or the"
                        + " same type is written twice");
    }

    @Test
    void aMetamodelIsRememberedOnceSoSeveralRepositoriesOverOneEntityProduceOne() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        assertFalse(registry.hasMetamodel(ORDER),
                "the first repository over an entity must be the one that emits its _Entity metamodel");

        registry.rememberMetamodel(ORDER, ClassName.get("relpath", "_Order"));
        assertTrue(registry.hasMetamodel(ORDER),
                "the second repository over the same entity must find it already emitted and skip it,"
                        + " or the same _Order interface is written twice");

        registry.rememberMetamodel(ORDER, ClassName.get("other", "_Order"));
        assertTrue(registry.hasMetamodel(ORDER),
                "unlike metadataTypes(), the metamodel map is private to the registry and the guard is"
                        + " hasMetamodel — nothing outside can overwrite an emitted metamodel, so a"
                        + " re-remember cannot silently retarget one");
    }

    @Test
    void managedEntitiesStartEmptyAndAreReplacedWholeEachRound() {
        GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();
        assertEquals(Set.of(), registry.managedEntities(),
                "before the round pre-scan nothing is managed, so no relationship is eligible to"
                        + " hydrate — an unmanaged target has no row mapper to delegate to");

        registry.managedEntities(Set.of(ORDER, "relpath.Customer"));
        assertEquals(Set.of(ORDER, "relpath.Customer"), registry.managedEntities());

        // Recomputed each round: a repository that disappears must not keep its entity hydratable.
        registry.managedEntities(Set.of(ORDER));
        assertEquals(Set.of(ORDER), registry.managedEntities());
    }
}
