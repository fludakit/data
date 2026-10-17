package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.ClassName;

import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.query.QueryDefinition;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything this processing SESSION has already produced or already claimed a name for, behind one
 * API. It survives rounds, which is the whole point: the same entity reached from two repositories
 * must emit its {@code <Entity>Metadata}, its {@code _Entity} metamodel and its
 * {@code <Entity>RowMapper} exactly once, however the repositories are ordered across rounds.
 *
 * <p>The four collections were private fields of {@link RepositoryProcessor} and are otherwise
 * unchanged — this class adds no new bookkeeping, it gives the four one name and one vocabulary
 * ({@code claim}/{@code isClaimed}, {@code remember…}/{@code …Of}). Two of them are handed out LIVE
 * and are still mutated by the generators that own them: {@link #metadataTypes()} is filled by
 * {@link TypeMappingGenerator} as it emits descriptors (and is the value-object cycle guard, so it is
 * populated <em>before</em> the class is written), and {@link #claimedNames()} is the set
 * {@link RepositorySourceWriter} and {@link MetamodelGenerator} consult and extend. Neither is
 * copied here, because a copy would let a caller deduplicate against a snapshot instead of against
 * what has actually been written.</p>
 *
 * <p>Pure state: no {@code ProcessingEnvironment}, no {@code Filer}, no generator. That is what makes
 * the name-claiming rules testable without driving javac.</p>
 */
public class GeneratedArtifactRegistry {

    /** Entity FQN → generated {@code <Entity>RowMapper} type. */
    private final Map<String, ClassName> rowMappers = new LinkedHashMap<>();

    /**
     * Value object element collection name → generated {@code <VO>RowMapper} type. Pre-computed
     * during the analysis phase so planners can read without triggering generation.
     */
    private final Map<String, ClassName> voRowMappers = new LinkedHashMap<>();

    /**
     * Repository FQN → (field name → query definition). Each query-by-names method contributes one
     * entry; the field name is the UPPER_SNAKE constant used in the generated {@code <Repository>Queries}
     * holder. Stored here rather than in the per-repository emission so the registry is the single
     * cache for everything the session has produced.
     */
    private final Map<String, Map<String, QueryDefinition>> queryDefinitions = new LinkedHashMap<>();

    /**
     * Entity/value-object FQN → generated {@code <Entity>Metadata} type. Doubles as the
     * "already generated" guard for the EMBEDDED value-object metadata tree, so it is populated
     * <em>before</em> the class is emitted (cycle-bounded).
     */
    private final Map<String, ClassName> metadataTypes = new LinkedHashMap<>();

    /** Entity FQN → generated {@code _Entity} static metamodel type. */
    private final Map<String, ClassName> metamodelTypes = new LinkedHashMap<>();

    /**
     * Every generated type FQN this session claims or emits: the {@code _Entity} metamodels, the
     * navigable member classes ({@code _<Entity><Field>} chains), the {@code <Entity>Metadata}
     * descriptors, the row mappers and the repository implementations. A nav-class name is derived
     * from its OWNER-RELATIVE member name, so it can collide with a DIFFERENT entity's generated
     * type (an {@code Order.item} relation nav is {@code pkg._OrderItem} — the same FQN as the
     * {@code OrderItem} aggregate root's own metamodel). The nav-collision guard consults this set so
     * a colliding nav degrades to the NOTE + plain attribute record instead of
     * {@code Filer.createSourceFile} throwing and aborting the user build.
     */
    private final Set<String> generatedTypeNames = new HashSet<>();

    /**
     * FQNs of every entity with a {@code @Repository} in the CURRENT round. A
     * {@code MANY_TO_ONE}/{@code ONE_TO_MANY} property hydrates only if its target is here AND the
     * target has a generated row mapper: an unmanaged target has no {@code <Target>RowMapper} to
     * delegate to, so the reference is left unmapped on read. Recomputed each round.
     */
    private Set<String> managedEntities = Set.of();

    // ------------------------------------------------------------------ name claiming

    /**
     * The live set of claimed generated-type FQNs, shared with {@link RepositorySourceWriter} and
     * {@link MetamodelGenerator}: both must see (and add to) what has already been taken, not a
     * snapshot of it.
     */
    public Set<String> claimedNames() {
        return generatedTypeNames;
    }

    /**
     * Takes a generated-type FQN BEFORE anything is written to it. Pre-claiming is what makes the
     * name race independent of repository iteration order: a {@code _Entity} metamodel and a
     * {@code <Repository>Queries} holder are claimed in the round pre-scan, so a nav class derived
     * from a DIFFERENT entity's member name always loses and degrades to the NOTE path.
     */
    public void claim(String fqn) {
        generatedTypeNames.add(fqn);
    }

    public boolean isClaimed(String fqn) {
        return generatedTypeNames.contains(fqn);
    }

    // ------------------------------------------------------------- managed entity set

    public Set<String> managedEntities() {
        return managedEntities;
    }

    public void managedEntities(Set<String> managed) {
        this.managedEntities = managed;
    }

    // ------------------------------------------------------------- metadata / row mapper

    /**
     * The live entity/value-object FQN → {@code <Entity>Metadata} map, shared with
     * {@link TypeMappingGenerator} and {@link RowMapperGenerator}: the former fills it, the latter reads
     * it to type the mapper it emits.
     */
    public Map<String, ClassName> metadataTypes() {
        return metadataTypes;
    }

    public boolean hasMetadata(String entityFqn) {
        return metadataTypes.containsKey(entityFqn);
    }

    /** The {@code <Entity>Metadata} type for {@code entityFqn}, or {@code null} when not yet emitted. */
    public ClassName metadataType(String entityFqn) {
        return metadataTypes.get(entityFqn);
    }

    public boolean hasRowMapper(String entityFqn) {
        return rowMappers.containsKey(entityFqn);
    }

    /** The {@code <Entity>RowMapper} type for {@code entityFqn}, or {@code null} when not yet emitted. */
    public ClassName rowMapper(String entityFqn) {
        return rowMappers.get(entityFqn);
    }

    public void rememberRowMapper(String entityFqn, ClassName type) {
        rowMappers.put(entityFqn, type);
    }

    // --------------------------------------------------------- value object row mappers

    /**
     * The {@code <VO>RowMapper} type for a value object element collection, or {@code null} when not
     * pre-computed. Planners read this during the planning phase without triggering generation.
     */
    public ClassName voRowMapper(String collectionName) {
        return voRowMappers.get(collectionName);
    }

    public void rememberVoRowMapper(String collectionName, ClassName type) {
        voRowMappers.put(collectionName, type);
    }

    // -------------------------------------------------------------- query definitions

    /**
     * The query definitions for a repository, keyed by field name. Returns the live map so callers
     * can add to it directly.
     */
    public Map<String, QueryDefinition> queryDefinitions(String repositoryFqn) {
        return queryDefinitions.computeIfAbsent(repositoryFqn, k -> new LinkedHashMap<>());
    }

    /** All query definitions for a repository, or an empty map if none. */
    public Map<String, QueryDefinition> queryDefinitionsOf(String repositoryFqn) {
        return queryDefinitions.getOrDefault(repositoryFqn, Map.of());
    }

    // ------------------------------------------------------------------- static metamodel

    /**
     * Records the {@code _Entity} metamodel FQN so several repositories over the same entity produce
     * one metamodel. The map is private to this registry — unlike {@link #metadataTypes()}, nothing
     * outside adds to it — so callers ask {@link #hasMetamodel} instead of reading it.
     */
    public boolean hasMetamodel(String entityFqn) {
        return metamodelTypes.containsKey(entityFqn);
    }

    public void rememberMetamodel(String entityFqn, ClassName type) {
        metamodelTypes.put(entityFqn, type);
    }
}
