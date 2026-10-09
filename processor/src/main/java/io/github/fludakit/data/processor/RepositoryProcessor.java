package io.github.fludakit.data.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.model.DataModel;
import io.github.fludakit.data.model.DataModel.Prop;
import io.github.fludakit.data.model.DataModel.Stereotype;
import io.github.fludakit.data.model.EmbeddedValueObjectModel;
import io.github.fludakit.data.model.EntityDataModel;
import io.github.fludakit.data.model.ExternalValueObjectModel;
import io.github.fludakit.data.page.CursorSupport;
import io.github.fludakit.data.page.SimpleCursoredPage;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResolvedPredicate;
import io.github.fludakit.data.query.ResolvedSort;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.jdbc.JdbcClient;
import io.github.fludakit.jdbc.support.GeneratedKeyHolder;
import jakarta.data.Sort;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates four artifacts at compile time: a {@code <Repository>Impl} class for every
 * {@link Repository @Repository} interface, a reflection-free {@code <Entity>Metadata} class
 * implementing {@link io.github.fludakit.data.metadata.EntityDescriptor} (the compile-time descriptor model behind all SQL), a
 * {@code <Entity>RowMapper} for entities whose fields are all JDBC-safe or embeddable value objects,
 * and a Jakarta Data 1.0 {@code _Entity} static metamodel interface per aggregate root
 * (see {@link #generateStaticMetamodel}).
 *
 * <p>The generated implementation class is {@link ApplicationScoped} and carries a single injected
 * collaborator, a {@link JdbcClient}. Every query's SQL is emitted as a {@code private static final
 * String} constant and executed with direct {@code jdbcClient.sql(...).param(...).query(...)}
 * calls: there is no runtime query model and no engine indirection. The entity's table/columns are
 * derived at compile time from its fields by convention (see {@link Names}).</p>
 */
@SupportedAnnotationTypes("jakarta.data.repository.Repository")
public class RepositoryProcessor extends AbstractProcessor {

    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";
    private static final String BASIC_REPOSITORY = "jakarta.data.repository.BasicRepository";
    private static final String DATA_REPOSITORY = "jakarta.data.repository.DataRepository";

    private static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    private static final String ORDER = "jakarta.data.Order";
    private static final String LIMIT = "jakarta.data.Limit";
    private static final String SORT = "jakarta.data.Sort";

    /** Jakarta Data 1.0 §3.3 static metamodel types (string-form so the processor needs no new deps). */
    private static final ClassName MM_ATTRIBUTE = ClassName.get("jakarta.data.metamodel", "Attribute");
    private static final ClassName MM_STATIC_METAMODEL =
            ClassName.get("jakarta.data.metamodel", "StaticMetamodel");
    private static final ClassName MM_GENERATED = ClassName.get("jakarta.annotation", "Generated");

    /**
     * FluDa's extended attribute types ({@code io.github.fludakit.data.metamodel}): the Jakarta Data
     * 1.0 attribute subtypes the generated metamodel fields instantiate, so one field instance is
     * both the user-facing sort anchor and the runtime descriptor attribute.
     */
    private static final ClassName FM_COLUMN = ClassName.get("io.github.fludakit.data.metamodel", "ColumnAttribute");
    private static final ClassName FM_ID = ClassName.get("io.github.fludakit.data.metamodel", "IdAttribute");
    private static final ClassName FM_SORTABLE = ClassName.get("io.github.fludakit.data.metamodel", "SortableColumnAttribute");
    private static final ClassName FM_TEXT = ClassName.get("io.github.fludakit.data.metamodel", "TextColumnAttribute");
    private static final ClassName FM_EMBEDDED = ClassName.get("io.github.fludakit.data.metamodel", "EmbeddableAttribute");
    private static final ClassName FM_FK = ClassName.get("io.github.fludakit.data.metamodel", "EntityAttribute");
    private static final ClassName FM_OTM = ClassName.get("io.github.fludakit.data.metamodel", "ListAttribute");
    private static final ClassName FM_EC = ClassName.get("io.github.fludakit.data.metamodel", "ListAttribute");
    private static final ClassName FM_COLUMN_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ColumnAttributeRecord");
    private static final ClassName FM_ID_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "IdAttributeRecord");
    private static final ClassName FM_SORTABLE_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "SortableColumnAttributeRecord");
    private static final ClassName FM_TEXT_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "TextColumnAttributeRecord");
    private static final ClassName FM_EMBEDDED_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "EmbeddableAttributeRecord");
    private static final ClassName FM_FK_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "EntityAttributeRecord");
    private static final ClassName FM_OTM_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ListAttributeRecord");
    private static final ClassName FM_EC_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ListAttributeRecord");
    private static final ClassName MD_COLUMN_REF =
            ClassName.get("io.github.fludakit.data.metadata", "ColumnRef");
    private static final ClassName MD_JOIN_REF =
            ClassName.get("io.github.fludakit.data.metadata", "JoinRef");

    // Plan E (E2): the query-metadata types the generated <Repository>Model serialises literals with.
    private static final ClassName Q_QUERY_MODEL =
            ClassName.get("io.github.fludakit.data.query", "QueryModel");
    private static final ClassName Q_OPERATION =
            ClassName.get("io.github.fludakit.data.query", "Operation");
    private static final ClassName Q_RESULT_KIND =
            ClassName.get("io.github.fludakit.data.query", "ResultKind");
    private static final ClassName Q_PAGING_MODE =
            ClassName.get("io.github.fludakit.data.query", "PagingMode");
    private static final ClassName Q_PREDICATE =
            ClassName.get("io.github.fludakit.data.query", "Predicate");
    private static final ClassName Q_SORT_SPEC =
            ClassName.get("io.github.fludakit.data.query", "SortSpec");
    private static final ClassName Q_OPERATOR =
            ClassName.get("io.github.fludakit.data.query", "Operator");
    private static final ClassName Q_RESOLVED_PREDICATE =
            ClassName.get("io.github.fludakit.data.query", "ResolvedPredicate");
    private static final ClassName Q_RESOLVED_SORT =
            ClassName.get("io.github.fludakit.data.query", "ResolvedSort");
    private static final ClassName Q_SEEK_TERM =
            ClassName.get("io.github.fludakit.data.query", "SeekTerm");
    private static final ClassName Q_PARAM_BINDING =
            ClassName.get("io.github.fludakit.data.query", "ParamBinding");

    /** Attribute-name types that get the spec's {@code TextAttribute} treatment. */
    private static final Set<String> METAMODEL_TEXT_TYPES = Set.of(
            "java.lang.String", "char", "java.lang.Character");

    /** Attribute-name types that get the spec's {@code SortableAttribute} treatment. */
    private static final Set<String> METAMODEL_SORTABLE_TYPES = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.ZonedDateTime", "java.util.Date");

    private static final Set<String> CONTAINER_TYPES = Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
            "java.util.Optional", "java.util.stream.Stream");

    /**
     * Field types the reflection-free {@code <Entity>RowMapper} can read straight from a
     * {@code ResultSet} (via {@code getObject(...)} or a primitive getter). Any entity carrying a
     * field outside this allowlist (enums, non-embeddable types, ...) is skipped so it keeps
     * using the existing reflective mapping instead of generating code that would not compile.
     * {@code @ValueObject}-typed fields are not in this set: they pass the gate through the
     * {@code EMBEDDED} recursion in {@link #canGenerateRowMapper} and are read leaf-by-leaf from
     * their flattened columns.
     * Note that {@code java.time.Instant}, {@code java.time.ZonedDateTime} and
     * {@code java.math.BigInteger} are read via {@code rs.getObject(col, X.class)}, whose driver
     * support beyond the types guaranteed by JDBC 4.2 ({@code LocalDate}/{@code LocalTime}/
     * {@code LocalDateTime}/{@code OffsetDateTime} and the basic types) is driver-dependent; an
     * unsupported driver fails fast at runtime with {@code SQLFeatureNotSupportedException}.
     */
    private static final Set<String> JDBC_SAFE_TYPES = Set.of(
            "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.math.BigDecimal", "java.math.BigInteger",
            "java.sql.Date", "java.sql.Time", "java.sql.Timestamp",
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime",
            "java.util.UUID",
            "boolean", "byte", "short", "int", "long", "float", "double");

    private Types types;
    private Elements elements;

    /** Entity FQN → generated {@code <Entity>RowMapper} type, deduped across processing rounds. */
    private final Map<String, ClassName> rowMappers = new LinkedHashMap<>();

    /**
     * Entity/value-object FQN → generated {@code <Entity>Metadata} type. Doubles as the
     * "already generated" guard for the EMBEDDED value-object metadata tree, so it is populated
     * <em>before</em> the class is emitted (cycle-bounded).
     */
    private final Map<String, ClassName> metadataTypes = new LinkedHashMap<>();

    /**
     * Entity FQN → generated {@code _Entity} Jakarta Data static metamodel type. Doubles as the
     * "already emitted" guard so several repositories over the same entity produce one metamodel.
     */
    private final Map<String, ClassName> metamodelTypes = new LinkedHashMap<>();

    /** Entity/value-object FQN → compile-time descriptor, memoized for {@link #descriptorFor}. */
    private final Map<String, DataModel> descriptorCache = new HashMap<>();

    /**
     * Every generated type FQN this processing session claims or emits: the {@code _Entity}
     * metamodels, the navigable member classes ({@code _<Entity><Field>} chains), the
     * {@code <Entity>Metadata} descriptors, the row mappers and the repository implementations.
     * A nav-class name is derived from its OWNER-RELATIVE member name, so it can collide with a
     * DIFFERENT entity's generated type (an {@code Order.item} relation nav is {@code pkg._OrderItem}
     * — the same FQN as the {@code OrderItem} aggregate root's own metamodel). The nav-collision
     * guard consults this registry so a colliding nav degrades to the NOTE + plain attribute
     * record instead of {@code Filer.createSourceFile} throwing and aborting the user build.
     * {@code _Entity} metamodel FQNs are additionally PRE-CLAIMED in {@link #process}'s round
     * pre-scan — metamodels always win the name, independent of repository iteration order — and
     * every emission registers through {@link #writeSourceFile}. Session-scoped (survives rounds),
     * mirroring the cross-round dedup of {@link #metamodelTypes}.
     */
    private final Set<String> generatedTypeNames = new HashSet<>();

    /** FQNs whose descriptor is currently being built; the cycle guard for {@link #descriptorFor}. */
    private final Set<String> inProgress = new HashSet<>();

    /** Embedded value-object FQN → compile-time model, memoized for {@link #voModel}. */
    private final Map<String, EntityModel> voModels = new HashMap<>();

    /** FQNs whose embedded model is currently on the build stack; the cycle guard for {@link #voModel}. */
    private final Set<String> voModelsInProgress = new HashSet<>();

    /**
     * Value-object FQN → generated {@code <VO>RowMapper} type, deduped across processing rounds.
     * Populated for {@code List<@ValueObject>} element-collection children (Task 3) so a VO reused by
     * several collections/repositories is mapped by one shared mapper; Task 4 reuses the same cache
     * for many-to-one/one-to-many child hydration.
     */
    private final Map<String, ClassName> voRowMappers = new LinkedHashMap<>();

    /**
     * Entity FQN → compile-time {@link EntityModel} for a relationship TARGET resolved by the
     * managed-target gate (Task 4). Like {@link #voModels}, {@link #buildEntity} is idempotent, so this
     * is a pure memo; a target reused by several repositories is walked once.
     */
    private final Map<String, EntityModel> entityModels = new HashMap<>();

    /**
     * FQNs of every entity with a {@code @Repository} in the CURRENT round, computed in {@link #process}
     * before {@link #generate} dispatch. A {@code MANY_TO_ONE}/{@code ONE_TO_MANY} property hydrates
     * only if its target is here AND the target has a generated row mapper (Decision 7): an unmanaged
     * target has no {@code <Target>RowMapper} to delegate to, so the reference is left unmapped on read.
     * {@code getSupportedAnnotationTypes} hands all repositories in one {@code Set}, so the round set is
     * complete; recomputed each round.
     */
    private Set<String> managedEntities = Set.of();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        // Round pre-scan (Task 4 / Decision 7): the set of managed entity FQNs (every entity that some
        // @Repository in THIS round resolves to) must be known BEFORE generate() dispatch, so a
        // repository processed early in the round can already see targets that only get their own
        // repository later in the same round. getSupportedAnnotationTypes hands all repositories in one
        // Set, so this scan is complete for the round.
        List<TypeElement> repositories = new ArrayList<>();
        Set<String> managed = new HashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Repository.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            TypeElement repository = (TypeElement) element;
            repositories.add(repository);
            TypeMirror entityMirror = resolveEntityType(repository, new HashSet<>());
            if (entityMirror != null && entityMirror.getKind() != TypeKind.TYPEVAR
                    && entityMirror.getKind() != TypeKind.WILDCARD
                    && types.erasure(entityMirror) instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement entityElement) {
                managed.add(entityElement.getQualifiedName().toString());
                // Pre-claim every aggregate-root metamodel FQN BEFORE any nav class is generated:
                // a nav for another entity (e.g. Order.item → pkg._OrderItem) must lose the name
                // race and degrade to the NOTE path, whichever repository the round processes first.
                // Value objects get no metamodel, so their name stays available to owners' navs.
                if (!isValueObjectType(entityElement)) {
                    generatedTypeNames.add(elements.getPackageOf(entityElement).getQualifiedName()
                            + "._" + entityElement.getSimpleName());
                }
            }
            // Plan E (E2): the <Repository>Model holder name is claimed up-front, exactly like the
            // _Entity metamodel, so a nav class can never win the race against it.
            generatedTypeNames.add(elements.getPackageOf(repository).getQualifiedName()
                    + "." + repository.getSimpleName() + "Model");
        }
        this.managedEntities = Set.copyOf(managed);
        boolean claimed = false;
        for (TypeElement repository : repositories) {
            generate(repository);
            claimed = true;
        }
        return claimed;
    }

    // --------------------------------------------------------------- generation

    private void generate(TypeElement repository) {
        TypeMirror entityMirror = resolveEntityType(repository, new HashSet<>());
        if (entityMirror == null || entityMirror.getKind() == TypeKind.TYPEVAR
                || entityMirror.getKind() == TypeKind.WILDCARD
                || !(types.erasure(entityMirror) instanceof DeclaredType)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Repository interface " + repository.getQualifiedName()
                            + " must extend DataRepository/BasicRepository/CrudRepository with a concrete entity type",
                    repository);
            return;
        }
        DeclaredType entityErasure = (DeclaredType) types.erasure(entityMirror);
        TypeElement entityElement = (TypeElement) entityErasure.asElement();
        EntityModel entity = buildEntity(entityElement);
        String entityFqn = entityElement.getQualifiedName().toString();
        ClassName mapperType = null;
        boolean mapperPending = false;
        if (canGenerateRowMapper(entity)) {
            mapperType = rowMappers.get(entityFqn);
            mapperPending = mapperType == null;
        }
        // Jakarta Data 1.0 §3.3 static metamodel + <Entity>Metadata descriptor: the metamodel is
        // additive for aggregate roots (it references no JDBC types) and its field instances ARE the
        // descriptor's attributes (Decision 1). Value objects surface only as navigable member
        // classes (_<Entity><Field> chains) of their owner's metamodel, never as `_VO` classes.
        // The descriptor is generated for EVERY repository entity (not just mappable ones): dynamic
        // paging resolves sort paths through <Entity>Metadata.columnRef, so the descriptor is a hard
        // runtime dependency that stands independent of the row-mapper gate.
        ensureDescriptorArtifacts(entityElement, entity, true);
        if (mapperPending) {
            mapperType = generateRowMapper(entityElement, entity, metadataTypes.get(entityFqn));
            rowMappers.put(entityFqn, mapperType);
        }
        // EMBEDDED value objects carry descriptor metadata even when the owner's mapper gate skips
        // them; the generated <VO>Metadata classes are what the owner's attributes() reference.
        generateEmbeddedDescriptors(entity.metadata());

        // Element-collection hydration (Task 3): resolve a generated <VO>RowMapper for each
        // List<@ValueObject> child. A child whose VO fails the mapper gate is skipped — hydration
        // then degrades to the parent's default (empty) field value rather than failing the whole
        // repository, matching the "gate fails closed" spirit of the row-mapper gate (Decision 7).
        List<ElementCollection> hydratedCollections = new ArrayList<>();
        Map<String, ClassName> collectionMappers = new LinkedHashMap<>();
        for (ElementCollection c : entity.collections()) {
            ClassName voMapper = voRowMapperFor(c, entityElement);
            if (voMapper != null) {
                hydratedCollections.add(c);
                collectionMappers.put(c.field(), voMapper);
            }
        }
        // Relationship hydration (Task 4 / Decision 7 managed-target gate): a MANY_TO_ONE/ONE_TO_MANY
        // property hydrates only when its TARGET is managed (some @Repository in this round resolves to
        // it — see process()'s pre-scan) AND has a generated row mapper to delegate to, and (for
        // ONE_TO_MANY) buildEntity's second pass resolved the inverse. Ineligible relationships get NO
        // loader and one NOTE each: the reference is left unmapped on read rather than failing the
        // repository or calling a mapper that was never emitted. The gate is checked BEFORE resolving
        // the target model so an unmanaged target never gets a row mapper generated for it.
        List<EntityRelationship> hydratedRelationships = new ArrayList<>();
        for (Prop a : entity.metadata().props()) {
            // Java 17 safety: instanceof, never a pattern switch. MANY_TO_ONE is a composing
            // Reference; an external @Association (composition=false) is not hydrated here.
            boolean manyToOne = a instanceof Prop.Reference ref && ref.composition();
            boolean oneToMany = a instanceof Prop.EntityList;
            if (!manyToOne && !oneToMany) {
                continue;
            }
            if (oneToMany && ((Prop.EntityList) a).inverseName() == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "relationship '" + a.name() + "' on " + entityElement.getQualifiedName()
                                + ": one-to-many inverse is unresolved on the target — left unmapped on read",
                        entityElement);
                continue;
            }
            String targetFqn = a instanceof Prop.EntityList el
                    ? el.targetTypeName() : ((Prop.Reference) a).targetTypeName();
            TypeElement targetElement = targetFqn == null ? null : elements.getTypeElement(targetFqn);
            ClassName targetMapper = null;
            EntityModel targetModel = null;
            if (targetElement != null && managedEntities.contains(targetFqn)) {
                targetModel = entityModel(targetElement);
                targetMapper = ensureEntityRowMapper(targetElement, targetModel);
            }
            if (targetMapper == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "relationship '" + a.name() + "' on " + entityElement.getQualifiedName()
                                + ": target " + targetFqn + " has no generated row mapper — left unmapped on read",
                        entityElement);
                continue;
            }
            hydratedRelationships.add(new EntityRelationship(a, targetModel, targetMapper));
        }
        // Aggregate write cascade (Task 11 fix / ruling R-T11a, fix-round 2 findings I-1 + I-2).
        // I-1 — stereotype gate: the composition lifecycle is bound to the root ONLY for
        // @AggregateRoot owners (Requirements Lock md:33); inside an @Entity an entity collection is a
        // "collection of associated objects" whose lifecycle is NOT bound (md:34), so a replace-all
        // there would silently DELETE associated child rows. Non-root owners therefore get NO write
        // cascade at all — their read hydration loaders (the scan above) are untouched.
        // I-2 — write-gate decoupling: membership is computed from EVERY EntityList on the descriptor,
        // NOT from the read-gate-passed hydratedRelationships, mirroring the element-collection write
        // path (appendUpdateStatements "iterates EVERY collection"): the cascade INSERT/DELETE constants
        // are plain SQL driven by the child's OWN table metadata + generated-key-coercible id
        // (entityCollectionWriteBlocker) and need no row mapper. A target that fails the managed/mapper
        // read gate thereby loses only its hydration LOADER, never its write cascade. Every write skip
        // emits its own write-honest NOTE ("children will NOT be cascaded on save/update/delete") so
        // save() can never silently drop children without write-side diagnostics.
        List<EntityRelationship> cascadeRelationships = new ArrayList<>();
        if (stereotypeOf(entityElement) == Stereotype.AGGREGATE_ROOT) {
            for (Prop a : entity.metadata().props()) {
                if (!(a instanceof Prop.EntityList list)) {
                    continue;
                }
                String targetFqn = list.targetTypeName();
                TypeElement targetElement = targetFqn == null ? null : elements.getTypeElement(targetFqn);
                // entityModel() is a pure memo of the idempotent buildEntity — safe for targets the
                // read gate excluded; no mapper or descriptor artifact is generated from this path.
                EntityModel targetModel = targetElement == null ? null : entityModel(targetElement);
                String blocker = targetModel == null
                        ? "target type " + targetFqn + " is unresolvable"
                        : list.inverseName() == null
                                ? "one-to-many inverse is unresolved on the target"
                                : entityCollectionWriteBlocker(targetModel);
                if (blocker != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "entity collection '" + list.name() + "' on " + entityElement.getQualifiedName()
                                    + ": " + blocker + " — children will NOT be cascaded on save/update/delete",
                            entityElement);
                    continue;
                }
                // The write emitters read only attribute()/targetModel(); the row mapper is
                // irrelevant to plain-SQL writes, so a cascade member may carry a null mapper.
                cascadeRelationships.add(new EntityRelationship(a, targetModel, null));
            }
        }
        boolean hydrate = hasGeneratedHydration(entity, mapperType, hydratedCollections, hydratedRelationships);

        DeclaredType repositoryType = (DeclaredType) repository.asType();
        boolean crud = extendsCrud(repository, new HashSet<>());
        String packageName = elements.getPackageOf(repository).getQualifiedName().toString();
        String implementationName = repository.getSimpleName() + "Impl";

        Map<String, String> constants = new LinkedHashMap<>();
        List<MethodSpec> methods = new ArrayList<>();
        // Plan E (E2): one resolved query model per query-by-names method, in declaration order;
        // @Query methods and the CRUD built-ins carry no resolved structure and stay out.
        List<ResolvedQueryModel> queryModels = new ArrayList<>();
        Map<String, Integer> queryModelNames = new HashMap<>();
        boolean paginatedFindAll = false;
        for (Element member : elements.getAllMembers(repository)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            if (method.getEnclosingElement().toString().equals("java.lang.Object")) {
                continue;
            }
            if (method.getSimpleName().contentEquals("findAll") && method.getParameters().size() == 2) {
                paginatedFindAll = true;
            }
            methods.add(generateMethod(method, repositoryType, entity, crud, constants, mapperType, hydrate,
                    methods, queryModels, queryModelNames, cascadeRelationships));
        }
        // Registered before the field loop below so the loaders' SELECT constants render as fields.
        if (hydrate) {
            // One umbrella reached from every read shape, delegating to a loader per hydrated
            // collection (Task 3) and per hydrated entity relationship (Task 4).
            methods.add(hydrationUmbrella(entity, hydratedCollections, hydratedRelationships));
            for (ElementCollection c : hydratedCollections) {
                methods.add(appendCollectionHydration(constants, entity, c, collectionMappers.get(c.field())));
            }
            for (EntityRelationship r : hydratedRelationships) {
                methods.add(r.attribute() instanceof Prop.Reference
                        ? appendManyToOneHydration(constants, entity, r)
                        : appendOneToManyHydration(constants, entity, r));
            }
        }

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implementationName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ApplicationScoped.class)
                .addSuperinterface(TypeName.get(repository.asType()));
        for (Map.Entry<String, String> constant : constants.entrySet()) {
            classBuilder.addField(FieldSpec.builder(String.class, constant.getKey(),
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", constant.getValue())
                    .build());
        }
        classBuilder.addField(FieldSpec.builder(JdbcClient.class, "jdbcClient", Modifier.PRIVATE)
                .addAnnotation(Inject.class)
                .build());
        methods.forEach(classBuilder::addMethod);
        if (paginatedFindAll) {
            classBuilder.addMethod(resolveRefHelper(entity));
        }

        writeSourceFile(packageName + "." + implementationName, repository,
                JavaFile.builder(packageName, classBuilder.build()).skipJavaLangImports(true).build());
        if (!queryModels.isEmpty()) {
            generateRepositoryModel(repository, packageName,
                    repository.getSimpleName() + "Model", entity.typeName(), queryModels);
        }
    }

    /**
     * Emits the generated query-metadata holder: {@code public final class <Repository>Model} with one
     * nested {@code public static final class <Method>QueryModel implements QueryModel<Entity>} per
     * query-by-names method. Every accessor returns a literal built over the {@code query} value
     * records, so the published structure is exactly what rendered the SQL constant in
     * {@code <Repository>Impl} — introspectable without reflection, and re-emitted per build so it
     * can never drift. The holder is documentation and metadata: nothing at runtime reads it.
     */
    private void generateRepositoryModel(TypeElement repository, String packageName, String modelSimpleName,
                                         TypeName entityType, List<ResolvedQueryModel> queryModels) {
        TypeSpec.Builder holder = TypeSpec.classBuilder(modelSimpleName)
                .addJavadoc("Generated query metadata for {@code $L}: one nested {@code QueryModel} per"
                        + " query-by-names method. The rendered SQL literals live in {@code $LImpl};"
                        + " this holder publishes the resolved structure they were rendered from."
                        + " Generated — do not edit.\n",
                        modelSimpleName, modelSimpleName.substring(0, modelSimpleName.length() - "Model".length()))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        holder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (ResolvedQueryModel model : queryModels) {
            holder.addType(queryModelType(model, entityType));
        }
        writeSourceFile(packageName + "." + modelSimpleName, repository,
                JavaFile.builder(packageName, holder.build()).skipJavaLangImports(true).build());
    }

    /** One nested {@code <Method>QueryModel} class serialising {@code model}'s nine accessors. */
    private TypeSpec queryModelType(ResolvedQueryModel model, TypeName entityType) {
        ClassName self = ClassName.bestGuess(model.modelName());
        TypeSpec.Builder query = TypeSpec.classBuilder(model.modelName())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(Q_QUERY_MODEL, entityType));
        query.addField(FieldSpec.builder(self, "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", self).build());
        query.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        query.addMethod(scalarAccessor("operation", Q_OPERATION,
                CodeBlock.of("$T.$L", Q_OPERATION, model.operation().name())));
        query.addMethod(scalarAccessor("resultKind", Q_RESULT_KIND,
                CodeBlock.of("$T.$L", Q_RESULT_KIND, model.resultKind().name())));
        CodeBlock.Builder preds = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < model.predicates().size(); i++) {
            if (i > 0) {
                preds.add(", ");
            }
            preds.add(resolvedPredicateLiteral(model.predicates().get(i)));
        }
        preds.add(")");
        query.addMethod(scalarAccessor("predicates",
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), Q_RESOLVED_PREDICATE),
                preds.build()));
        CodeBlock.Builder sortLiterals = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < model.sorts().size(); i++) {
            if (i > 0) {
                sortLiterals.add(", ");
            }
            sortLiterals.add(resolvedSortLiteral(model.sorts().get(i)));
        }
        sortLiterals.add(")");
        query.addMethod(scalarAccessor("sorts",
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), Q_RESOLVED_SORT),
                sortLiterals.build()));
        query.addMethod(scalarAccessor("paging", Q_PAGING_MODE,
                CodeBlock.of("$T.$L", Q_PAGING_MODE, model.paging().name())));
        query.addMethod(MethodSpec.methodBuilder("limit").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(int.class)
                .addStatement("return $L", model.limit()).build());
        CodeBlock.Builder seeks = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < model.seekTerms().size(); i++) {
            if (i > 0) {
                seeks.add(", ");
            }
            SeekTerm term = model.seekTerms().get(i);
            seeks.add("new $T($L, $L)", Q_SEEK_TERM, columnRefLiteral(term.ref()), term.ascending());
        }
        seeks.add(")");
        query.addMethod(scalarAccessor("seekTerms",
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), Q_SEEK_TERM), seeks.build()));
        CodeBlock.Builder params = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < model.paramBindings().size(); i++) {
            if (i > 0) {
                params.add(", ");
            }
            ParamBinding binding = model.paramBindings().get(i);
            params.add("new $T($L, $S)", Q_PARAM_BINDING, binding.argIndex(), binding.paramName());
        }
        params.add(")");
        query.addMethod(scalarAccessor("paramBindings",
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), Q_PARAM_BINDING),
                params.build()));
        return query.build();
    }

    /** {@code @Override public X name() { return <literal>; }}. */
    private static MethodSpec scalarAccessor(String name, TypeName returnType, CodeBlock value) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(returnType)
                .addStatement("return $L", value).build();
    }

    /**
     * The 4-arg {@code ColumnRef} literal — the same shape {@code generateMetadata} emits for
     * {@code navigationRefs()}, so a navigated ref serialises identically in both surfaces.
     */
    private static CodeBlock columnRefLiteral(ColumnRef ref) {
        CodeBlock.Builder joins = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < ref.joins().size(); i++) {
            if (i > 0) {
                joins.add(", ");
            }
            JoinRef join = ref.joins().get(i);
            joins.add("new $T($S, $S, $S, $S)", MD_JOIN_REF, join.table(), join.alias(),
                    join.parentColumn(), join.childColumn());
        }
        joins.add(")");
        return CodeBlock.of("new $T($S, $S, $L, $L)", MD_COLUMN_REF, ref.column(), ref.path(),
                ref.table() == null ? CodeBlock.of("null") : CodeBlock.of("$S", ref.table()), joins.build());
    }

    private static CodeBlock resolvedPredicateLiteral(ResolvedPredicate resolved) {
        Predicate p = resolved.predicate();
        return CodeBlock.of("new $T($T.of($S, $T.$L, $S), $L, $L)", Q_RESOLVED_PREDICATE, Q_PREDICATE,
                p.property(), Q_OPERATOR, p.operator().name(), p.param(),
                columnRefLiteral(resolved.ref()), resolved.existential());
    }

    private static CodeBlock resolvedSortLiteral(ResolvedSort resolved) {
        SortSpec s = resolved.sort();
        return CodeBlock.of("new $T(new $T($S, $L, $L), $L, $L)", Q_RESOLVED_SORT, Q_SORT_SPEC,
                s.property(), s.ascending(), s.ignoreCase(),
                columnRefLiteral(resolved.ref()), resolved.existential());
    }

    // ------------------------------------------- static metamodel + row mapper generation

    private static String constName(String property) {
        return Names.toSnakeCase(property).toUpperCase();
    }

    private static FieldSpec stringConstant(String name, String value) {
        return FieldSpec.builder(String.class, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", value).build();
    }

    private static boolean isJdbcSafe(TypeName type) {
        if (type instanceof ArrayTypeName array) {
            return array.componentType.equals(TypeName.BYTE); // byte[]
        }
        return JDBC_SAFE_TYPES.contains(type.toString());
    }

    /**
     * Gate for the reflection-free row mapper: safe when every property is JDBC-safe, an
     * {@code ASSOCIATION}/{@code MANY_TO_ONE} fk (skipped on read), or an {@code EMBEDDED} value
     * object whose own model passes the same check. A VO's model has no id and its leaves are its
     * properties, so the recursion flattens naturally; a {@code null} embedded model (cycle guard)
     * fails closed.
     */
    private boolean canGenerateRowMapper(EntityModel entity) {
        if (entity.id() != null && !isJdbcSafe(entity.id().type())) {
            return false;
        }
        for (Property p : entity.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // FK-only or hydrated by loaders (Tasks 3-4)
            }
            if (isJdbcSafe(p.type())) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded && p.embeddedModel() != null
                    && canGenerateRowMapper(p.embeddedModel())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Emits {@code <Entity>Metadata implements EntityDescriptor<Entity>}: Plan A's column-name
     * constants (one per mapped property plus the table name) alongside the descriptor view —
     * {@code INSTANCE}, {@code entityClass()}, {@code tableName()}, {@code id()} and
     * {@code attributes()}. A value object (EMBEDDED target) has no table and no id, so its
     * TABLE_NAME constant is omitted and the matching accessors return {@code null}. An EMBEDDED
     * property contributes no constant of its own — its {@code column} slot holds a leaf prefix,
     * so no physical column named after the property exists — but each flattened leaf of its
     * value-object descriptor gets a constant {@code <PREFIX>_<LEAF>} named
     * {@code <prefix>_<leaf>} (Decision 3; nesting is resolved inside the descriptor, so one loop
     * per embedded attribute suffices). The accessor bodies return the {@code _Entity} metamodel
     * field instances when a metamodel was generated (Decision 1); a value object has none, so its
     * members are emitted as fresh attribute-record literals with the same shapes. Embedded leaves
     * are NOT flat {@code attributes()} members: the navigable metamodel classes carry them, and the
     * explicit {@code columns()} override (emitted from {@link DataModel#columns()} in
     * declaration order) answers flattened dot paths for the engine.
     */
    private ClassName generateMetadata(TypeElement entityElement, EntityModel entity,
                                       List<MmMember> members, ClassName metamodelType) {
        String packageName = elements.getPackageOf(entityElement).getQualifiedName().toString();
        String simpleName = entityElement.getSimpleName() + "Metadata";
        ClassName self = ClassName.get(packageName, simpleName);
        TypeName entityType = entity.typeName();
        ClassName descriptor = ClassName.get("io.github.fludakit.data.metadata", "EntityDescriptor");

        TypeSpec.Builder builder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(descriptor, entityType))
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(self, "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", self).build());
        if (entity.metadata().tableName() != null) {
            builder.addField(stringConstant("TABLE_NAME", entity.metadata().tableName()));
        }
        if (entity.id() != null) {
            builder.addField(stringConstant(constName(entity.id().name()), entity.id().column()));
        }
        Set<String> embeddedNames = new HashSet<>();
        for (Prop a : entity.metadata().props()) {
            if (a instanceof Prop.Embedded) {
                embeddedNames.add(a.name());
            }
        }
        for (Property p : entity.properties()) {
            if (!embeddedNames.contains(p.name())) {
                builder.addField(stringConstant(constName(p.name()), p.column()));
            }
        }
        // flattened embedded leaves: one constant per physical leaf column (Decision 3). The
        // EMBEDDED property's own name stays skipped above — its column slot is only a leaf prefix —
        // while each leaf of the (already-flattened) value-object descriptor surfaces as
        // <prefix>_<leaf>, e.g. an embedded "point" yields POINT_LAT = "point_lat". The constant
        // NAME derives from the leaf's COLUMN (not the attribute name) so name and value agree
        // whenever the attribute name is not the snake case of its column.
        for (Prop a : entity.metadata().props()) {
            if (!(a instanceof Prop.Embedded embedded) || embedded.target() == null) {
                continue;
            }
            for (ColumnRef leaf : embedded.target().columns()) {
                builder.addField(stringConstant(
                        Names.toSnakeCase(embedded.column() + "_" + leaf.column()).toUpperCase(),
                        embedded.column() + "_" + leaf.column()));
            }
        }

        builder.addMethod(MethodSpec.methodBuilder("entityClass").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), entityType))
                .addStatement("return $T.class", entityType).build());
        builder.addMethod(stringAccessor("tableName", entity.metadata().tableName()));

        MmMember idMember = null;
        for (MmMember m : members) {
            if (m.prop() instanceof Prop.Id) {
                idMember = m;
                break;
            }
        }
        MethodSpec.Builder id = MethodSpec.methodBuilder("id").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(ParameterizedTypeName.get(FM_ID, entityType));
        if (idMember == null) {
            id.addStatement("return null");
        } else if (metamodelType != null) {
            id.addStatement("return $T.$L", metamodelType, idMember.identifier());
        } else {
            id.addStatement("return $L", memberInitializer(entityType, idMember, false));
        }
        builder.addMethod(id.build());

        TypeName attribute = ParameterizedTypeName.get(MM_ATTRIBUTE, entityType);
        CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        boolean first = true;
        for (MmMember m : members) {
            if (m.prop() instanceof Prop.Id) {
                continue; // exposed through id(), not duplicated in attributes()
            }
            if (!first) {
                list.add(", ");
            }
            first = false;
            list.add(metamodelType != null
                    ? CodeBlock.of("$T.$L", metamodelType, m.identifier())
                    : memberInitializer(entityType, m, false));
        }
        list.add(")");
        builder.addMethod(MethodSpec.methodBuilder("attributes").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(ClassName.get(java.util.List.class), attribute))
                .addStatement("return $L", list.build()).build());

        // Task 6: the flattened column view is generated EXPLICITLY from the compile-time model in
        // declaration order (the derived EntityDescriptor.columns() default is gone), in BOTH the
        // reference and the literal (value-object) mode. Two-arg ColumnRef literals: the root table
        // is implicit, so no per-entry table/join overrides are emitted.
        CodeBlock.Builder columns = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        boolean firstColumn = true;
        for (ColumnRef column : entity.metadata().columns()) {
            if (!firstColumn) {
                columns.add(", ");
            }
            firstColumn = false;
            columns.add("new $T($S, $S)", MD_COLUMN_REF, column.column(), column.path());
        }
        columns.add(")");
        builder.addMethod(MethodSpec.methodBuilder("columns").addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ParameterizedTypeName.get(ClassName.get(java.util.List.class), MD_COLUMN_REF))
                .addStatement("return $L", columns.build()).build());

        // D3/D4: the navigated leaf cases — the EXACT path set both the value() switch below and
        // the navigationRefs() map are generated from (shared compile-time enumeration; the
        // mirror-drift risk is closed at generation time: navigationRefs() path set ==
        // value()-switch relation-path set by construction). Map.ofEntries (not Map.of) so any
        // cardinality compiles.
        List<NavCase> navs = navCases(entityElement, entity);
        if (!navs.isEmpty()) {
            CodeBlock.Builder entries = CodeBlock.builder();
            boolean firstNav = true;
            for (NavCase nav : navs) {
                if (!firstNav) {
                    entries.add(", ");
                }
                firstNav = false;
                ColumnRef ref = nav.ref();
                CodeBlock.Builder joins = CodeBlock.builder()
                        .add("$T.of(", ClassName.get(java.util.List.class));
                boolean firstJoin = true;
                for (JoinRef join : ref.joins()) {
                    if (!firstJoin) {
                        joins.add(", ");
                    }
                    firstJoin = false;
                    joins.add("new $T($S, $S, $S, $S)", MD_JOIN_REF,
                            join.table(), join.alias(), join.parentColumn(), join.childColumn());
                }
                joins.add(")");
                entries.add("$T.entry($S, new $T($S, $S, $L, $L))", ClassName.get(java.util.Map.class),
                        nav.path(), MD_COLUMN_REF, ref.column(), ref.path(),
                        ref.table() == null ? CodeBlock.of("null") : CodeBlock.of("$S", ref.table()),
                        joins.build());
            }
            builder.addMethod(MethodSpec.methodBuilder("navigationRefs").addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class)
                    .returns(ParameterizedTypeName.get(ClassName.get(java.util.Map.class),
                            ClassName.get(String.class), MD_COLUMN_REF))
                    .addStatement("return $T.ofEntries($L)", ClassName.get(java.util.Map.class),
                            entries.build()).build());
        }

        // Task 4 (S-3): the read-side twin of the column view. columns()/columnRef() answer WHERE a dot
        // path lives; value() answers what it IS on a materialised row, so a paged read can build its own
        // cursor key without reflection. Generation is NOTE-degraded: a descriptor that cannot carry the
        // switch simply keeps the interface default (which fails fast) instead of failing the round.
        MethodSpec valueMethod = valueExtractor(entityElement, entity, navs);
        if (valueMethod != null) {
            builder.addMethod(valueMethod);
        }

        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build());
        return self;
    }

    /**
     * Emits the descriptor's reflection-free {@code value(String dotPath, Object entity)} override: a
     * {@code switch} over exactly the dot paths {@code columnRef} resolves at the depth
     * {@link #resolveCompilePath} walks — the flattened {@code columns()} view (identifier, scalars,
     * to-one heads, embedded leaves), the one-hop leaves of every {@link Prop.Reference} target, and the
     * mapped child values of every {@link Prop.EntityList}/{@link Prop.ElementList}. Bodies are JavaPoet
     * getter chains, method references and stream lambdas only (never reflection), each hop null-guarded
     * and each to-many container guarded to an EMPTY list. A path whose accessor cannot be resolved is
     * left out with a NOTE and the round continues; an unknown path fails fast with
     * {@link IllegalArgumentException}, exactly as {@code columnRef} does.
     */
    private MethodSpec valueExtractor(TypeElement entityElement, EntityModel entity,
                                        List<NavCase> navs) {
        try {
            return valueSwitch(entityElement, entity, navs);
        } catch (RuntimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "descriptor value extraction could not be generated for " + entityElement.getSimpleName()
                            + " (" + e + "); the value switch is omitted", entityElement);
            return null;
        }
    }

    /** One navigated leaf: the full dot path, its resolved ColumnRef and what valueSwitch needs. */
    private record NavCase(String path, ColumnRef ref, TypeElement targetElement, DataModel target,
                           CodeBlock head, boolean toOne) { }

    /** The relation-traversing leaf cases of one entity — the EXACT path set both the value()
     *  switch and the navigationRefs() map are generated from (the loop moved verbatim out of
     *  valueSwitch; head-level guards keep the historical NOTE-degrade semantics: an unmapped
     *  target or accessor-less head drops the head from BOTH surfaces). */
    private List<NavCase> navCases(TypeElement entityElement, EntityModel entity) {
        DataModel md = entity.metadata();
        List<NavCase> out = new ArrayList<>();
        for (Prop p : md.props()) {
            if (!isTraversable(p)) {
                continue;
            }
            String targetFqn = memberTargetFqn(p);
            DataModel target = descriptorByFqn(targetFqn);
            TypeElement targetElement = target == null ? null
                    : elements.getTypeElement(stripGenerics(target.typeName()));
            String headGetter = getter(p.name());
            if (targetElement == null || !hasMethodNamed(entityElement, headGetter)) {
                continue; // an unmapped target or a bean-less head is unnavigable at runtime as well
            }
            CodeBlock head = CodeBlock.of("e.$L()", headGetter);
            boolean toOne = p instanceof Prop.Reference;
            List<ColumnRef> leaves;
            JoinRef join;
            if (p instanceof Prop.ElementList elementList) {
                leaves = elementList.elementColumns();
                join = new JoinRef(elementList.childTable(), elementList.name(),
                        md.idColumn(), elementList.parentFkColumn());
            } else {
                leaves = target.columns();
                join = referenceOrListJoin(md, target, p);
                if (join == null) {
                    continue; // unbuildable join: the compile paths unresolvedRelation drop
                }
            }
            for (ColumnRef leaf : leaves) {
                out.add(new NavCase(p.name() + "." + leaf.path(),
                        leaf.withPrefix(p.name(), join), targetElement, target, head, toOne));
            }
        }
        return out;
    }

    private MethodSpec valueSwitch(TypeElement entityElement, EntityModel entity,
                                 List<NavCase> navs) {
        DataModel md = entity.metadata();
        Map<String, CodeBlock> cases = new LinkedHashMap<>();

        // (1) The flattened column view this descriptor publishes: identifier, scalars, to-one heads
        // (whose path is the property name, mapping to the fk column) and embedded leaves.
        for (ColumnRef column : md.columns()) {
            putValueCase(cases, entityElement, column.path(),
                    valueChain(entityElement, md, CodeBlock.of("e"), column.path()));
        }

        // (2) Depth 1 through every relationship the descriptor publishes — the SHARED
        // navCases() enumeration (the navigationRefs() map consumes the identical list, so the
        // path sets agree by construction). Anything deeper is multi-hop for resolveCompilePath
        // too, so it stays out of both surfaces by construction.
        for (NavCase nav : navs) {
            String leafPath = nav.path().substring(nav.path().indexOf(".") + 1);
            CodeBlock body = nav.toOne()
                    ? guardedValueChain(nav.targetElement(), nav.target(), nav.head(), leafPath)
                    : toManyValue(nav.targetElement(), nav.target(), nav.head(), leafPath);
            putValueCase(cases, entityElement, nav.path(), body);
        }
        if (cases.isEmpty()) {
            return null;
        }

        TypeName entityType = entity.typeName();
        MethodSpec.Builder value = MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(Object.class)
                .addParameter(String.class, "dotPath")
                .addParameter(Object.class, "entity")
                .addStatement("$T e = ($T) entity", entityType, entityType);
        value.beginControlFlow("switch (dotPath)");
        for (Map.Entry<String, CodeBlock> entry : cases.entrySet()) {
            value.addStatement("case $S: return $L", entry.getKey(), entry.getValue());
        }
        value.addStatement("default: throw new $T($S + dotPath + $S)", IllegalArgumentException.class,
                "Unknown property path '", "' on " + entityElement.getSimpleName());
        value.endControlFlow();
        return value.build();
    }

    /** Records one generated case, NOTE-degrading the path when its expression could not be built. */
    private void putValueCase(Map<String, CodeBlock> cases, TypeElement origin, String path, CodeBlock value) {
        if (value == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "descriptor value extraction is not available for path '" + path
                            + "': its accessor could not be resolved; the path is left out of the"
                            + " value switch", origin);
            return;
        }
        cases.putIfAbsent(path, value);
    }

    /**
     * The null-safe getter chain for one dot path walked from {@code recv} through {@code md} (declared
     * on {@code element}), or {@code null} when a hop is unknown, is not an embedded value object, or
     * has no accessor. Only {@link Prop.Embedded} may carry a further segment.
     */
    private CodeBlock valueChain(TypeElement element, DataModel md, CodeBlock recv, String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        Prop p = propOrNull(md, head);
        if (p == null || element == null || !hasMethodNamed(element, getter(head))) {
            return null;
        }
        CodeBlock chain = CodeBlock.of("$L.$L()", recv, getter(head));
        if (dot < 0) {
            return chain;
        }
        if (!(p instanceof Prop.Embedded embedded) || embedded.target() == null) {
            return null;
        }
        TypeElement voElement = elements.getTypeElement(stripGenerics(embedded.target().typeName()));
        CodeBlock body = valueChain(voElement, embedded.target(), chain, path.substring(dot + 1));
        return body == null ? null : CodeBlock.of("$L == null ? null : $L", chain, body);
    }

    /**
     * A to-one leaf: the target's own chain, guarded on the reference itself —
     * {@code e.getCustomer() == null ? null : e.getCustomer().getName()}.
     */
    private CodeBlock guardedValueChain(TypeElement targetElement, DataModel target,
                                        CodeBlock head, String leafPath) {
        CodeBlock body = valueChain(targetElement, target, head, leafPath);
        return body == null ? null : CodeBlock.of("$L == null ? null : $L", head, body);
    }

    /**
     * A to-many leaf: the child values of the whole collection, mapped by a method reference (single
     * segment) or a null-safe chain lambda (embedded leaf) and collected to a list. A null container
     * reads as an empty list, never null, so the cursor reducer always gets a list.
     */
    private CodeBlock toManyValue(TypeElement childElement, DataModel childModel,
                                  CodeBlock collection, String leafPath) {
        CodeBlock mapper;
        if (leafPath.indexOf('.') < 0) {
            if (!hasMethodNamed(childElement, getter(leafPath))) {
                return null;
            }
            mapper = CodeBlock.of("$T::$L", ClassName.get(childElement), getter(leafPath));
        } else {
            CodeBlock body = valueChain(childElement, childModel, CodeBlock.of("child"), leafPath);
            mapper = body == null ? null : CodeBlock.of("child -> $L", body);
        }
        if (mapper == null) {
            return null;
        }
        return CodeBlock.of("$L == null ? $T.of() : $L.stream().map($L).collect($T.toList())",
                collection, ClassName.get(java.util.List.class), collection, mapper,
                ClassName.get("java.util.stream", "Collectors"));
    }

    /**
     * Emits descriptor metadata for every value object transitively {@code EMBEDDED} in
     * {@code owner}'s attributes. Children are generated before their parent so the parent's
     * {@code <VO>Metadata.INSTANCE} reference resolves to a generated class; the
     * {@link #metadataTypes} guard is set before recursing, which bounds cycles (A embeds B embeds A).
     */
    private void generateEmbeddedDescriptors(DataModel owner) {
        for (Prop a : owner.props()) {
            if (!(a instanceof Prop.Embedded embedded) || embedded.target() == null) {
                continue;
            }
            String voFqn = embedded.target().typeName();
            if (voFqn == null || metadataTypes.containsKey(voFqn)) {
                continue;
            }
            TypeElement voElement = elements.getTypeElement(voFqn);
            if (voElement == null) {
                continue;
            }
            EntityModel voModel = buildEntity(voElement);
            String voPackage = elements.getPackageOf(voElement).getQualifiedName().toString();
            metadataTypes.put(voFqn, ClassName.get(voPackage, voElement.getSimpleName() + "Metadata"));
            generateEmbeddedDescriptors(voModel.metadata());
            // The metadataTypes guard above is what bounds VO cycles; emit directly (a VO has no
            // metamodel, so its members become record literals — the `metamodelType == null` mode).
            generateMetadata(voElement, voModel, collectMembers(voElement, voModel.metadata()), null);
        }
    }

    /**
     * Emits the aggregate's {@code _Entity} static metamodel (idempotent via {@link #metamodelTypes})
     * and, when {@code withMetadata} asks for it, its {@code <Entity>Metadata} descriptor (idempotent
     * via {@link #metadataTypes}). One member list drives both, so the descriptor's
     * {@code id()}/{@code attributes()} return the very metamodel field instances (Decision 1);
     * value objects have no metamodel and their metadata falls back to record literals.
     */
    private void ensureDescriptorArtifacts(TypeElement entityElement, EntityModel entity,
                                           boolean withMetadata) {
        String fqn = entityElement.getQualifiedName().toString();
        boolean needMetamodel = !isValueObjectType(entityElement) && !metamodelTypes.containsKey(fqn);
        boolean needMetadata = withMetadata && !metadataTypes.containsKey(fqn);
        if (!needMetamodel && !needMetadata) {
            return;
        }
        List<MmMember> members = collectMembers(entityElement, entity.metadata());
        if (needMetamodel) {
            String entityPackage = elements.getPackageOf(entityElement).getQualifiedName().toString();
            metamodelTypes.put(fqn, ClassName.get(entityPackage, "_" + entityElement.getSimpleName()));
            generateStaticMetamodel(entityElement, members);
        }
        if (needMetadata) {
            metadataTypes.put(fqn,
                    generateMetadata(entityElement, entity, members, metamodelTypes.get(fqn)));
        }
    }

    /**
     * Emits the Jakarta Data 1.0 §3.3 static metamodel: {@code _Entity}, a generated interface annotated
     * {@code @Generated} + {@code @StaticMetamodel(Entity.class)} whose {@code UPPER_CASE} String
     * constants hold entity ATTRIBUTE names (root members only, Task 6) and whose mixed-case fields are
     * FluDa attribute records from {@code io.github.fludakit.data.metamodel}, each also carrying the
     * physical COLUMN — so one field instance is both the user-facing sort anchor and the runtime
     * descriptor attribute (Decision 1). Embedded and relation members are NAVIGABLE: each gets a
     * generated top-level member class ({@code _<Entity><Field>}) implementing the corresponding
     * attribute interface, instantiated as the field value; its leaf fields carry the owner-rooted
     * dot-path names. {@code <Entity>Metadata} returns these very instances. The member list comes
     * from {@link #collectMembers}, shared with the metadata emitter. A nav-class name collision —
     * against ANY type in the session-global {@link #generatedTypeNames} registry, including another
     * entity's {@code _Entity} metamodel — degrades to a NOTE and the plain attribute record (spec
     * collision handling), never to a Filer error.
     */
    private void generateStaticMetamodel(TypeElement entityElement, List<MmMember> members) {
        String packageName = elements.getPackageOf(entityElement).getQualifiedName().toString();
        ClassName owner = ClassName.get(packageName, entityElement.getSimpleName().toString());
        String simpleName = "_" + entityElement.getSimpleName();

        TypeSpec.Builder builder = TypeSpec.interfaceBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build())
                .addAnnotation(AnnotationSpec.builder(MM_STATIC_METAMODEL)
                        .addMember("value", "$T.class", owner).build());
        for (MmMember m : members) {
            // JavaPoet requires explicit visibility on interface fields (static/final are implicit).
            builder.addField(FieldSpec.builder(String.class, m.constant(),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", m.path()).build());
        }
        // One navigable member class per embedded/relation member, collected here and written to the
        // Filer after the interface itself (same round, distinct top-level types). The collision guard
        // is the SESSION-GLOBAL generatedTypeNames registry (every _Entity metamodel, nav, metadata,
        // row mapper and repository impl FQN): a nav whose name collides with ANY generated type —
        // including another entity's metamodel (Order.item → pkg._OrderItem vs the OrderItem
        // root's own _OrderItem) — degrades to the NOTE + plain attribute record instead of colliding
        // at the Filer. This entity's own _Entity FQN is already pre-claimed in process()'s round
        // pre-scan, so metamodels win the name race independently of repository iteration order.
        List<NavUnit> units = new ArrayList<>();
        for (MmMember m : members) {
            Prop p = m.prop();
            boolean navigable = p instanceof Prop.Embedded || p instanceof Prop.Reference
                    || p instanceof Prop.EntityList || p instanceof Prop.ElementList;
            ClassName navType = navigable
                    ? generateNav(entityElement, packageName, owner, m, generatedTypeNames, units)
                    : null;
            if (navType != null) {
                builder.addField(FieldSpec.builder(navType, m.identifier(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", navType).build());
            } else {
                builder.addField(FieldSpec.builder(memberAttrType(owner, m), m.identifier(),
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer(memberInitializer(owner, m, true)).build());
            }
        }

        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build());
        for (NavUnit unit : units) {
            writeSourceFile(unit.fqn(), entityElement, unit.file());
        }
    }

    /** One generated navigable member class: its Filer name plus the file to write. */
    private record NavUnit(String fqn, JavaFile file) { }

    /**
     * Dispatch: the navigable member class for one embedded/relation root member, named
     * {@code _<EntitySimpleName><PascalCase field>} per the spec. Returns the generated type name or
     * {@code null} when the name collides (NOTE; the caller falls back to the plain record).
     */
    private ClassName generateNav(TypeElement origin, String packageName, ClassName owner,
                                  MmMember m, Set<String> claimed, List<NavUnit> units) {
        String navSimple = "_" + owner.simpleName() + capitalize(m.prop().name());
        Prop p = m.prop();
        if (p instanceof Prop.Embedded embedded) {
            return embeddedNav(origin, packageName, owner, navSimple, m.path(),
                    m.column() + "_", embedded, claimed, units);
        }
        if (p instanceof Prop.Reference || p instanceof Prop.EntityList) {
            return relationNav(origin, packageName, owner, navSimple, m, claimed, units);
        }
        return elementCollectionNav(origin, packageName, owner, navSimple, m.path(),
                (Prop.ElementList) p, claimed, units);
    }

    /**
     * The navigable class for an EMBEDDED member: implements
     * {@code EmbeddableAttribute<owner, target>} with owner-typed leaf fields for the value object's
     * direct members (nested embeddeds recurse into their own {@code _Parent<Field>} nav class), the
     * flattened column chain as leaf columns ({@code address_point_lat}) and the full dot path as
     * leaf/attribute names ({@code address.point.lat}). A VO cycle (unresolved target) degrades to a
     * container-only nav: no leaves. {@code columnPrefix} is
     * the flattened physical column chain from the owner root ({@code address_}, then
     * {@code address_point_}), threading exactly as {@link DataModel#columns()} flattens.
     */
    private ClassName embeddedNav(TypeElement origin, String packageName, TypeName owner,
                                  String navSimple, String path, String columnPrefix,
                                  Prop.Embedded embedded, Set<String> claimed, List<NavUnit> units) {
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            navCollision(origin, fqn, path);
            return null;
        }
        EmbeddedValueObjectModel target = embedded.target();
        TypeName targetType = classArgOf(target == null ? null : target.typeName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(FM_EMBEDDED, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        if (target != null) {
            if (target.id() != null) {
                addNavLeaf(builder, taken, origin, packageName, owner, path, navSimple,
                        columnPrefix, target.id(), claimed, units);
            }
            for (Prop p : target.props()) {
                addNavLeaf(builder, taken, origin, packageName, owner, path, navSimple,
                        columnPrefix, p, claimed, units);
            }
        }
        builder.addMethod(overrideAccessor("name", String.class, CodeBlock.of("$S", path)));
        units.add(new NavUnit(fqn, navFile(packageName, builder)));
        return ClassName.get(packageName, navSimple);
    }

    /**
     * The navigable class for a MANY_TO_ONE ({@code EntityAttribute}) or ONE_TO_MANY
     * ({@code ListAttribute}) member: the attribute name plus owner-typed leaf fields
     * for the managed target's flattened LOCAL paths ({@code primary.id} maps to column {@code id}).
     * An unmanaged or gate-failed target keeps the name and no leaves — the same degradation the
     * attribute records already follow.
     */
    private ClassName relationNav(TypeElement origin, String packageName, TypeName owner,
                                  String navSimple, MmMember m, Set<String> claimed, List<NavUnit> units) {
        Prop p = m.prop();
        boolean fk = p instanceof Prop.Reference;
        String targetFqn = memberTargetFqn(p);
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            navCollision(origin, fqn, m.path());
            return null;
        }
        TypeName targetType = classArgOf(targetFqn);
        TypeSpec.Builder builder = TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(fk ? FM_FK : FM_OTM, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        if (relationTargetResolved(targetFqn, false)) {
            TypeElement targetElement = elements.getTypeElement(stripGenerics(targetFqn));
            DataModel target = entityModel(targetElement).metadata();
            if (target.id() != null) {
                addRelationLeaf(builder, taken, origin, owner, m.path(), "", target.id());
            }
            for (Prop tp : target.props()) {
                addRelationLeaf(builder, taken, origin, owner, m.path(), "", tp);
            }
        }
        builder.addMethod(overrideAccessor("name", String.class, CodeBlock.of("$S", m.path())));
        units.add(new NavUnit(fqn, navFile(packageName, builder)));
        return ClassName.get(packageName, navSimple);
    }

    /**
     * The navigable class for an ELEMENT_COLLECTION member: implements
     * {@code ListAttribute<owner, element>} with owner-typed leaf fields for the element
     * value object's columns (mirroring {@link Prop.ElementList#elementColumns()}). The child-table
     * placement lives in the descriptor's {@code navigationRefs()} literal, not on the attribute.
     */
    private ClassName elementCollectionNav(TypeElement origin, String packageName, TypeName owner,
                                           String navSimple, String path, Prop.ElementList elementList,
                                           Set<String> claimed, List<NavUnit> units) {
        String fqn = packageName + "." + navSimple;
        if (!claimed.add(fqn)) {
            navCollision(origin, fqn, path);
            return null;
        }
        String targetFqn = elementList.elementTypeName();
        TypeName targetType = classArgOf(targetFqn);
        TypeSpec.Builder builder = TypeSpec.classBuilder(navSimple)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(FM_EC, owner, targetType))
                .addAnnotation(AnnotationSpec.builder(MM_GENERATED)
                        .addMember("value", "$S", RepositoryProcessor.class.getName()).build());
        Set<String> taken = new HashSet<>();
        if (relationTargetResolved(targetFqn, true)) {
            TypeElement elementElement = elements.getTypeElement(stripGenerics(targetFqn));
            if (elementElement != null) {
                for (Prop ep : elementList.target().props()) {
                    addRelationLeaf(builder, taken, origin, owner, path, "", ep);
                }
            }
        }
        builder.addMethod(overrideAccessor("name", String.class, CodeBlock.of("$S", path)));
        units.add(new NavUnit(fqn, navFile(packageName, builder)));
        return ClassName.get(packageName, navSimple);
    }

    /**
     * One nav field inside an embedded navigable class. A nested {@link Prop.Embedded} recurses into
     * its own nav class (name {@code <parentNav><PascalCase field>}, threaded through the same
     * {@code claimed}/{@code units} context); entity/element collections inside a value object carry
     * no column and are skipped.
     */
    private void addNavLeaf(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                            String packageName, TypeName owner, String parentPath, String parentNav,
                            String columnPrefix, Prop p, Set<String> claimed, List<NavUnit> units) {
        String path = parentPath + "." + p.name();
        if (p instanceof Prop.Embedded embedded) {
            String childNav = parentNav + capitalize(p.name());
            ClassName nav = embeddedNav(origin, packageName, owner, childNav, path,
                    columnPrefix + embedded.column() + "_", embedded, claimed, units);
            if (nav != null) {
                addNavField(builder, taken, origin, owner, childNav, p.name(), nav,
                        CodeBlock.of("new $T()", nav));
            }
            return;
        }
        String column = memberColumn(p);
        if (column == null) {
            return; // EntityList / ElementList inside a value object: no column on this table.
        }
        addLeafField(builder, taken, origin, owner, parentNav, p.name(), path, columnPrefix + column, p);
    }

    /**
     * One owner-typed leaf for a relation nav: name = {@code <relation>.<local path>}, column = the
     * target's LOCAL flattened column (threaded through {@code columnPrefix} exactly as
     * {@link DataModel#columns()} flattens). Embedded targets expand recursively; collections
     * inside the target carry no column and are skipped.
     */
    private void addRelationLeaf(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                                 TypeName owner, String parentPath, String columnPrefix, Prop p) {
        String path = parentPath + "." + p.name();
        // Java 17 safety: instanceof chain, never a pattern switch.
        if (p instanceof Prop.Embedded embedded) {
            String prefix = columnPrefix + embedded.column() + "_";
            EmbeddedValueObjectModel vo = embedded.target();
            if (vo != null && vo.id() != null) {
                addRelationLeaf(builder, taken, origin, owner, path, prefix, vo.id());
            }
            if (vo != null && !vo.props().isEmpty()) {
                for (Prop vp : vo.props()) {
                    addRelationLeaf(builder, taken, origin, owner, path, prefix, vp);
                }
            } else {
                // unresolved (or empty) cycle target: one plain leaf at the container column
                addLeafField(builder, taken, origin, owner, parentPath, embedded.name(), path,
                        columnPrefix + embedded.column(),
                        new Prop.Scalar(embedded.name(), embedded.column(), null));
            }
            return;
        }
        String column = memberColumn(p);
        if (column == null) {
            return;
        }
        addLeafField(builder, taken, origin, owner, parentPath, p.name(), path,
                columnPrefix + column, p);
    }

    /** The typed leaf attribute field: kind from the property type, diamond record initializer. */
    private void addLeafField(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                              TypeName owner, String navSimple, String identifier,
                              String path, String column, Prop p) {
        ClassName attrType;
        ClassName recordType;
        // Java 17 safety: instanceof chain, never a pattern switch.
        if (p instanceof Prop.Id) {
            attrType = FM_ID;
            recordType = FM_ID_RECORD;
        } else if (p instanceof Prop.Scalar scalar) {
            switch (metamodelKind(scalar.typeFqn())) {
                case TEXT -> {
                    attrType = FM_TEXT;
                    recordType = FM_TEXT_RECORD;
                }
                case SORTABLE -> {
                    attrType = FM_SORTABLE;
                    recordType = FM_SORTABLE_RECORD;
                }
                default -> {
                    attrType = FM_COLUMN;
                    recordType = FM_COLUMN_RECORD;
                }
            }
        } else {
            attrType = FM_COLUMN; // relation/embedded foreign keys surface as plain column leaves
            recordType = FM_COLUMN_RECORD;
        }
        addNavField(builder, taken, origin, owner, navSimple, identifier,
                ParameterizedTypeName.get(attrType, owner),
                CodeBlock.of("new $T<>($S)", recordType, path));
    }

    private void addNavField(TypeSpec.Builder builder, Set<String> taken, TypeElement origin,
                             TypeName owner, String navSimple, String identifier,
                             TypeName declaredType, CodeBlock initializer) {
        if (!taken.add(navSimple + "." + identifier)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "static metamodel collision in '" + navSimple + "': leaf '" + identifier
                            + "' already emitted; this member is skipped", origin);
            return;
        }
        builder.addField(FieldSpec.builder(declaredType, identifier, Modifier.PUBLIC, Modifier.FINAL)
                .initializer(initializer).build());
    }

    private void navCollision(TypeElement origin, String fqn, String path) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "static metamodel collision on navigable member '" + path + "': type '" + fqn
                        + "' already claimed; emitting a plain attribute record instead", origin);
    }

    private static JavaFile navFile(String packageName, TypeSpec.Builder builder) {
        return JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build();
    }

    private static MethodSpec overrideAccessor(String name, TypeName returnType, CodeBlock body) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(returnType)
                .addStatement("return $L", body).build();
    }

    private static MethodSpec overrideAccessor(String name, Class<?> returnType, CodeBlock body) {
        return overrideAccessor(name, ClassName.get(returnType), body);
    }

    private static String stripGenerics(String fqn) {
        int angle = fqn.indexOf('<');
        return angle < 0 ? fqn : fqn.substring(0, angle);
    }

    /**
     * One metamodel member in emission order: the list entry shared by {@code _Entity} and
     * {@code <Entity>Metadata}. {@code path} is the dot-separated attribute path (identifier =
     * {@code path.replace('.','_')}, UPPER constant = its uppercase), {@code column} the flattened
     * physical column (prefix applied) for column-bearing props, and {@code prop} the descriptor
     * property whose sealed shape drives every kind-specific decision downstream.
     */
    private record MmMember(String path, String identifier, String constant,
                            String column, Prop prop) { }

    /** The member list of one descriptor: id first, then properties in declaration order. */
    private List<MmMember> collectMembers(Element origin, DataModel model) {
        List<MmMember> members = new ArrayList<>();
        addMembers(members, new HashSet<>(), origin, model, "", "");
        return members;
    }

    private void addMembers(List<MmMember> out, Set<String> taken, Element origin,
                             DataModel model, String pathPrefix, String columnPrefix) {
        if (model.id() != null) {
            addMember(out, taken, origin, model.id(), pathPrefix, columnPrefix);
        }
        for (Prop a : model.props()) {
            addMember(out, taken, origin, a, pathPrefix, columnPrefix);
        }
    }

    /**
     * One root member. Task 6: a resolved {@link Prop.Embedded} contributes NO flat leaf recursion
     * here — its value object's leaves live on the navigable member class emitted by
     * {@link #generateStaticMetamodel}, and the flattened dot paths stay answerable through the
     * explicit {@code columns()} view. Duplicate identifiers keep the first member and report a NOTE
     * (spec collision handling).
     */
    private void addMember(List<MmMember> out, Set<String> taken, Element origin,
                           Prop a, String pathPrefix, String columnPrefix) {
        String path = pathPrefix.isEmpty() ? a.name() : pathPrefix + "." + a.name();
        String identifier = path.replace('.', '_');
        if (!taken.add(identifier)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "static metamodel collision on '" + path + "': member '" + identifier
                            + "' already emitted; this path is skipped", origin);
            return;
        }
        String rawColumn = memberColumn(a);
        String column = rawColumn == null ? null : columnPrefix + rawColumn;
        out.add(new MmMember(path, identifier, identifier.toUpperCase(), column, a));
    }

    /** The property's own physical column (or fk column), {@code null} for a columnless container. */
    private static String memberColumn(Prop p) {
        if (p instanceof Prop.Id id) {
            return id.column();
        }
        if (p instanceof Prop.Scalar scalar) {
            return scalar.column();
        }
        if (p instanceof Prop.Embedded embedded) {
            return embedded.column();
        }
        if (p instanceof Prop.Reference reference) {
            return reference.fkColumn();
        }
        return null; // EntityList / ElementList: no column on this table
    }

    /** The relationship/element target FQN for a descriptor-carrying prop, {@code null} otherwise. */
    private static String memberTargetFqn(Prop p) {
        if (p instanceof Prop.Embedded embedded) {
            return embedded.target() == null ? null : embedded.target().typeName();
        }
        if (p instanceof Prop.Reference reference) {
            return reference.targetTypeName();
        }
        if (p instanceof Prop.EntityList entityList) {
            return entityList.targetTypeName();
        }
        if (p instanceof Prop.ElementList elementList) {
            return elementList.elementTypeName();
        }
        return null;
    }

    /** The spec's attribute-kind classification for a metamodel member of the given type name. */
    private MetamodelKind metamodelKind(String typeFqn) {
        if (typeFqn == null) {
            return MetamodelKind.PLAIN;
        }
        if (METAMODEL_TEXT_TYPES.contains(typeFqn)) {
            return MetamodelKind.TEXT;
        }
        if (METAMODEL_SORTABLE_TYPES.contains(typeFqn)) {
            return MetamodelKind.SORTABLE;
        }
        TypeElement te = elements.getTypeElement(typeFqn);
        if (te != null && te.getKind() == ElementKind.ENUM) {
            return MetamodelKind.SORTABLE;
        }
        return MetamodelKind.PLAIN;
    }

    private enum MetamodelKind { PLAIN, SORTABLE, TEXT }

    /** The class literal argument for a target FQN, erasing generics; {@code Object} when unknown. */
    private TypeName classArgOf(String fqn) {
        if (fqn == null) {
            return ClassName.OBJECT;
        }
        int angle = fqn.indexOf('<');
        String name = angle < 0 ? fqn : fqn.substring(0, angle);
        TypeElement te = elements.getTypeElement(name);
        if (te != null) {
            return TypeName.get(types.erasure(te.asType()));
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? ClassName.OBJECT : ClassName.get(name.substring(0, dot), name.substring(dot + 1));
    }

    /** The declared static type of one metamodel field / descriptor attribute, by member kind. */
    private TypeName memberAttrType(TypeName owner, MmMember m) {
        Prop p = m.prop();
        if (p instanceof Prop.Id) {
            return ParameterizedTypeName.get(FM_ID, owner);
        }
        if (p instanceof Prop.Scalar scalar) {
            return switch (metamodelKind(scalar.typeFqn())) {
                case TEXT -> ParameterizedTypeName.get(FM_TEXT, owner);
                case SORTABLE -> ParameterizedTypeName.get(FM_SORTABLE, owner);
                default -> ParameterizedTypeName.get(FM_COLUMN, owner);
            };
        }
        if (p instanceof Prop.Embedded) {
            return ParameterizedTypeName.get(FM_EMBEDDED, owner, classArgOf(memberTargetFqn(p)));
        }
        if (p instanceof Prop.Reference) {
            return ParameterizedTypeName.get(FM_FK, owner, classArgOf(memberTargetFqn(p)));
        }
        if (p instanceof Prop.EntityList) {
            return ParameterizedTypeName.get(FM_OTM, owner, classArgOf(memberTargetFqn(p)));
        }
        return ParameterizedTypeName.get(FM_EC, owner, classArgOf(memberTargetFqn(p)));
    }

    /**
     * The initializer of one member. In the metamodel file ({@code constRef}) the attribute name is
     * the interface's own UPPER constant and {@code <>} infers from the declared field type; in the
     * VO metadata literal mode the name is the dot path string and type arguments are explicit,
     * because {@code List.of(...)} targets do not reliably push inference into record constructors.
     */
    private CodeBlock memberInitializer(TypeName owner, MmMember m, boolean constRef) {
        CodeBlock name = constRef ? CodeBlock.of("$L", m.constant()) : CodeBlock.of("$S", m.path());
        CodeBlock typeArgs = constRef ? CodeBlock.of("<>") : explicitTypeArgs(owner, m);
        Prop p = m.prop();
        if (p instanceof Prop.Id) {
            return CodeBlock.of("new $T$L($L)", FM_ID_RECORD, typeArgs, name);
        }
        if (p instanceof Prop.Scalar scalar) {
            ClassName rec = switch (metamodelKind(scalar.typeFqn())) {
                case TEXT -> FM_TEXT_RECORD;
                case SORTABLE -> FM_SORTABLE_RECORD;
                default -> FM_COLUMN_RECORD;
            };
            return CodeBlock.of("new $T$L($L)", rec, typeArgs, name);
        }
        if (p instanceof Prop.Embedded) {
            return CodeBlock.of("new $T$L($L)", FM_EMBEDDED_RECORD, typeArgs, name);
        }
        if (p instanceof Prop.Reference) {
            return CodeBlock.of("new $T$L($L)", FM_FK_RECORD, typeArgs, name);
        }
        if (p instanceof Prop.EntityList) {
            return CodeBlock.of("new $T$L($L)", FM_OTM_RECORD, typeArgs, name);
        }
        return CodeBlock.of("new $T$L($L)", FM_EC_RECORD, typeArgs, name);
    }

    /**
     * Whether a relation target resolves to an emitted {@code <Target>Metadata} class: the
     * same managed-target / mapper-gate predicate the descriptor supplier and the navigable member
     * classes (Task 6) share, so a nav's leaf fields exist exactly when its supplier is non-null.
     */
    private boolean relationTargetResolved(String targetFqn, boolean elementCollection) {
        if (targetFqn == null) {
            return false;
        }
        String rawName = stripGenerics(targetFqn);
        TypeElement targetElement = elements.getTypeElement(rawName);
        if (targetElement == null || !canGenerateRowMapper(entityModel(targetElement))) {
            return false;
        }
        return elementCollection || managedEntities.contains(rawName);
    }

    private CodeBlock explicitTypeArgs(TypeName owner, MmMember m) {
        Prop p = m.prop();
        if (p instanceof Prop.Id || p instanceof Prop.Scalar) {
            return CodeBlock.of("<$T>", owner);
        }
        return CodeBlock.of("<$T,$T>", owner, classArgOf(memberTargetFqn(p)));
    }

    /** Null-safe string emission: JavaPoet's {@code $S} is replaced by a literal {@code null} arg. */
    private static CodeBlock stringLiteral(String value) {
        return value == null ? CodeBlock.of("null") : CodeBlock.of("$S", value);
    }

    private MethodSpec stringAccessor(String name, String value) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(String.class)
                .addStatement("return $L", stringLiteral(value)).build();
    }

    /**
     * The {@code <VO>Metadata} type for a value-object (or relation-target) FQN: the registered name
     * from the embedded tree walk when available. The fallback resolves the element so a NESTED value
     * object ({@code pkg.Cfg.Geo}) maps to the TOP-LEVEL {@code pkg.GeoMetadata} the emitter actually
     * writes — the old last-dot split produced the member-class name {@code pkg.Cfg.GeoMetadata},
     * which never exists (parked Task 3 bug, re-checked and pinned in Task 6).
     */
    private ClassName embeddedMetadataName(String voFqn) {
        ClassName known = metadataTypes.get(voFqn);
        if (known != null) {
            return known;
        }
        String rawName = stripGenerics(voFqn);
        TypeElement element = elements.getTypeElement(rawName);
        if (element != null) {
            return ClassName.get(elements.getPackageOf(element).getQualifiedName().toString(),
                    element.getSimpleName() + "Metadata");
        }
        int dot = rawName.lastIndexOf('.');
        String pkg = dot < 0 ? "" : rawName.substring(0, dot);
        return ClassName.get(pkg, rawName.substring(dot + 1) + "Metadata");
    }

    /** Emits {@code <Entity>RowMapper implements io.github.fludakit.jdbc.RowMapper<Entity>}; reflection-free. */
    private ClassName generateRowMapper(TypeElement entityElement, EntityModel entity, ClassName metadataType) {
        String packageName = elements.getPackageOf(entityElement).getQualifiedName().toString();
        String simpleName = entityElement.getSimpleName() + "RowMapper";
        TypeName entityType = entity.typeName();
        ClassName rowMapperInterface = ClassName.get("io.github.fludakit.jdbc", "RowMapper");

        MethodSpec.Builder mapRow = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(entityType)
                .addParameter(ClassName.get("java.sql", "ResultSet"), "rs")
                .addParameter(int.class, "rowNum")
                .addException(ClassName.get("java.sql", "SQLException"))
                .addStatement("$T entity = new $T()", entityType, entityType);

        if (entity.id() != null) {
            appendPropertyRead(mapRow, entity.id().type(), metadataType, entity.id().name(),
                    entity.id().column(), entityElement.getSimpleName().toString());
        }
        // Task 9: a non-composing @Association (composition=false) materializes at read time as an
        // id-only PARTIAL instance through a generated partial_<Target> helper. A composition
        // Reference (root-owned many-to-one) stays loader-filled: it is deliberately NOT read here.
        // One helper per distinct target simple name (deduped). A target that cannot back a partial
        // (no no-arg ctor / setId / non-JDBC-safe id) degrades to a NOTE + null field (never abort).
        Map<String, MethodSpec> partialHelpers = new LinkedHashMap<>();
        for (Property p : entity.properties()) {
            if (p.prop() instanceof Prop.Reference ref) {
                if (!ref.composition()) {
                    appendAssociationPartial(mapRow, ref, metadataType, entityElement, partialHelpers);
                }
                continue; // composition reference: FK-only on the mapper, hydrated by the loader
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendEmbeddedRead(mapRow, metadataType, p, entityElement.getSimpleName().toString());
                continue;
            }
            appendPropertyRead(mapRow, p.type(), metadataType, p.name(), p.column(),
                    entityElement.getSimpleName().toString());
        }
        mapRow.addStatement("return entity");

        TypeSpec.Builder mapperBuilder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(rowMapperInterface, entityType))
                .addMethod(mapRow.build());
        partialHelpers.values().forEach(mapperBuilder::addMethod);
        TypeSpec type = mapperBuilder.build();
        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, type).skipJavaLangImports(true).build());
        return ClassName.get(packageName, simpleName);
    }

    /**
     * Emits the id-only partial materialisation of one {@code @Association} field (Task 9): a read of
     * the FK through the owner's metadata constant plus a {@code setter(fk == null ? null :
     * partial_<Target>(fk))} call. The {@code partial_<Target>} helper (new + setId, reflection-free)
     * is registered in {@code helpers} keyed by the target simple name so several associations to the
     * same target share one helper. The target's own relationships are NOT touched (id-only). Any
     * precondition failure (unresolvable target, no id, non-JDBC-safe id, missing no-arg ctor or
     * {@code setId}) records a NOTE on {@code entityElement} and leaves the field unset (null) — the
     * Requirements-Lock NOTE-degrade, never an abort.
     */
    private void appendAssociationPartial(MethodSpec.Builder mapRow, Prop.Reference ref,
                                          ClassName metadataType, TypeElement entityElement,
                                          Map<String, MethodSpec> helpers) {
        String why = associationPartialBlocked(ref, entityElement);
        if (why != null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "association '" + ref.name() + "' on " + entityElement.getQualifiedName() + ": " + why
                            + " — the field is left null on read (no id-only partial materialisation)",
                    entityElement);
            return;
        }
        EntityModel targetModel = entityModel(elements.getTypeElement(ref.targetTypeName()));
        TypeName idType = targetModel.id().type().box();
        TypeName targetType = targetModel.typeName();
        String targetSimple = targetSimpleName(targetModel);
        // Read the FK into a local named after its physical column (customer_id), reusing the owner's
        // metadata constant for the association property (CUSTOMER -> "customer_id").
        String fkLocal = ref.fkColumn();
        mapRow.addStatement("$T $L = $L", idType, fkLocal,
                readExpression(idType, metadataType, ref.name()));
        mapRow.addStatement("entity.$L($L == null ? null : partial_$L($L))",
                setter(ref.name()), fkLocal, targetSimple, fkLocal);
        helpers.computeIfAbsent(targetSimple, k -> MethodSpec.methodBuilder("partial_" + targetSimple)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(targetType)
                .addParameter(idType, "id")
                .addStatement("$T c = new $T()", targetType, targetType)
                .addStatement("c.$L(id)", setter(targetModel.id().name()))
                .addStatement("return c")
                .build());
    }

    /**
     * The reason a {@code @Association} target cannot back an id-only partial, or {@code null} when it
     * can. Checks (in order): the target element resolves, the target carries an id, the id is JDBC-safe
     * (so the FK reads through {@code getObject}), and the target exposes a no-arg constructor and an
     * id setter. Java 17 safety: driven by {@code elements}/{@code types}, never reflection in the
     * generated code.
     */
    private String associationPartialBlocked(Prop.Reference ref, TypeElement entityElement) {
        String fqn = ref.targetTypeName();
        if (fqn == null) {
            return "the target type could not be resolved";
        }
        TypeElement target = elements.getTypeElement(fqn);
        if (target == null) {
            return "the target " + fqn + " could not be resolved";
        }
        EntityModel targetModel = entityModel(target);
        if (targetModel.id() == null) {
            return "the target " + fqn + " has no identifier";
        }
        if (!isJdbcSafe(targetModel.id().type().box())) {
            return "the target " + fqn + " id type " + targetModel.id().type() + " is not JDBC-safe";
        }
        if (!hasNoArgConstructor(target)) {
            return "the target " + fqn + " has no accessible no-argument constructor";
        }
        if (!hasMethodNamed(target, setter(targetModel.id().name()))) {
            return "the target " + fqn + " has no " + setter(targetModel.id().name()) + " setter";
        }
        return null;
    }

    /** True when {@code element} can be instantiated with {@code new X()} (an explicit or default ctor). */
    private static boolean hasNoArgConstructor(TypeElement element) {
        boolean hasExplicitCtor = false;
        for (Element member : element.getEnclosedElements()) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                hasExplicitCtor = true;
                if (((ExecutableElement) member).getParameters().isEmpty()) {
                    return true;
                }
            }
        }
        return !hasExplicitCtor; // no explicit ctor → the compiler supplies a no-arg default
    }

    /** True when {@code element} has any member method named {@code name}. */
    private boolean hasMethodNamed(TypeElement element, String name) {
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves (generating on first use) the {@code <VO>RowMapper} for an element-collection child VO.
     * The VO's own descriptor metadata is generated (and its transitive embedded tree) so the mapper's
     * leaf reads reference {@code <VO>Metadata} constants. A VO that fails the row-mapper gate yields
     * {@code null}: its collection loader is skipped and a NOTE is recorded, so hydration degrades to
     * the parent's default field value rather than failing the whole repository (Decision 7 spirit).
     */
    private ClassName voRowMapperFor(ElementCollection collection, Element origin) {
        String voFqn = collection.descriptor().typeName();
        ClassName cached = voRowMappers.get(voFqn);
        if (cached != null) {
            return cached;
        }
        TypeElement voElement = elements.getTypeElement(voFqn);
        if (voElement == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Cannot resolve element-collection value object '" + voFqn
                            + "'; its children will not be hydrated.", origin);
            return null;
        }
        EntityModel voModel = buildEntity(voElement);
        if (!canGenerateRowMapper(voModel)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Value object '" + voFqn + "' is not reflection-free mappable; its element collection"
                            + " children will not be hydrated.", origin);
            return null;
        }
        if (!metadataTypes.containsKey(voFqn)) {
            generateEmbeddedDescriptors(voModel.metadata());
            ensureDescriptorArtifacts(voElement, voModel, true);
        }
        ClassName voMetadata = metadataTypes.get(voFqn);
        ClassName mapper = generateValueObjectRowMapper(voElement, voModel, voMetadata);
        voRowMappers.put(voFqn, mapper);
        return mapper;
    }

    /**
     * Emits {@code <VO>RowMapper implements RowMapper<VO>} for a value object read from an
     * element-collection child table. It is {@link #generateRowMapper} without the identifier step: a
     * {@code @ValueObject} has no id and the child table's physical columns ARE the VO's (flattened)
     * leaf columns, so the standalone mapper reads UNprefixed labels from the VO's OWN metadata. FK-only
     * children ({@code ASSOCIATION}/{@code MANY_TO_ONE}) are skipped — never hydrated in this task
     * (Decision 8) — and {@code EMBEDDED} children are rebuilt leaf-by-leaf via
     * {@link #appendEmbeddedRead}.
     */
    private ClassName generateValueObjectRowMapper(TypeElement voElement, EntityModel voModel,
                                                  ClassName voMetadataType) {
        String packageName = elements.getPackageOf(voElement).getQualifiedName().toString();
        String simpleName = voElement.getSimpleName() + "RowMapper";
        TypeName voType = voModel.typeName();
        ClassName rowMapperInterface = ClassName.get("io.github.fludakit.jdbc", "RowMapper");
        String voName = voElement.getSimpleName().toString();

        MethodSpec.Builder mapRow = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(voType)
                .addParameter(ClassName.get("java.sql", "ResultSet"), "rs")
                .addParameter(int.class, "rowNum")
                .addException(ClassName.get("java.sql", "SQLException"))
                .addStatement("$T entity = new $T()", voType, voType);

        for (Property p : voModel.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // FK-only reference; left null (loaders fill it in Task 4)
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendEmbeddedRead(mapRow, voMetadataType, p, voName);
                continue;
            }
            appendPropertyRead(mapRow, p.type(), voMetadataType, p.name(), p.column(), voName);
        }
        mapRow.addStatement("return entity");

        TypeSpec type = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(rowMapperInterface, voType))
                .addMethod(mapRow.build())
                .build();
        writeSourceFile(packageName + "." + simpleName, voElement,
                JavaFile.builder(packageName, type).skipJavaLangImports(true).build());
        return ClassName.get(packageName, simpleName);
    }

    /**
     * Emits the assignment of one mapped column into the freshly constructed {@code entity}. Boxed
     * and reference columns are read via {@code getObject} and assigned directly, since a SQL NULL
     * is a legal value for them. A primitive column cannot hold NULL, so the value is read into a
     * method-unique temporary and {@code rs.wasNull()} is checked immediately after the getter to
     * fail fast with a {@code SQLException} — mirroring the reflective mapper, which throws when a
     * NULL is assigned to a primitive field. The {@code id} read follows the same rule.
     */
    private void appendPropertyRead(MethodSpec.Builder mapRow, TypeName type, ClassName metadataType,
                                    String property, String columnName, String entityName) {
        if (!type.isPrimitive()) {
            mapRow.addStatement("entity.$L($L)", setter(property), readExpression(type, metadataType, property));
            return;
        }
        // Temp name is derived from the (unique) property name so it cannot collide with the
        // generated entity/rs/rowNum locals nor with another primitive property in the same mapper.
        String temp = property + "Value";
        mapRow.addStatement("$T $L = rs.$L($T.$L)", type, temp, primitiveGetter(type),
                metadataType, constName(property));
        mapRow.beginControlFlow("if (rs.wasNull())");
        mapRow.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                "NULL value in non-nullable primitive column \"" + columnName
                        + "\" mapped to " + entityName + "." + property);
        mapRow.endControlFlow();
        mapRow.addStatement("entity.$L($L)", setter(property), temp);
    }

    /**
     * Emits the read and construction of one {@code EMBEDDED} property (Decision 2): every flattened
     * leaf of the value-object tree is read into a local named after its physical column
     * ({@code <prefix>_<leaf>}, e.g. {@code address_point_lat}); the top VO is then constructed inside
     * a guard firing iff at least one boxed leaf anywhere in its subtree is non-null, and each nested
     * child VO is constructed and set iff at least one boxed leaf in <em>its own</em> subtree is
     * non-null — an all-null child subtree leaves the child null (per-subtree presence). Primitive
     * leaves follow the {@link #appendPropertyRead} temp+{@code wasNull()} rule, never join any
     * presence test, and so a VO with a primitive leaf cannot be NULL-absent in the database. The
     * whole block stays reflection-free.
     */
    private void appendEmbeddedRead(MethodSpec.Builder mapRow, ClassName ownerMetadata,
                                    Property embedded, String entityName) {
        List<String> presence = new ArrayList<>();
        readEmbeddedLeaves(mapRow, ownerMetadata, embedded.embeddedModel(),
                embedded.column(), embedded.name(), presence, entityName);
        boolean guarded = !presence.isEmpty();
        if (guarded) {
            mapRow.beginControlFlow("if ($L)", String.join(" || ", presence));
        }
        buildEmbeddedValue(mapRow, embedded.embeddedModel(), embedded.column(), embedded.name());
        mapRow.addStatement("entity.$L($L)", setter(embedded.name()), embedded.name());
        if (guarded) {
            mapRow.endControlFlow();
        }
    }

    /** Reads each flattened leaf of {@code vo} into a column-named local, collecting the boxed-leaf presence terms. */
    private void readEmbeddedLeaves(MethodSpec.Builder mapRow, ClassName ownerMetadata, EntityModel vo,
                                    String prefix, String path, List<String> presence, String entityName) {
        for (Property p : vo.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // fk-only children stay null on read, as at owner level
            }
            if (p.prop() instanceof Prop.Embedded) {
                readEmbeddedLeaves(mapRow, ownerMetadata, p.embeddedModel(),
                        prefix + "_" + p.column(), path + "." + p.name(), presence, entityName);
                continue;
            }
            String local = prefix + "_" + p.column();
            if (!p.type().isPrimitive()) {
                mapRow.addStatement("$T $L = $L", p.type(), local,
                        readExpression(p.type(), ownerMetadata, local));
                presence.add(local + " != null");
                continue;
            }
            mapRow.addStatement("$T $L = rs.$L($T.$L)", p.type(), local, primitiveGetter(p.type()),
                    ownerMetadata, constName(local));
            mapRow.beginControlFlow("if (rs.wasNull())");
            mapRow.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                    "NULL value in non-nullable primitive column \"" + local
                            + "\" mapped to " + entityName + "." + path);
            mapRow.endControlFlow();
        }
    }

    /**
     * Constructs one value-object instance from its already-read leaf locals (parent first): the VO's
     * own scalar leaves are assigned, then each nested child is constructed and set under its OWN
     * subtree presence guard (Decision 2 — an all-null child subtree leaves the child null). A child
     * whose subtree holds no boxed leaf has no presence signal and is constructed unconditionally
     * under the parent's guard.
     */
    private void buildEmbeddedValue(MethodSpec.Builder mapRow, EntityModel vo, String prefix, String local) {
        mapRow.addStatement("$T $L = new $T()", vo.typeName(), local, vo.typeName());
        for (Property p : vo.properties()) {
            Prop pp = p.prop();
            if (pp instanceof Prop.Reference || pp instanceof Prop.Embedded) {
                continue;
            }
            mapRow.addStatement("$L.$L($L)", local, setter(p.name()), prefix + "_" + p.column());
        }
        for (Property p : vo.properties()) {
            if (!(p.prop() instanceof Prop.Embedded)) {
                continue;
            }
            String childLocal = prefix + "_" + p.column();
            List<String> terms = new ArrayList<>();
            collectPresenceTerms(p.embeddedModel(), childLocal, terms);
            if (terms.isEmpty()) {
                buildEmbeddedValue(mapRow, p.embeddedModel(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, setter(p.name()), childLocal);
            } else {
                mapRow.beginControlFlow("if ($L)", String.join(" || ", terms));
                buildEmbeddedValue(mapRow, p.embeddedModel(), childLocal, childLocal);
                mapRow.addStatement("$L.$L($L)", local, setter(p.name()), childLocal);
                mapRow.endControlFlow();
            }
        }
    }

    /** The boxed-leaf locals whose nullness marks absence within {@code vo}'s subtree rooted at {@code prefix}. */
    private void collectPresenceTerms(EntityModel vo, String prefix, List<String> terms) {
        for (Property p : vo.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded) {
                collectPresenceTerms(p.embeddedModel(), prefix + "_" + p.column(), terms);
                continue;
            }
            if (!p.type().isPrimitive()) {
                terms.add(prefix + "_" + p.column() + " != null");
            }
        }
    }

    private static String primitiveGetter(TypeName type) {
        return switch (type.toString()) {
            case "boolean" -> "getBoolean";
            case "byte" -> "getByte";
            case "short" -> "getShort";
            case "int" -> "getInt";
            case "long" -> "getLong";
            case "float" -> "getFloat";
            case "double" -> "getDouble";
            default -> throw new IllegalStateException("Unsupported primitive " + type);
        };
    }

    /** Boxed/reference read expression; a primitive column is guarded separately in {@link #appendPropertyRead}. */
    private CodeBlock readExpression(TypeName type, ClassName metadataType, String property) {
        return CodeBlock.of("rs.getObject($T.$L, $T.class)", metadataType, constName(property), type.box());
    }

    private void writeSourceFile(String fqn, Element origin, JavaFile javaFile) {
        // Register the emitted type name in the session-global collision registry so every later
        // claim (nav classes especially) sees it — this is the audit point covering ALL emissions
        // (_Entity metamodels, navs, metadata, row mappers, repository impls) through one funnel.
        generatedTypeNames.add(fqn);
        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(fqn, origin);
            try (Writer writer = sourceFile.openWriter()) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write " + fqn + ": " + e.getMessage(), origin);
        }
    }

    // ------------------------------------------------------------- method dispatch

    private MethodSpec generateMethod(ExecutableElement method, DeclaredType repositoryType, EntityModel entity,
                                      boolean crud, Map<String, String> constants, ClassName mapperType,
                                      boolean hydrate, List<MethodSpec> auxMethods,
                                      List<ResolvedQueryModel> queryModels,
                                      Map<String, Integer> queryModelNames,
                                      List<EntityRelationship> cascades) {
        ExecutableType resolved = (ExecutableType) types.asMemberOf(repositoryType, method);
        TypeMirror returnMirror = resolved.getReturnType();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        List<? extends VariableElement> parameters = method.getParameters();
        String name = method.getSimpleName().toString();
        int arity = parameters.size();

        MethodSpec.Builder builder = MethodSpec.overriding(method, repositoryType, types);
        rebindTypeVariables(builder, method, entity.typeName());

        Query query = method.getAnnotation(Query.class);
        if (query != null) {
            emitQuery(builder, method, query, returnMirror, entity, constants);
            return builder.build();
        }

        if (arity == 1 && isEntityParameter(parameterTypes.get(0), entity)) {
            String value = parameterName(parameters.get(0), "entity");
            if (name.equals("delete")) {
                builder.addStatement("deleteById($L.$L())", value, getter(entity.id().name()));
                return builder.build();
            }
            if (name.equals("save")) {
                emitSave(builder, method, value, entity, crud, constants, cascades);
                return builder.build();
            }
            if (name.equals("insert") && crud) {
                appendInsertStatements(builder, value, entity, constants, cascades);
                builder.addStatement("return $L", value);
                return builder.build();
            }
            if (name.equals("update") && crud) {
                appendUpdateStatements(builder, value, entity, constants, cascades);
                builder.addStatement("return $L", value);
                return builder.build();
            }
        }

        if (arity == 1 && name.equals("saveAll")) {
            emitBatch(builder, method, parameters.get(0), entity, "save");
            return builder.build();
        }
        if (arity == 1 && name.equals("insertAll") && crud) {
            emitBatch(builder, method, parameters.get(0), entity, "insert");
            return builder.build();
        }
        if (arity == 1 && name.equals("updateAll") && crud) {
            emitBatch(builder, method, parameters.get(0), entity, "update");
            return builder.build();
        }
        if (arity == 1 && name.equals("deleteAll")) {
            builder.beginControlFlow("for ($T entity : $L)", entity.typeName(), parameterName(parameters.get(0), "entities"));
            builder.addStatement("delete(entity)");
            builder.endControlFlow();
            return builder.build();
        }
        if (arity == 1 && name.equals("findById")) {
            emitFindById(builder, parameterName(parameters.get(0), "id"), entity, constants, mapperType, hydrate);
            return builder.build();
        }
        if (arity == 1 && name.equals("deleteById")) {
            emitDeleteById(builder, parameterName(parameters.get(0), "id"), entity, constants, cascades);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 0) {
            emitFindAll(builder, entity, constants, mapperType, hydrate);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 2) {
            emitFindAllPage(builder, parameters, entity, constants, mapperType, hydrate);
            return builder.build();
        }

        if (arity == 1 && method.getAnnotation(Insert.class) != null) {
            String value = parameterName(parameters.get(0), "entity");
            appendInsertStatements(builder, value, entity, constants, cascades);
            builder.addStatement("return $L", value);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Update.class) != null) {
            String value = parameterName(parameters.get(0), "entity");
            appendUpdateStatements(builder, value, entity, constants, cascades);
            builder.addStatement("return $L", value);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Save.class) != null) {
            emitSave(builder, method, parameterName(parameters.get(0), "entity"), entity, crud, constants,
                    cascades);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Delete.class) != null) {
            String value = parameterName(parameters.get(0), "entity");
            builder.addStatement("deleteById($L.$L())", value, getter(entity.id().name()));
            return builder.build();
        }

        emitFind(builder, method, resolved, entity, constants, mapperType, hydrate, auxMethods,
                queryModels, queryModelNames);
        return builder.build();
    }

    // ------------------------------------------------------------------- CRUD writes

    private void emitSave(MethodSpec.Builder builder, ExecutableElement method, String value,
                          EntityModel entity, boolean crud, Map<String, String> constants,
                          List<EntityRelationship> cascades) {
        if (idKeyCoercion(entity.id().type()) == null) {
            // The null-id discrimination routes an application-assigned entity with no id yet into an
            // INSERT that would bind NULL into the identity column — warn and keep compiling.
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "id type " + entity.id().type().box() + " requires application-assigned identifiers",
                    method);
        }
        String idAccess = value + "." + getter(entity.id().name()) + "()";
        if (crud) {
            builder.addStatement("return $L == null ? insert($L) : update($L)", idAccess, value, value);
            return;
        }
        builder.beginControlFlow("if ($L == null)", idAccess);
        appendInsertStatements(builder, value, entity, constants, cascades);
        builder.addStatement("return $L", value);
        builder.nextControlFlow("else");
        appendUpdateStatements(builder, value, entity, constants, cascades);
        builder.addStatement("return $L", value);
        builder.endControlFlow();
    }

    private void appendInsertStatements(MethodSpec.Builder builder, String value, EntityModel entity,
                                        Map<String, String> constants, List<EntityRelationship> cascades) {
        String coercion = idKeyCoercion(entity.id().type());
        if (coercion == null) {
            // Task 3 (S-4/T11 P1): an id type no GeneratedKeyHolder key can produce (String, UUID,
            // ...) is application-assigned — the identity column joins the INSERT and binds from the
            // entity; there is no keyholder roundtrip for a value known before the insert.
            String appConstant = addConstant(constants, "SQL_INSERT",
                    new SqlBuilder().insert(entity.metadata(), true));
            CodeBlock.Builder appSql = CodeBlock.builder().add("jdbcClient.sql($L)", appConstant);
            appSql.add(".param($S, $L.$L())", SqlBuilder.paramName(entity.metadata().id().name()),
                    value, getter(entity.id().name()));
            appendColumnParams(appSql, entity, value);
            appSql.add(".update()");
            builder.addStatement("$L", appSql.build());
        } else {
            // includeId=false: the generated key is read back through GeneratedKeyHolder, so the identity
            // column must be omitted from the INSERT (H2 and most databases reject an explicit NULL).
            // NEW-I: the coercion method follows the id type — Long renders longValue() byte-identically
            // to the pre-Task-4 hardcoded emission, Integer renders intValue() so its impl compiles.
            String constant = addConstant(constants, "SQL_INSERT",
                    new SqlBuilder().insert(entity.metadata(), false));
            builder.addStatement("$T keys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
            CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            appendColumnParams(sql, entity, value);
            sql.add(".update(keys)");
            builder.addStatement("$L", sql.build());

            builder.beginControlFlow("if (keys.getKey() != null)");
            builder.addStatement("$L.$L((($T) keys.getKey()).$L())",
                    value, setter(entity.id().name()), Number.class, coercion);
            builder.endControlFlow();
        }

        for (ElementCollection collection : entity.collections()) {
            appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
        // R-T11a: the root INSERT cascades each composition entity collection — the child's own
        // generated-key machinery assigns its id, the inverse fk is bound from the ROOT id.
        for (EntityRelationship relationship : cascades) {
            appendEntityCollectionInsert(builder, value, entity, relationship, constants);
        }
    }

    /**
     * NOTE-degrade writability check for the R-T11a entity-collection cascade: a child whose identity
     * cannot be written through the generated-key machinery (no id property, an id type {@link
     * #idKeyCoercion} cannot produce from a {@code Number} key, unresolved table metadata) skips
     * ONLY its own collection's cascade — the repository still compiles, reads keep working. Consulted
     * by the {@link #generate} cascade scan on the WRITABILITY path only: like element collections,
     * the plain-SQL child INSERT/DELETE needs this weaker requirement and NOT a row mapper, so a
     * target that fails the managed/mapper READ gate still cascades on write (fix-round 2, I-2).
     */
    private static String entityCollectionWriteBlocker(EntityModel target) {
        if (target.id() == null) {
            return "target has no identity property";
        }
        if (target.metadata().tableName() == null) {
            return "target table metadata is unresolved";
        }
        if (idKeyCoercion(target.id().type()) == null) {
            return "target id type " + target.id().type().box() + " is not generated-key coercible";
        }
        return null;
    }

    /**
     * Emits the R-T11a cascade INSERT for one entity collection: iterate the in-memory children, run
     * the CHILD's own SqlBuilder insert (identity column omitted, generated key read back) with every
     * column bound through {@link #boundValue} on the CHILD descriptor — EXCEPT the inverse-fk
     * parameter, which is bound from the root's id (the parent context supplies the fk; the bare
     * inverse field is never serialized, matching the existing {@code customer.getId()} fk-extraction
     * shape) — then backfill the generated child id. Depth-1: a cascaded child's own collections are
     * not written (mirrors the read-side depth-1 contract lock).
     */
    private void appendEntityCollectionInsert(MethodSpec.Builder builder, String value, EntityModel entity,
                                              EntityRelationship relationship, Map<String, String> constants) {
        Prop.EntityList list = (Prop.EntityList) relationship.attribute();
        EntityModel target = relationship.targetModel();
        DataModel md = target.metadata();
        String inverseFk = list.inverseFkColumn();
        String constant = addConstant(constants, "SQL_INSERT_" + upperSnake(list.name()),
                new SqlBuilder().insert(md, false));
        builder.beginControlFlow("for ($T item : $L.$L())", target.typeName(), value, getter(list.name()));
        builder.beginControlFlow("if (item != null)");
        builder.addStatement("$T childKeys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        for (ColumnRef ref : md.columns()) {
            if (md.id() != null && ref.column().equals(md.id().column())) {
                continue;
            }
            if (ref.column().equals(inverseFk)) {
                sql.add(".param($S, $L.$L())", SqlBuilder.paramName(ref.path()),
                        value, getter(entity.id().name()));
            } else {
                sql.add(".param($S, $L)", SqlBuilder.paramName(ref.path()), boundValue(md, "item", ref));
            }
        }
        sql.add(".update(childKeys)");
        builder.addStatement("$L", sql.build());
        builder.beginControlFlow("if (childKeys.getKey() != null)");
        builder.addStatement("item.$L((($T) childKeys.getKey()).$L())", setter(target.id().name()),
                Number.class, idKeyCoercion(target.id().type()));
        builder.endControlFlow();
        builder.endControlFlow();
        builder.endControlFlow();
    }

    /**
     * The {@code SQL_DELETE_<FIELD>} constant for one cascading entity collection: the CHILD's own
     * table, keyed on the inverse fk column — the delete half of the R-T11a replace-all update and of
     * deleteById (mirrors {@link #childDeleteConstant} for element collections). Routed through
     * {@link #addConstant} so update and deleteById share one field.
     */
    private String entityChildDeleteConstant(Map<String, String> constants, Prop.EntityList list) {
        return addConstant(constants, "SQL_DELETE_" + upperSnake(list.name()),
                "DELETE FROM " + Names.quote(list.targetTable())
                        + " WHERE " + Names.quote(list.inverseFkColumn()) + " = :" + list.inverseFkColumn());
    }

    /** Emits {@code jdbcClient.sql(SQL_DELETE_<FIELD>).param(<inverseFk>, entity.getId()).update()}. */
    private void appendEntityCollectionDelete(MethodSpec.Builder builder, String value, EntityModel entity,
                                              EntityRelationship relationship, Map<String, String> constants) {
        Prop.EntityList list = (Prop.EntityList) relationship.attribute();
        String constant = entityChildDeleteConstant(constants, list);
        builder.addStatement("jdbcClient.sql($L).param($S, $L.$L()).update()", constant, list.inverseFkColumn(),
                value, getter(entity.id().name()));
    }

    /**
     * The {@code Number} → id coercion method for a {@code GeneratedKeyHolder} key (NEW-I, Task 3):
     * {@code box()} normalizes a primitive id ({@code int} → {@code java.lang.Integer}); Long renders
     * {@code longValue()} textually identical to the pre-Task-4 hardcoded emission and Integer renders
     * {@code intValue()}. {@code null} means no {@code Number} key can produce this id type (String,
     * UUID, ...) — the insert path takes the application-assigned shape instead: the identity column
     * is part of the INSERT and the keyholder roundtrip is skipped entirely.
     */
    private static String idKeyCoercion(TypeName idType) {
        return switch (idType.box().toString()) {
            case "java.lang.Long" -> "longValue";
            case "java.lang.Integer" -> "intValue";
            default -> null;
        };
    }

    private void appendElementCollectionInsert(MethodSpec.Builder builder, String value, EntityModel entity,
                                               ElementCollection collection, Map<String, String> constants) {
        // M-1: the child-table column list must iterate the SAME flattened descriptor columns the
        // params below bind. The old list walked {@code collection.properties()} while the params
        // walked {@code descriptor().columns()}; the two diverge for a collection VO that embeds
        // another VO (one column per property vs one per flattened leaf). Iterating the descriptor
        // here keeps columns and params aligned. Byte-stable for a flat VO (no nested embedded).
        List<String> columns = childColumnNames(collection);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Names.quote(collection.childTable())).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(Names.quote(columns.get(i)));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(':').append(columns.get(i));
        }
        sql.append(')');
        String constant = addConstant(constants, "SQL_INSERT_" + upperSnake(collection.field()), sql.toString());

        builder.beginControlFlow("for ($T item : $L.$L())", collection.valueObjectType(), value,
                getter(collection.field()));
        builder.beginControlFlow("if (item != null)");
        CodeBlock.Builder insert = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        insert.add(".param($S, $L.$L())", collection.parentFk(), value, getter(entity.id().name()));
        // Child-table placeholders stay COLUMN-named: the hand-rendered INSERT above lists the
        // element-collection columns (:product_id, :quantity), so the params must match them. The
        // authoritative flat child-column list is {@link Prop.ElementList#elementColumns()} — one
        // source of truth — while the value still walks the descriptor path through boundValue so
        // fk-bearing children bind their id exactly like the previous property-driven loop did.
        for (ColumnRef ref : collection.element().elementColumns()) {
            insert.add(".param($S, $L)", ref.column(), boundValue(collection.descriptor(), "item", ref));
        }
        insert.add(".update()");
        builder.addStatement("$L", insert.build());
        builder.endControlFlow();
        builder.endControlFlow();
    }

    /**
     * The child-table's physical columns in write order: the parent FK first, then one entry per
     * flattened descriptor column — exactly the names the element-collection INSERT params bind
     * (M-1). A flat VO yields the same list as its property columns did before the fix.
     */
    private List<String> childColumnNames(ElementCollection collection) {
        List<String> columns = new ArrayList<>();
        columns.add(collection.parentFk());
        for (ColumnRef ref : collection.element().elementColumns()) {
            columns.add(ref.column());
        }
        return columns;
    }

    /**
     * The child-table column list for the hydration SELECT: the parent FK (the grouping key) followed
     * by every descriptor column the generated VO row mapper actually reads back. FK-only columns
     * ({@code ASSOCIATION}/{@code MANY_TO_ONE}) are excluded — they are never hydrated in this task
     * (Decision 8) — as are relationship kinds with no physical column. This mirrors the mapper's own
     * {@code properties()} loop, which skips the same kinds.
     */
    private String quotedColumnList(ElementCollection collection) {
        StringBuilder sb = new StringBuilder(Names.quote(collection.parentFk()));
        for (Prop a : collection.descriptor().props()) {
            if (a instanceof Prop.Id id) {
                sb.append(", ").append(Names.quote(id.column()));
            } else if (a instanceof Prop.Scalar scalar) {
                sb.append(", ").append(Names.quote(scalar.column()));
            } else if (a instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    continue;
                }
                for (ColumnRef leaf : embedded.target().columns()) {
                    sb.append(", ").append(Names.quote(embedded.column() + "_" + leaf.column()));
                }
            }
            // Reference / EntityList / ElementList: no read column on this child table.
        }
        return sb.toString();
    }

    private void appendUpdateStatements(MethodSpec.Builder builder, String value, EntityModel entity,
                                        Map<String, String> constants, List<EntityRelationship> cascades) {
        String constant = addConstant(constants, "SQL_UPDATE", new SqlBuilder().update(entity.metadata()));

        CodeBlock.Builder update = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendColumnParams(update, entity, value);
        update.add(".param($S, $L.$L())", "id", value, getter(entity.id().name()));
        update.add(".update()");
        builder.addStatement("$L", update.build());

        // Replace-all children (Task 3 / fix I-1): delete the parent's whole child set, then re-insert it
        // from the entity's current collection. This iterates EVERY collection, not just the gate-passed
        // (hydrated) ones: save() inserts rows for all of them (appendInsertStatements), so update must
        // replace and deleteById must remove all of them too. A child VO that fails the row-mapper gate
        // only loses its hydration LOADER — the DELETE/re-insert here is plain SQL driven by the child
        // table/FK and needs no VO mapper, so gate failure degrades hydration only, not cascade integrity.
        for (ElementCollection collection : entity.collections()) {
            appendElementCollectionDelete(builder, value, entity, collection, constants);
            appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
        // R-T11a replace-all PARITY with element collections: delete the parent's whole child set by
        // the inverse fk, then re-insert it from the entity's current collection. Child ids are
        // REGENERATED (delete + identity re-insert is what replace-all means on the element side; no
        // upsert-by-id machinery exists, and stale in-memory ids would diverge from that semantic).
        for (EntityRelationship relationship : cascades) {
            appendEntityCollectionDelete(builder, value, entity, relationship, constants);
            appendEntityCollectionInsert(builder, value, entity, relationship, constants);
        }
    }

    /**
     * Registers (once, idempotently) the {@code SQL_DELETE_<FIELD>} constant shared by the replace-all
     * update cascade and {@code deleteById}. Both callers MUST build it through this method: {@link
     * #addConstant} keys by the exact SQL, so two hand-written builders that drift would silently emit a
     * duplicate {@code SQL_DELETE_X_2} field (M-2).
     */
    private String childDeleteConstant(Map<String, String> constants, ElementCollection collection) {
        return addConstant(constants, "SQL_DELETE_" + upperSnake(collection.field()),
                "DELETE FROM " + Names.quote(collection.childTable())
                        + " WHERE " + Names.quote(collection.parentFk()) + " = :" + collection.parentFk());
    }

    /** Emits {@code jdbcClient.sql(SQL_DELETE_<FIELD>).param(<parentFk>, entity.getId()).update()}. */
    private void appendElementCollectionDelete(MethodSpec.Builder builder, String value, EntityModel entity,
                                               ElementCollection collection, Map<String, String> constants) {
        String constant = childDeleteConstant(constants, collection);
        builder.addStatement("jdbcClient.sql($L).param($S, $L.$L()).update()", constant,
                collection.parentFk(), value, getter(entity.id().name()));
    }

    /**
     * Binds one {@code .param(...)} per physical column exactly as {@link SqlBuilder} renders it:
     * the name is {@code paramName(path)} and the value walks the dot-path. The identifier is
     * skipped by COLUMN EQUALITY, mirroring {@code SqlBuilder.insert(md, false)} and
     * {@code SqlBuilder.update(md)} (which both also exclude the id from SET); any other predicate
     * would drift against the rendered placeholders.
     */
    private void appendColumnParams(CodeBlock.Builder sql, EntityModel entity, String var) {
        DataModel md = entity.metadata();
        for (ColumnRef ref : md.columns()) {
            if (md.id() != null && ref.column().equals(md.id().column())) {
                continue;
            }
            sql.add(".param($S, $L)", SqlBuilder.paramName(ref.path()), boundValue(md, var, ref));
        }
    }

    /**
     * Renders the accessor expression for one physical column path (e.g. {@code address.point.lat}
     * or a FK-bearing {@code primary}) rooted at {@code rootVar}. Every intermediate null short-
     * circuits to SQL NULL; a final ASSOCIATION/MANY_TO_ONE attribute binds its id.
     */
    private static CodeBlock boundValue(DataModel md, String rootVar, ColumnRef ref) {
        String[] segments = ref.path().split("\\.");
        StringBuilder chain = new StringBuilder(rootVar);
        List<String> nullChecks = new ArrayList<>();
        DataModel current = md;
        for (int i = 0; i < segments.length; i++) {
            Prop attr = current.prop(segments[i]);
            chain.append('.').append(getter(segments[i])).append("()");
            if (i < segments.length - 1) {
                nullChecks.add(chain.toString());
                current = ((Prop.Embedded) attr).target();
            } else if (attr instanceof Prop.Reference) {
                nullChecks.add(chain.toString());
                chain.append(".getId()");
            }
        }
        if (nullChecks.isEmpty()) {
            return CodeBlock.of("$L", chain.toString());
        }
        StringBuilder guard = new StringBuilder();
        for (int i = 0; i < nullChecks.size(); i++) {
            if (i > 0) {
                guard.append(" || ");
            }
            guard.append(nullChecks.get(i)).append(" == null");
        }
        return CodeBlock.of("$L ? null : $L", guard.toString(), chain.toString());
    }

    private void emitBatch(MethodSpec.Builder builder, ExecutableElement method, VariableElement parameter,
                           EntityModel entity, String delegate) {
        TypeVariableName element = method.getTypeParameters().isEmpty()
                ? TypeVariableName.get("S", entity.typeName())
                : TypeVariableName.get(method.getTypeParameters().get(0).getSimpleName().toString(), entity.typeName());
        builder.addStatement("$T<$T> result = new $T<>()", List.class, element, ArrayList.class);
        builder.beginControlFlow("for ($T entity : $L)", element, parameterName(parameter, "entities"));
        builder.addStatement("result.add($L(entity))", delegate);
        builder.endControlFlow();
        builder.addStatement("return result");
    }

    // ------------------------------------------------------------------- CRUD reads

    private void emitFindById(MethodSpec.Builder builder, String value, EntityModel entity,
                              Map<String, String> constants, ClassName mapperType, boolean hydrate) {
        String constant = addConstant(constants, "SQL_FIND_BY_ID",
                new SqlBuilder().selectBy(entity.metadata(),
                        List.of(Predicate.of(entity.id().name(), Operator.EQ, "id")), List.of(), 0, null));
        if (!hydrate) {
            builder.addStatement("return jdbcClient.sql($L).param($S, $L).query($L).optional()",
                    constant, "id", value, queryArg(entity, mapperType));
            return;
        }
        builder.addStatement("$T<$T> found = jdbcClient.sql($L).param($S, $L).query($L).optional()",
                java.util.Optional.class, entity.typeName(), constant, "id", value, queryArg(entity, mapperType));
        builder.addStatement("found.ifPresent(e -> $L)", hydrationCall(entity, "java.util.List.of(e)"));
        builder.addStatement("return found");
    }

    private void emitDeleteById(MethodSpec.Builder builder, String value, EntityModel entity,
                                Map<String, String> constants, List<EntityRelationship> cascades) {
        // Children first, then the parent: the child table has no FK constraint to lean on, but the
        // order keeps the child rows from outliving their parent even for a single autocommit delete.
        // Fix I-1: cascade over EVERY collection (save inserts all of them), not just the gate-passed
        // hydrated ones — a child whose VO fails the mapper gate would otherwise orphan rows. The child
        // DELETE is plain SQL keyed on the child table/FK, so it needs no VO mapper (see childDeleteConstant).
        for (ElementCollection collection : entity.collections()) {
            String childConstant = childDeleteConstant(constants, collection);
            builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", childConstant,
                    collection.parentFk(), value);
        }
        // R-T11a: the composition lifecycle is bound to the root — entity-collection children are
        // deleted from the CHILD's own table by the inverse fk, same children-first order.
        for (EntityRelationship relationship : cascades) {
            Prop.EntityList list = (Prop.EntityList) relationship.attribute();
            String childConstant = entityChildDeleteConstant(constants, list);
            builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", childConstant,
                    list.inverseFkColumn(), value);
        }
        String constant = addConstant(constants, "SQL_DELETE", new SqlBuilder().deleteById(entity.metadata()));
        builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", constant, "id", value);
    }

    private void emitFindAll(MethodSpec.Builder builder, EntityModel entity, Map<String, String> constants,
                             ClassName mapperType, boolean hydrate) {
        String constant = addConstant(constants, "SQL_FIND_ALL",
                new SqlBuilder().selectBy(entity.metadata(), List.of(), List.of(), 0, null));
        if (!hydrate) {
            builder.addStatement("return jdbcClient.sql($L).query($L).stream()",
                    constant, queryArg(entity, mapperType));
            return;
        }
        // Materialize before streaming so the (lazy) stream's elements are all hydrated up front.
        builder.addStatement("$T<$T> results = jdbcClient.sql($L).query($L).list()",
                List.class, entity.typeName(), constant, queryArg(entity, mapperType));
        builder.addStatement("$L", hydrationCall(entity, "results"));
        builder.addStatement("return results.stream()");
    }

    private void emitFindAllPage(MethodSpec.Builder builder, List<? extends VariableElement> parameters,
                                 EntityModel entity, Map<String, String> constants, ClassName mapperType,
                                 boolean hydrate) {
        String pageRequest = parameterName(parameters.get(0), "pageRequest");
        String sortBy = parameterName(parameters.get(1), "sortBy");
        String findConstant = addConstant(constants, "SQL_FIND_ALL",
                new SqlBuilder().selectBy(entity.metadata(), List.of(), List.of(), 0, null));
        String countConstant = addConstant(constants, "SQL_COUNT_ALL",
                new SqlBuilder().countBy(entity.metadata(), List.of()));
        // R-T11c: a runtime sort can traverse a relation and splice a LEFT JOIN onto this constant —
        // if the joined table shares a column name (entity targets always share "id") the bare select
        // list becomes ambiguous. When the descriptor has ANY traversable prop, register a second,
        // ROOT-TABLE-QUALIFIED find constant (Task 9's joined method-find shape) and pick it at
        // runtime when joins are non-empty; the labels are unchanged by qualification, so the same
        // row mapper reads both. Relation-free descriptors keep the single-constant emission
        // byte-identical (legacy parity).
        boolean joinCapable = false;
        for (Prop p : entity.metadata().props()) {
            if (isTraversable(p)) {
                joinCapable = true;
                break;
            }
        }
        String joinedFindConstant = joinCapable
                ? addConstant(constants, "SQL_FIND_ALL_QUALIFIED",
                        SqlBuilder.qualifiedRootSelect(entity.metadata(), List.of(), true, ""))
                : null;

        String entityFqn = entity.typeName().toString();
        ClassName metadataClass = metadataTypes.get(entityFqn);
        if (metadataClass == null) {
            metadataClass = embeddedMetadataName(entityFqn);
        }
        String rootTable = entity.metadata().tableName();

        builder.addStatement("$T cols = new $T<>()", List.class, ArrayList.class);
        builder.addStatement("$T<$T> dirs = new $T<>()", List.class, Boolean.class, ArrayList.class);
        builder.addStatement("$T props = new $T<>()", List.class, ArrayList.class);
        builder.addStatement("$T<$T> refs = new $T<>()", List.class, MD_COLUMN_REF, ArrayList.class);
        builder.addStatement("$T orderBy = new $T()", StringBuilder.class, StringBuilder.class);
        if (joinCapable) {
            builder.addStatement("boolean keyed = false");
        }
        builder.beginControlFlow("for ($T<?> s : $L)", Sort.class, sortBy);
        builder.addStatement("$T r = resolveRef(s.property())", MD_COLUMN_REF);
        builder.addStatement("cols.add(r.column())");
        builder.addStatement("refs.add(r)");
        builder.addStatement("dirs.add(s.isAscending())");
        builder.addStatement("props.add(s.property())");
        builder.beginControlFlow("if (orderBy.length() > 0)");
        builder.addStatement("orderBy.append($S)", ", ");
        builder.endControlFlow();
        if (joinCapable) {
            builder.beginControlFlow("if ($T.isToManyHead($T.INSTANCE, r))", SqlBuilder.class, metadataClass);
            builder.addStatement("keyed = true");
            builder.endControlFlow();
            builder.addStatement("orderBy.append($T.isToManyHead($T.INSTANCE, r)"
                            + " ? $T.aggregateSortTerm($T.INSTANCE, r, s.isAscending())"
                            + " : $T.qualify(r, $S)).append(s.isAscending() ? $S : $S)",
                    SqlBuilder.class, metadataClass, SqlBuilder.class, metadataClass,
                    SqlBuilder.class, rootTable, " ASC", " DESC");
            builder.endControlFlow();
            builder.beginControlFlow("if (keyed && !(dirs.size() > 0"
                            + " && Boolean.TRUE.equals(dirs.get(dirs.size() - 1))"
                            + " && $S.equals(props.get(props.size() - 1))))", entity.id().name());
            builder.addStatement("orderBy.append($S)", ", " + Names.quote(rootTable) + "."
                    + Names.quote(entity.metadata().idColumn()) + " ASC");
            builder.addStatement("cols.add($S)", entity.metadata().idColumn());
            builder.addStatement("dirs.add(true)");
            builder.addStatement("props.add($S)", entity.id().name());
            builder.addStatement("refs.add(resolveRef($S))", entity.id().name());
            builder.endControlFlow();
        } else {
            builder.addStatement("orderBy.append($T.qualify(r, $S)).append(s.isAscending() ? $S : $S)",
                    SqlBuilder.class, rootTable, " ASC", " DESC");
            builder.endControlFlow();
        }
        builder.addStatement("$T joins = $T.joinClause($T.INSTANCE, refs)",
                String.class, SqlBuilder.class, metadataClass);

        builder.beginControlFlow("if ($L.mode() == $T.Mode.OFFSET)", pageRequest, PageRequest.class);
        builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
        if (joinCapable) {
            builder.addStatement("$T<$T> content = jdbcClient.sql(((joins.isEmpty() && !keyed ? $L : $L) + joins"
                            + " + (orderBy.length() > 0 ? $S + orderBy : $S)"
                            + " + $S + $L.size() + $S + offset)).query($L).list()",
                    List.class, entity.typeName(), findConstant, joinedFindConstant, " ORDER BY ", "",
                    " LIMIT ", pageRequest, " OFFSET ", queryArg(entity, mapperType));
        } else {
            builder.addStatement("$T<$T> content = jdbcClient.sql($L + joins + (orderBy.length() > 0 ? $S + orderBy : $S)"
                            + " + $S + $L.size() + $S + offset).query($L).list()",
                    List.class, entity.typeName(), findConstant, " ORDER BY ", "", " LIMIT ", pageRequest,
                    " OFFSET ", queryArg(entity, mapperType));
        }
        builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                pageRequest, countConstant, Long.class);
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, pageRequest);
        builder.endControlFlow();

        builder.addStatement("$T.Cursor cursor = $L.cursor().orElseThrow()", PageRequest.class, pageRequest);
        if (joinCapable) {
            // Parked Task-7 finding M-1: the seek predicate must follow the same qualification rule as
            // the select list — an unqualified "id" in the WHERE is exactly as ambiguous as in the
            // projection once a join is spliced. Terms are rendered verbatim (already quoted) through
            // the new CursorSupport.seekWhereTerms; joins.isEmpty() keeps the bare-column shape, so a
            // join-free cursor read stays byte-identical to the old emission.
            builder.addStatement("$T<$T> terms = new $T<>()", List.class, String.class, ArrayList.class);
            builder.beginControlFlow("for (int i = 0; i < refs.size(); i++)");
            builder.addStatement("$T r = refs.get(i)", MD_COLUMN_REF);
            builder.beginControlFlow("if ($T.isToManyHead($T.INSTANCE, r))", SqlBuilder.class, metadataClass);
            builder.addStatement("terms.add($T.aggregateSortTerm($T.INSTANCE, r, dirs.get(i)))",
                    SqlBuilder.class, metadataClass);
            builder.nextControlFlow("else if (joins.isEmpty() && !keyed)");
            builder.addStatement("terms.add($T.quote(r.column()))", Names.class);
            builder.nextControlFlow("else");
            builder.addStatement("terms.add($T.qualify(r, $S))", SqlBuilder.class, rootTable);
            builder.endControlFlow();
            builder.endControlFlow();
            builder.addStatement("$T where = $T.seekWhereTerms(terms, dirs, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                    String.class, CursorSupport.class, pageRequest, PageRequest.class);
            builder.addStatement("$T<$T> content = jdbcClient.sql(((joins.isEmpty() && !keyed ? $L : $L) + joins + $S + where"
                            + " + (orderBy.length() > 0 ? $S + orderBy : $S) + $S + $L.size() + $S))"
                            + ".params($T.cursorParams(cursor)).query($L).list()",
                    List.class, entity.typeName(), findConstant, joinedFindConstant, " WHERE ", " ORDER BY ", "",
                    " LIMIT ", pageRequest, " + 1", CursorSupport.class, queryArg(entity, mapperType));
        } else {
            builder.addStatement("$T where = $T.seekWhere(cols, dirs, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                    String.class, CursorSupport.class, pageRequest, PageRequest.class);
            builder.addStatement("$T<$T> content = jdbcClient.sql($L + joins + $S + where + (orderBy.length() > 0 ? $S + orderBy : $S)"
                            + " + $S + $L.size() + $S).params($T.cursorParams(cursor)).query($L).list()",
                    List.class, entity.typeName(), findConstant, " WHERE ", " ORDER BY ", "", " LIMIT ", pageRequest,
                    " + 1", CursorSupport.class, queryArg(entity, mapperType));
        }
        builder.addStatement("boolean hasMore = content.size() > $L.size()", pageRequest);
        builder.beginControlFlow("if (hasMore)");
        builder.addStatement("content = content.subList(0, $L.size())", pageRequest);
        builder.endControlFlow();
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor(props, dirs, $T.INSTANCE, e))",
                CursorSupport.class, metadataClass);
        builder.endControlFlow();
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
    }

    /** The single {@code "qualifier"."column"} rendering shared by every qualified projection emission (S-1). */
    private static String qualifiedColumn(String qualifier, String column) {
        return Names.quote(qualifier) + "." + Names.quote(column);
    }

    /**
     * The single-source NOTE texts for relation-path resolution failures (S-1 vocabulary). The
     * compile-side {@link #resolveCompilePaths} reporting and the runtime-mirror messages must render
     * these strings verbatim; the diagnostics pins keep their own literal copies as the byte guard,
     * so changing a constant here is a user-visible drift.
     */
    static final class ResolutionVocabulary {
        /** Every resolution NOTE opens with the quoted property path. */
        static final String PATH_PREFIX = "path '";

        /** Multi-hop traversal rejection: the text after the path. */
        static final String MULTI_HOP_SUFFIX =
                "' traverses more than one relation; not supported";

        /** Unresolvable relation target, predicate left unqualified: the text after the path. */
        static final String UNRESOLVED_SUFFIX =
                "' traverses a relation whose target could not be resolved; the predicate was left unqualified";

        static String multiHop(String path) {
            return PATH_PREFIX + path + MULTI_HOP_SUFFIX;
        }

        static String unresolved(String path) {
            return PATH_PREFIX + path + UNRESOLVED_SUFFIX;
        }

        private ResolutionVocabulary() {
        }
    }

    private void emitPaged(MethodSpec.Builder builder, EntityModel entity, String pageRequest, List<SortSpec> sorts,
                           String findConstant, Map<String, String> constants, List<Binding> bindings,
                           List<? extends VariableElement> parameters, boolean cursored, ClassName mapperType,
                           boolean hydrate) {
        List<SortSpec> effective = sorts.isEmpty()
                ? List.of(SortSpec.of(entity.id().name(), true, false))
                : sorts;
        String entityFqn = entity.typeName().toString();
        ClassName metadataClass = metadataTypes.get(entityFqn);
        if (metadataClass == null) {
            metadataClass = embeddedMetadataName(entityFqn);
        }
        List<String> columns = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        boolean toManySort = false;
        for (SortSpec s : effective) {
            String column;
            ColumnRef aggregateRef = null;
            try {
                column = SqlBuilder.resolveColumn(entity.metadata(), s.property());
            } catch (IllegalArgumentException unknown) {
                // Task 6: a sort property the flat column view cannot answer may still resolve through
                // the compile model to a TO-MANY head — that renders the aggregate subquery term (the
                // same string the runtime isToManyHead/aggregateSortTerm pair produces). Anything else
                // keeps the legacy rejection byte-for-byte.
                PathResolution resolution = resolveCompilePath(entity.metadata(), s.property());
                if (resolution.ref() == null || !resolution.existential()) {
                    throw unknown;
                }
                aggregateRef = resolution.ref();
                column = null;
                toManySort = true;
            }
            columns.add(column != null ? column : aggregateRef.column());
            properties.add(s.property());
            directions.add(Boolean.toString(s.ascending()));
            terms.add(column != null ? Names.quote(column)
                    : SqlBuilder.aggregateSortTerm(entity.metadata(), aggregateRef, s.ascending()));
        }
        if (toManySort && !("true".equals(directions.get(directions.size() - 1))
                && entity.id().name().equals(properties.get(properties.size() - 1)))) {
            // Keyset uniqueness (note 4): the aggregate edge is not unique per root row — append the
            // root-qualified id ASC term and grow props/dirs (and cols, index-aligned) with "id",
            // skipped only when the last sort already IS the ascending id.
            properties.add(entity.id().name());
            directions.add("true");
            columns.add(entity.metadata().idColumn());
            terms.add(Names.quote(entity.metadata().tableName()) + "."
                    + Names.quote(entity.metadata().idColumn()));
        }
        String colsExpr = "java.util.List.of(" + quoted(columns) + ")";
        String propsExpr = "java.util.List.of(" + quoted(properties) + ")";
        String dirsExpr = "java.util.List.of(" + String.join(", ", directions) + ")";
        String termsExpr = "java.util.List.of(" + quotedPreRendered(terms) + ")";
        String orderBy;
        if (toManySort) {
            StringBuilder ob = new StringBuilder(" ORDER BY ");
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) {
                    ob.append(", ");
                }
                ob.append(terms.get(i)).append("true".equals(directions.get(i)) ? " ASC" : " DESC");
            }
            orderBy = ob.toString();
        } else {
            orderBy = new SqlBuilder().orderBy(entity.metadata(), effective);
        }
        String wherePrefix = bindings.isEmpty() ? " WHERE " : " AND ";

        if (!cursored) {
            String countConstant = addConstant(constants, "SQL_COUNT_ALL",
                    new SqlBuilder().countBy(entity.metadata(), List.of()));
            builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
            CodeBlock.Builder call = CodeBlock.builder()
                    .add("jdbcClient.sql($L + $S + $S + $L.size() + $S + offset)", findConstant, orderBy,
                            " LIMIT ", pageRequest, " OFFSET ");
            appendBoundParameters(call, bindings, parameters);
            call.add(".query($L).list()", queryArg(entity, mapperType));
            builder.addStatement("$T<$T> content = $L", List.class, entity.typeName(), call.build());
            builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                    pageRequest, countConstant, Long.class);
            if (hydrate) {
                builder.addStatement("$L", hydrationCall(entity, "content"));
            }
            builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, pageRequest);
            return;
        }

        builder.addStatement("$T.Cursor cursor = $L.cursor().orElse(null)", PageRequest.class, pageRequest);
        builder.beginControlFlow("if (cursor == null)");
        builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
        CodeBlock.Builder offsetCall = CodeBlock.builder()
                .add("jdbcClient.sql($L + $S + $S + $L.size() + $S + offset)", findConstant, orderBy,
                        " LIMIT ", pageRequest, " OFFSET ");
        appendBoundParameters(offsetCall, bindings, parameters);
        offsetCall.add(".query($L).list()", queryArg(entity, mapperType));
        builder.addStatement("$T<$T> content = $L", List.class, entity.typeName(), offsetCall.build());
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor($L, $L, $T.INSTANCE, e))",
                CursorSupport.class, propsExpr, dirsExpr, metadataClass);
        builder.endControlFlow();
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
        builder.endControlFlow();
        if (toManySort) {
            builder.addStatement("$T where = $T.seekWhereTerms($L, $L, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                    String.class, CursorSupport.class, termsExpr, dirsExpr, pageRequest, PageRequest.class);
        } else {
            builder.addStatement("$T where = $T.seekWhere($L, $L, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                    String.class, CursorSupport.class, colsExpr, dirsExpr, pageRequest, PageRequest.class);
        }
        CodeBlock.Builder seekCall = CodeBlock.builder()
                .add("jdbcClient.sql($L + $S + where + $S + $S + $L.size() + $S)", findConstant, wherePrefix,
                        orderBy, " LIMIT ", pageRequest, " + 1");
        appendBoundParameters(seekCall, bindings, parameters);
        seekCall.add(".params($T.cursorParams(cursor)).query($L).list()", CursorSupport.class,
                queryArg(entity, mapperType));
        builder.addStatement("$T<$T> content = $L", List.class, entity.typeName(), seekCall.build());
        builder.addStatement("boolean hasMore = content.size() > $L.size()", pageRequest);
        builder.beginControlFlow("if (hasMore)");
        builder.addStatement("content = content.subList(0, $L.size())", pageRequest);
        builder.endControlFlow();
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor($L, $L, $T.INSTANCE, e))",
                CursorSupport.class, propsExpr, dirsExpr, metadataClass);
        builder.endControlFlow();
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
    }

    private String quoted(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(v).append('"');
        }
        return sb.toString();
    }

    /** Like {@link #quoted} but for PRE-RENDERED terms whose text carries embedded double quotes
     *  (aggregate sort terms, root-qualified id terms): each {@code "} is escaped for the emitted
     *  Java string literal so the generated source round-trips the term verbatim. */
    private static String quotedPreRendered(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        }
        return sb.toString();
    }

    /**
     * Emits the private {@code resolveRef(String)} helper the paginated {@code findAll} uses to map a
     * runtime {@link Sort} property (bare name or embedded dot path, e.g. {@code "address.city"}) to a
     * physical {@code ColumnRef}. Resolution delegates to the descriptor ({@code <Entity>Metadata.columnRef}),
     * so a relation-traversing path carries the {@code JoinRef}s the paging code turns into a
     * {@code LEFT JOIN} chain, while an embedded leaf stays a plain local column (no join). Unknown
     * properties throw, as the prior string-switch helper did.
     */
    private MethodSpec resolveRefHelper(EntityModel entity) {
        String entityFqn = entity.typeName().toString();
        ClassName metadataType = metadataTypes.get(entityFqn);
        if (metadataType == null) {
            metadataType = embeddedMetadataName(entityFqn);
        }
        return MethodSpec.methodBuilder("resolveRef")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(MD_COLUMN_REF)
                .addParameter(String.class, "property")
                .addStatement("return $T.INSTANCE.columnRef($L)", metadataType, "property")
                .build();
    }

    // ------------------------------------------------------------------ @Query

    private void emitQuery(MethodSpec.Builder builder, ExecutableElement method, Query query, TypeMirror returnMirror,
                           EntityModel entity, Map<String, String> constants) {
        String name = method.getSimpleName().toString();
        String constant = addConstant(constants, "SQL_" + upperSnake(name), query.value());
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        List<? extends VariableElement> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            Param param = parameters.get(i).getAnnotation(Param.class);
            if (param != null) {
                sql.add(".param($S, $L)", param.value(), parameterName(parameters.get(i), "arg" + i));
            }
        }
        Kind kind = kindOf(returnMirror);
        TypeName queryType = queryResultType(returnMirror, entity.typeName());
        terminal(sql, kind, CodeBlock.of("$T.class", queryType));
        if (kind == Kind.VOID) {
            builder.addStatement("$L", sql.build());
        } else {
            builder.addStatement("return $L", sql.build());
        }
    }

    // ------------------------------------------------------------- find / by-method-name

    private void emitFind(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                          EntityModel entity, Map<String, String> constants, ClassName mapperType,
                          boolean hydrate, List<MethodSpec> auxMethods,
                          List<ResolvedQueryModel> queryModels, Map<String, Integer> queryModelNames) {
        String name = method.getSimpleName().toString();
        ParsedMethodName parsed = tryParse(name);
        String action = parsed != null ? parsed.action() : "find";
        List<? extends VariableElement> parameters = method.getParameters();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        List<Binding> bindings = bindings(parameters, parameterTypes, parsed);
        TypeMirror returnMirror = resolved.getReturnType();
        Kind kind = kindOf(returnMirror);
        String queryModelName = capitalize(name) + "QueryModel";
        int nameClash = queryModelNames.merge(queryModelName, 1, Integer::sum);
        if (nameClash > 1) {
            queryModelName = queryModelName + nameClash; // overload collision: declaration-order suffix
        }

        List<Predicate> predicates = predicates(bindings, parameters);
        // Task 8: resolve every method-name predicate property through the compile model, so a path
        // that traverses a relation (Reference / EntityList / ElementList) resolves at compile time,
        // a to-one head contributing a LEFT JOIN and (Task 5) a to-many head an EXISTS semi-join.
        // Local and embedded paths resolve to plain unqualified refs.
        List<PathResolution> predicatePaths = resolveCompilePaths(entity.metadata(),
                predicates.stream().map(Predicate::property).toList(), method);
        List<ColumnRef> predicateRefs = predicatePaths.stream().map(PathResolution::ref).toList();
        List<Boolean> existentialPredicates = predicatePaths.stream().map(PathResolution::existential).toList();

        if ("count".equals(action) || "exists".equals(action)) {
            Operation countOperation = "exists".equals(action) ? Operation.EXISTS : Operation.COUNT;
            queryModels.add(queryModelOf(queryModelName, countOperation, kind, entity.metadata(),
                    predicates, predicateRefs, existentialPredicates, List.of(), List.of(), List.of(),
                    0, bindings, parameters));
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().build(entity.metadata(),
                            queryModels.get(queryModels.size() - 1)));
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            appendBoundParameters(call, bindings, parameters);
            call.add(".singleValue($T.class)", Long.class);
            if ("exists".equals(action)) {
                builder.addStatement("return $L > 0", call.build());
            } else if (kind == Kind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
            return;
        }

        if ("delete".equals(action)) {
            // C-1: a delete whose predicate traverses a relation renders correlated EXISTS
            // semi-joins through SqlBuilder.build's delete mode — the old NOTE-degrade to the join-free
            // legacy predicate could match the wrong rows, and a joined DELETE ... LEFT JOIN is
            // invalid SQL. Multi-hop paths are NOTE-rejected by resolveCompilePaths BEFORE this
            // point (their refs stay unjoined, so they render legacy and emit no delete NOTE).
            queryModels.add(queryModelOf(queryModelName, Operation.DELETE, kind, entity.metadata(),
                    predicates, predicateRefs, existentialPredicates, List.of(), List.of(), List.of(),
                    0, bindings, parameters));
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().build(entity.metadata(),
                            queryModels.get(queryModels.size() - 1)));
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            appendBoundParameters(call, bindings, parameters);
            call.add(".update()");
            if (kind == Kind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
            return;
        }

        List<SortSpec> methodSorts = sorts(method, parsed);
        if (kind == Kind.CURSORED_PAGE || kind == Kind.PAGE) {
            String pageRequest = specialParameter(parameters, parameterTypes, PAGE_REQUEST);
            if (pageRequest == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        name + " returning " + (kind == Kind.PAGE ? "Page" : "CursoredPage")
                                + " requires a PageRequest parameter", method);
                return;
            }
            // Task 9: full materialisation through a joined read is not supported for paged finders —
            // the runtime sort/seek wiring appends its own join chain to the base constant. A paged
            // finder whose compile-time predicates traverse a Reference is degraded to the normal mapper
            // (association fields stay id-only partials read by the base row mapper), with a NOTE.
            if (!collectTraversedReferences(entity.metadata(), predicateRefs, List.of()).isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        name + ": full materialisation through joins is not supported for paged finders;"
                                + " association fields materialize as id-only partials", method);
            }
            // The base constant carries the compile-time predicates (join-aware); method-name sort
            // paths are resolved per-emit in emitPaged (local / to-one qualified / to-many
            // aggregate — Task 6), so no sort refs are baked into this constant.
            List<PathResolution> pagedSortPaths = methodSorts.stream()
                    .map(s -> resolveCompilePath(entity.metadata(), s.property()))
                    .toList();
            List<ColumnRef> pagedSortRefs = new ArrayList<>();
            List<Boolean> pagedExistentialSorts = new ArrayList<>();
            for (int i = 0; i < pagedSortPaths.size(); i++) {
                // NOTE-free resolution: emitPaged rejects a multi-hop sort path right after this point,
                // so the model degrades a ref-less rejection to the legacy local ref instead of
                // pre-emitting a second diagnostic (and never serialises a null ref).
                ColumnRef ref = pagedSortPaths.get(i).ref();
                pagedSortRefs.add(ref == null ? legacyColumnRef(methodSorts.get(i).property()) : ref);
                pagedExistentialSorts.add(ref != null && pagedSortPaths.get(i).existential());
            }
            queryModels.add(queryModelOf(queryModelName, Operation.FIND, kind, entity.metadata(),
                    predicates, predicateRefs, existentialPredicates, methodSorts,
                    pagedSortRefs, pagedExistentialSorts, 0, bindings, parameters));
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().build(entity.metadata(),
                            queryModels.get(queryModels.size() - 1)));
            emitPaged(builder, entity, pageRequest, methodSorts, constant, constants, bindings, parameters,
                    kind == Kind.CURSORED_PAGE, mapperType, hydrate);
            return;
        }
        List<PathResolution> sortPaths = resolveCompilePaths(entity.metadata(),
                methodSorts.stream().map(SortSpec::property).toList(), method);
        List<ColumnRef> sortRefs = sortPaths.stream().map(PathResolution::ref).toList();
        List<Boolean> existentialSorts = sortPaths.stream().map(PathResolution::existential).toList();

        // Task 9 (join-returns-all-fields): when a method-name predicate/sort path traversed a Reference,
        // the SELECT carries the target's aliased flattened columns and the read uses a generated
        // per-method full mapper. Only for non-paged entity-returning finders with a generated row
        // mapper to delegate the root columns to; otherwise the projection stays root-only (partial).
        List<Prop.Reference> traversed = collectTraversedReferences(entity.metadata(), predicateRefs, sortRefs);
        boolean fullMaterialisation = !traversed.isEmpty() && mapperType != null
                && "find".equals(action) && kind != Kind.VOID;
        String extraProjection = fullMaterialisation ? joinedProjection(entity.metadata(), traversed) : "";
        CodeBlock queryArgForMethod = queryArg(entity, mapperType);
        if (fullMaterialisation) {
            String fullMapper = "mapRow_full_" + Names.toSnakeCase(name);
            auxMethods.add(buildFullMapper(fullMapper, entity, mapperType, traversed));
            queryArgForMethod = CodeBlock.of("(rs, rowNum) -> $L(rs, rowNum)", fullMapper);
        }
        queryModels.add(queryModelOf(queryModelName, Operation.FIND, kind, entity.metadata(),
                predicates, predicateRefs, existentialPredicates,
                methodSorts, sortRefs, existentialSorts, parsed != null ? parsed.limit() : 0,
                bindings, parameters));
        ResolvedQueryModel queryModel = queryModels.get(queryModels.size() - 1);
        String sql = new SqlBuilder().build(entity.metadata(), queryModel, extraProjection);
        String constant = addConstant(constants, "SQL_" + upperSnake(name), sql);
        CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendBoundParameters(call, bindings, parameters);
        if (!hydrate || kind == Kind.VOID) {
            terminal(call, kind, queryArgForMethod);
            if (kind == Kind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
            return;
        }
        // Hydrated read shapes: land the query result in a local, run the umbrella loader, then return.
        switch (kind) {
            case LIST, STREAM -> {
                call.add(".query($L).list()", queryArgForMethod);
                builder.addStatement("$T<$T> results = $L", List.class, entity.typeName(), call.build());
                builder.addStatement("$L", hydrationCall(entity, "results"));
                builder.addStatement(kind == Kind.STREAM ? "return results.stream()" : "return results");
            }
            case OPTIONAL -> {
                call.add(".query($L).optional()", queryArgForMethod);
                builder.addStatement("$T<$T> found = $L", java.util.Optional.class, entity.typeName(), call.build());
                builder.addStatement("found.ifPresent(e -> $L)", hydrationCall(entity, "java.util.List.of(e)"));
                builder.addStatement("return found");
            }
            case SINGLE -> {
                call.add(".query($L).single()", queryArgForMethod);
                builder.addStatement("$T found = $L", entity.typeName(), call.build());
                builder.addStatement("$L", hydrationCall(entity, "java.util.List.of(found)"));
                builder.addStatement("return found");
            }
            default -> {
                terminal(call, kind, queryArgForMethod);
                builder.addStatement("$L", call.build());
            }
        }
    }

    /**
     * The distinct {@link Prop.Reference} heads whose resolved predicate/sort refs carry a join (i.e. the
     * path traversed a Reference rather than an {@code EntityList}/{@code ElementList}). A joined ref's
     * path is {@code <head>.<leafPath>} (set by {@code withPrefix}); the head names the traversed
     * property. Composition and association references both qualify — the field is filled from the join
     * either way. Deduped by property name so one traversal of a shared head yields one projection.
     */
    private List<Prop.Reference> collectTraversedReferences(DataModel md, List<ColumnRef> predicateRefs,
                                                            List<ColumnRef> sortRefs) {
        List<Prop.Reference> out = new ArrayList<>();
        List<ColumnRef> all = new ArrayList<>(predicateRefs);
        all.addAll(sortRefs);
        for (ColumnRef ref : all) {
            if (ref.joins().isEmpty()) {
                continue;
            }
            String path = ref.path();
            int dot = path.indexOf('.');
            String head = dot < 0 ? path : path.substring(0, dot);
            Prop prop = propOrNull(md, head);
            if (prop instanceof Prop.Reference reference) {
                boolean seen = out.stream().anyMatch(r -> r.name().equals(reference.name()));
                if (!seen) {
                    out.add(reference);
                }
            }
        }
        return out;
    }

    /**
     * The extra SELECT fragments a joined full-materialisation read needs: every flattened column of
     * each traversed target EXCEPT its own references (nested associations stay null, depth-1), qualified
     * by the join alias and re-labelled {@code <alias>_join_<column>} so the read is unambiguous against
     * the root's own columns. Emitted in the target's {@code columns()} declaration order.
     */
    private String joinedProjection(DataModel md, List<Prop.Reference> traversed) {
        StringBuilder sb = new StringBuilder();
        for (Prop.Reference ref : traversed) {
            TypeElement target = elements.getTypeElement(ref.targetTypeName());
            if (target == null) {
                continue;
            }
            EntityModel targetModel = entityModel(target);
            for (ColumnRef column : targetModel.metadata().columns()) {
                String head = column.path().contains(".")
                        ? column.path().substring(0, column.path().indexOf('.')) : column.path();
                if (propOrNull(targetModel.metadata(), head) instanceof Prop.Reference) {
                    continue; // nested association fk not selected — its target stays null (depth-1)
                }
                String alias = qualifiedColumn(ref.name(), column.column());
                String label = ref.name() + "_join_" + column.column();
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(alias).append(" AS ").append(Names.quote(label));
            }
        }
        return sb.toString();
    }

    /**
     * Generates the per-method full mapper {@code mapRow_full_<method>}: it delegates the root columns to
     * the entity's generated row mapper (which already materialises non-traversed associations as
     * id-only partials) and then OVERWRITES each traversed association with a fully-populated instance
     * read from the {@code <alias>_join_<column>} labels. Primitive leaves use the temp+wasNull rule.
     * Reflection-free (Java 17 instanceof-chain).
     */
    private MethodSpec buildFullMapper(String mapperName, EntityModel entity, ClassName mapperType,
                                       List<Prop.Reference> traversed) {
        TypeName entityType = entity.typeName();
        MethodSpec.Builder m = MethodSpec.methodBuilder(mapperName)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(entityType)
                .addParameter(ClassName.get("java.sql", "ResultSet"), "rs")
                .addParameter(int.class, "rowNum")
                .addException(ClassName.get("java.sql", "SQLException"))
                .addStatement("$T entity = new $T().mapRow(rs, rowNum)", entityType, mapperType);
        for (Prop.Reference ref : traversed) {
            TypeElement target = elements.getTypeElement(ref.targetTypeName());
            if (target == null) {
                continue;
            }
            EntityModel targetModel = entityModel(target);
            if (targetModel.id() == null) {
                continue;
            }
            TypeName idType = targetModel.id().type().box();
            String idLabel = ref.name() + "_join_" + targetModel.id().column();
            String idLocal = ref.name() + "_join_id";
            m.addStatement("$T $L = rs.getObject($S, $T.class)", idType, idLocal, idLabel, idType);
            m.beginControlFlow("if ($L != null)", idLocal);
            String var = ref.name() + "Full";
            m.addStatement("$T $L = new $T()", targetModel.typeName(), var, targetModel.typeName());
            m.addStatement("$L.$L($L)", var, setter(targetModel.id().name()), idLocal);
            appendJoinedTargetLeaves(m, targetModel, ref.name(), var, entity.typeName().toString());
            m.addStatement("entity.$L($L)", setter(ref.name()), var);
            m.endControlFlow();
        }
        m.addStatement("return entity");
        return m.build();
    }

    /**
     * Fills the scalar and embedded-leaf columns of a joined target (its id was already set by the
     * caller). References in the target are skipped (nested associations stay null — depth-1) and the
     * target's own id is skipped (already assigned). Reads use {@code <alias>_join_<column>} labels,
     * exactly matching {@link #joinedProjection}; primitives follow the temp+wasNull rule.
     */
    private void appendJoinedTargetLeaves(MethodSpec.Builder m, EntityModel vo, String alias, String var,
                                          String ownerName) {
        for (Property p : vo.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendJoinedEmbedded(m, p, alias, var, ownerName);
                continue;
            }
            String label = alias + "_join_" + p.column();
            if (!p.type().isPrimitive()) {
                m.addStatement("$L.$L(rs.getObject($S, $T.class))", var, setter(p.name()), label, p.type().box());
                continue;
            }
            String temp = alias + "_" + p.column() + "Value";
            m.addStatement("$T $L = rs.$L($S)", p.type(), temp, primitiveGetter(p.type()), label);
            m.beginControlFlow("if (rs.wasNull())");
            m.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                    "NULL value in non-nullable primitive column \"" + label + "\" mapped to "
                            + ownerName + "." + alias + "." + p.name());
            m.endControlFlow();
            m.addStatement("$L.$L($L)", var, setter(p.name()), temp);
        }
    }

    /** Reconstructs one embedded value-object of a joined target from its aliased leaf labels (per-subtree presence). */
    private void appendJoinedEmbedded(MethodSpec.Builder m, Property embedded, String alias, String var,
                                      String ownerName) {
        EntityModel vo = embedded.embeddedModel();
        if (vo == null) {
            return;
        }
        String childVar = var + capitalize(embedded.name());
        List<String> presence = new ArrayList<>();
        readJoinedEmbeddedLeaves(m, vo, alias + "_join_" + embedded.column(), childVar, presence,
                ownerName + "." + alias + "." + embedded.name());
        boolean guarded = !presence.isEmpty();
        if (guarded) {
            m.beginControlFlow("if ($L)", String.join(" || ", presence));
        }
        buildJoinedEmbeddedValue(m, vo, alias + "_join_" + embedded.column(), childVar);
        m.addStatement("$L.$L($L)", var, setter(embedded.name()), childVar);
        if (guarded) {
            m.endControlFlow();
        }
    }

    private void readJoinedEmbeddedLeaves(MethodSpec.Builder m, EntityModel vo, String prefix, String local,
                                          List<String> presence, String path) {
        for (Property p : vo.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue;
            }
            if (p.prop() instanceof Prop.Embedded) {
                readJoinedEmbeddedLeaves(m, p.embeddedModel(), prefix + "_" + p.column(),
                        local + capitalize(p.name()), presence, path + "." + p.name());
                continue;
            }
            String leafLocal = prefix + "_" + p.column();
            if (!p.type().isPrimitive()) {
                m.addStatement("$T $L = rs.getObject($S, $T.class)", p.type().box(), leafLocal, leafLocal,
                        p.type().box());
                presence.add(leafLocal + " != null");
                continue;
            }
            m.addStatement("$T $L = rs.$L($S)", p.type(), leafLocal, primitiveGetter(p.type()), leafLocal);
            m.beginControlFlow("if (rs.wasNull())");
            m.addStatement("throw new $T($S)", ClassName.get("java.sql", "SQLException"),
                    "NULL value in non-nullable primitive column \"" + leafLocal + "\" mapped to " + path);
            m.endControlFlow();
        }
    }

    private void buildJoinedEmbeddedValue(MethodSpec.Builder m, EntityModel vo, String prefix, String local) {
        m.addStatement("$T $L = new $T()", vo.typeName(), local, vo.typeName());
        for (Property p : vo.properties()) {
            if (p.prop() instanceof Prop.Reference || p.prop() instanceof Prop.Embedded) {
                continue;
            }
            m.addStatement("$L.$L($L)", local, setter(p.name()), prefix + "_" + p.column());
        }
        for (Property p : vo.properties()) {
            if (!(p.prop() instanceof Prop.Embedded)) {
                continue;
            }
            String childLocal = local + capitalize(p.name());
            List<String> terms = new ArrayList<>();
            collectPresenceTerms(p.embeddedModel(), prefix + "_" + p.column(), terms);
            if (terms.isEmpty()) {
                buildJoinedEmbeddedValue(m, p.embeddedModel(), prefix + "_" + p.column(), childLocal);
                m.addStatement("$L.$L($L)", local, setter(p.name()), childLocal);
            } else {
                m.beginControlFlow("if ($L)", String.join(" || ", terms));
                buildJoinedEmbeddedValue(m, p.embeddedModel(), prefix + "_" + p.column(), childLocal);
                m.addStatement("$L.$L($L)", local, setter(p.name()), childLocal);
                m.endControlFlow();
            }
        }
    }

    /**
     * The outcome of resolving a method-name property path against a {@link DataModel}. A
     * resolved {@code ref} is either a plain local column (empty {@code joins}) or a relation-traversing
     * column carrying exactly one {@link JoinRef}; {@code multiHop} flags a path that would need two or
     * more joins (rejected with a NOTE), and {@code unresolvedRelation} flags a path whose relation head
     * names a target that could not be resolved (degraded with a NOTE). Both degrade the caller back to
     * the legacy unqualified rendering; neither aborts the compile. {@code existential} marks a ref
     * whose single head is a to-many {@link Prop.EntityList}/{@link Prop.ElementList}: the caller
     * renders it as an {@link SqlBuilder#existsConjunct(EntitySchema, Predicate, ColumnRef) EXISTS
     * semi-join} conjunct and splices NO join.
     */
    private record PathResolution(ColumnRef ref, boolean multiHop, boolean unresolvedRelation,
                                  boolean existential) {
        static PathResolution resolved(ColumnRef ref) { return new PathResolution(ref, false, false, false); }
        static PathResolution existentialRef(ColumnRef ref) { return new PathResolution(ref, false, false, true); }
        static PathResolution unresolved() { return new PathResolution(null, false, false, false); }
        static PathResolution tooManyRelations() { return new PathResolution(null, true, false, false); }
        static PathResolution relationUnresolved() { return new PathResolution(null, false, true, false); }
    }

    /**
     * Resolves every property in {@code paths} against {@code md}, reporting the multi-hop and
     * unresolved-relation NOTEs on {@code method} and falling back to the legacy unqualified column
     * (identical to {@code SqlBuilder}'s old {@code Names.columnName} rendering) whenever the descriptor
     * cannot resolve the path. The returned resolutions line up index-for-index with {@code paths}.
     */
    private List<PathResolution> resolveCompilePaths(DataModel md, List<String> paths, ExecutableElement method) {
        List<PathResolution> out = new ArrayList<>();
        for (String path : paths) {
            PathResolution resolution = resolveCompilePath(md, path);
            if (resolution.multiHop()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        ResolutionVocabulary.multiHop(path), method);
                out.add(PathResolution.resolved(legacyColumnRef(path)));
            } else if (resolution.unresolvedRelation()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        ResolutionVocabulary.unresolved(path), method);
                out.add(PathResolution.resolved(legacyColumnRef(path)));
            } else if (resolution.ref() != null) {
                out.add(resolution);
            } else {
                out.add(PathResolution.resolved(legacyColumnRef(path)));
            }
        }
        return out;
    }

    /** The legacy fully-local ref: physical column via {@link Names#columnName}, no join, no table. */
    private static ColumnRef legacyColumnRef(String property) {
        return new ColumnRef(Names.columnName(property), property);
    }

    /**
     * Resolves a single method-name property to a {@link ColumnRef} through the compile model, using the
     * same algorithm as the runtime {@code EntityDescriptor.columnRef} but driven by the concatenated
     * camel path the {@code qbn} parser emits (which carries NO dots). So a head/remainder split happens
     * at camel-hump (and any literal {@code '.'}) boundaries rather than at a single dot. Order:
     * <ol>
     *   <li>local scan — a dots-stripped, case-insensitive match against each {@link ColumnRef#path()}
     *       (this keeps flat scalars and flattened embedded leaves byte-identical to today);</li>
     *   <li>relation descent — the shortest head that is a {@link Prop.Reference}/
     *       {@link Prop.EntityList}/{@link Prop.ElementList}, with the remainder resolved as a LEAF in the
     *       target (or against the element columns), contributing exactly one join.</li>
     * </ol>
     * A remainder that would itself need a relation yields {@link PathResolution#multiHop()}; a head whose
     * target cannot be resolved yields {@link PathResolution#relationUnresolved()}.
     */
    private PathResolution resolveCompilePath(DataModel md, String path) {
        ColumnRef local = localColumnRef(md.columns(), path);
        if (local != null) {
            return PathResolution.resolved(local);
        }
        for (String[] head : candidateHeads(path)) {
            Prop prop = propOrNull(md, head[0]);
            if (!isTraversable(prop)) {
                continue;
            }
            PathResolution inner = resolveRelationHead(md, prop, head[1]);
            if (inner.multiHop() || inner.unresolvedRelation() || inner.ref() != null) {
                return inner;
            }
        }
        return PathResolution.unresolved();
    }

    /**
     * Resolves {@code remainder} as a leaf under a relation {@code prop} and attaches the join edge the
     * traversal contributes. The alias is exactly the property name.
     */
    private PathResolution resolveRelationHead(DataModel md, Prop prop, String remainder) {
        if (prop instanceof Prop.ElementList elementList) {
            ColumnRef leaf = localColumnRef(elementList.elementColumns(), remainder);
            if (leaf == null) {
                return PathResolution.unresolved();
            }
            JoinRef join = new JoinRef(elementList.childTable(), elementList.name(),
                    md.idColumn(), elementList.parentFkColumn());
            return PathResolution.existentialRef(leaf.withPrefix(elementList.name(), join));
        }
        String targetFqn = prop instanceof Prop.Reference reference
                ? reference.targetTypeName() : ((Prop.EntityList) prop).targetTypeName();
        DataModel target = descriptorByFqn(targetFqn);
        if (target == null) {
            return PathResolution.relationUnresolved();
        }
        ColumnRef leaf = localColumnRef(target.columns(), remainder);
        if (leaf == null) {
            // Not a local leaf of the target — if the remainder names ANOTHER relation the path is
            // multi-hop; otherwise this head is simply not the right split, so keep searching.
            for (String[] deeper : candidateHeads(remainder)) {
                if (isTraversable(propOrNull(target, deeper[0]))) {
                    return PathResolution.tooManyRelations();
                }
            }
            return PathResolution.unresolved();
        }
        JoinRef join = referenceOrListJoin(md, target, prop);
        if (join == null) {
            return PathResolution.relationUnresolved();
        }
        ColumnRef traversed = leaf.withPrefix(prop.name(), join);
        return prop instanceof Prop.EntityList
                ? PathResolution.existentialRef(traversed)
                : PathResolution.resolved(traversed);
    }

    /** The LEFT JOIN edge for a Reference / EntityList traversal; {@code null} when unbuildable. */
    private static JoinRef referenceOrListJoin(DataModel root, DataModel target, Prop prop) {
        if (prop instanceof Prop.Reference reference) {
            if (reference.targetTable() == null || target.idColumn() == null) {
                return null;
            }
            return new JoinRef(reference.targetTable(), reference.name(),
                    reference.fkColumn(), target.idColumn());
        }
        if (prop instanceof Prop.EntityList list) {
            if (list.targetTable() == null || list.inverseFkColumn() == null) {
                return null;
            }
            return new JoinRef(list.targetTable(), list.name(), root.idColumn(), list.inverseFkColumn());
        }
        return null;
    }

    /** The compile-time descriptor for a relationship target FQN, or {@code null} if unmapped. */
    private DataModel descriptorByFqn(String fqn) {
        if (fqn == null) {
            return null;
        }
        String raw = stripGenerics(fqn);
        DataModel cached = descriptorCache.get(raw);
        if (cached != null) {
            return cached;
        }
        try {
            TypeElement te = elements.getTypeElement(raw);
            return te == null ? null : descriptorFor(te);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** First column whose dot-path (dots removed, case-insensitive) equals {@code path} (dots removed). */
    private static ColumnRef localColumnRef(List<ColumnRef> columns, String path) {
        String flat = path.replace(".", "");
        for (ColumnRef c : columns) {
            if (c.path().replace(".", "").equalsIgnoreCase(flat)) {
                return c;
            }
        }
        return null;
    }

    /**
     * All (head, remainder) splits at camel-hump and literal-{@code '.'} boundaries, shortest head
     * first. Each entry is {@code {head, remainder}}.
     */
    private static List<String[]> candidateHeads(String path) {
        List<String[]> out = new ArrayList<>();
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                out.add(new String[]{path.substring(0, i), path.substring(i + 1)});
            } else if (Character.isUpperCase(c)) {
                out.add(new String[]{path.substring(0, i), path.substring(i)});
            }
        }
        return out;
    }

    private static boolean isTraversable(Prop prop) {
        return prop instanceof Prop.Reference || prop instanceof Prop.EntityList || prop instanceof Prop.ElementList;
    }

    /**
     * {@link DataModel#prop(String)} without the throw: {@code null} for an unknown name. A
     * mid-path camel segment keeps its leading capital (e.g. {@code "Country"} in
     * {@code "customerCountryCode"}), so the decapitalized form is matched as a fallback — mirroring how
     * the {@code qbn} parser only decapitalizes the FIRST hump of the concatenated property string.
     */
    private static Prop propOrNull(DataModel md, String name) {
        Prop exact = propByExactName(md, name);
        if (exact != null) {
            return exact;
        }
        if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) {
            return null;
        }
        return propByExactName(md, Character.toLowerCase(name.charAt(0)) + name.substring(1));
    }

    private static Prop propByExactName(DataModel md, String name) {
        if (md.id() != null && md.id().name().equals(name)) {
            return md.id();
        }
        for (Prop p : md.props()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private String specialParameter(List<? extends VariableElement> parameters,
                                    List<? extends TypeMirror> parameterTypes, String erasedType) {
        for (int i = 0; i < parameters.size(); i++) {
            if (erasedType.equals(types.erasure(parameterTypes.get(i)).toString())) {
                return parameters.get(i).getSimpleName().toString();
            }
        }
        return null;
    }

    private List<Binding> bindings(List<? extends VariableElement> parameters, List<? extends TypeMirror> parameterTypes,
                                   ParsedMethodName parsed) {
        List<Integer> indices = nonSpecialIndices(parameterTypes);
        List<Binding> result = new ArrayList<>();
        boolean annotated = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        if (annotated) {
            for (int index : indices) {
                By by = parameters.get(index).getAnnotation(By.class);
                if (by == null) {
                    continue;
                }
                String property = By.ID.equals(by.value()) ? "id" : by.value();
                result.add(new Binding(property, operatorFor(parsed, property), index));
            }
        } else if (parsed != null) {
            List<Predicate> predicates = parsed.predicates();
            for (int i = 0; i < predicates.size(); i++) {
                int index = i < indices.size() ? indices.get(i) : i;
                Predicate predicate = predicates.get(i);
                result.add(new Binding(predicate.property(), predicate.operator(), index));
            }
        }
        return result;
    }

    /**
     * Converts the resolved parameter bindings into {@link Predicate}s whose named parameters are the
     * method's parameter names, so the SQL rendered by {@link SqlBuilder} uses exactly the tokens
     * bound by {@link #appendBoundParameters}.
     */
    private List<Predicate> predicates(List<Binding> bindings, List<? extends VariableElement> parameters) {
        List<Predicate> predicates = new ArrayList<>();
        for (Binding binding : bindings) {
            String param = parameters.get(binding.paramIndex()).getSimpleName().toString();
            predicates.add(Predicate.of(binding.property(), binding.operator(), param));
        }
        return predicates;
    }

    private void appendBoundParameters(CodeBlock.Builder call, List<Binding> bindings,
                                       List<? extends VariableElement> parameters) {
        for (Binding binding : bindings) {
            Operator operator = binding.operator();
            if (operator == Operator.IS_NULL || operator == Operator.IS_NOT_NULL
                    || operator == Operator.TRUE || operator == Operator.FALSE) {
                continue;
            }
            String param = parameters.get(binding.paramIndex()).getSimpleName().toString();
            call.add(".param($S, $L)", param, boundValue(operator, param));
        }
    }

    private CodeBlock boundValue(Operator operator, String param) {
        return switch (operator) {
            case CONTAINS -> CodeBlock.of("$S + $L + $S", "%", param, "%");
            case STARTS_WITH -> CodeBlock.of("$L + $S", param, "%");
            case ENDS_WITH -> CodeBlock.of("$S + $L", "%", param);
            default -> CodeBlock.of("$L", param);
        };
    }

    private void terminal(CodeBlock.Builder call, Kind kind, CodeBlock queryArg) {
        switch (kind) {
            case LIST -> call.add(".query($L).list()", queryArg);
            case STREAM -> call.add(".query($L).stream()", queryArg);
            case OPTIONAL -> call.add(".query($L).optional()", queryArg);
            case VOID -> call.add(".update()");
            case SINGLE -> call.add(".query($L).single()", queryArg);
        }
    }

    /**
     * Returns the argument passed to {@code JdbcClient.query(...)}: an instance of the compile-time
     * generated {@code <Entity>RowMapper} when one exists, otherwise the entity class for the
     * reflective mapper.
     */
    private CodeBlock queryArg(EntityModel entity, ClassName mapperType) {
        return mapperType != null
                ? CodeBlock.of("new $T()", mapperType)
                : CodeBlock.of("$T.class", entity.typeName());
    }

    // -------------------------------------------------------------- collection hydration (Task 3)

    /**
     * True iff the repository generates hydration: a reflection-free row mapper was emitted for the
     * entity AND at least one hydrator cleared its gate — an element collection past the VO row-mapper
     * gate (Task 3) or an entity relationship past the managed-target gate (Task 4, the widening this
     * predicate was extracted for). The relationship eligibility (managed target + generated target
     * mapper + resolved inverse) is decided by the caller; this predicate only joins the lists.
     * {@code entity} is unused and kept as the widening shape (relationship inspection lives on the
     * entity model, walked by the caller).
     */
    private static boolean hasGeneratedHydration(EntityModel entity, ClassName mapperType,
                                                 List<ElementCollection> hydratedCollections,
                                                 List<EntityRelationship> hydratedRelationships) {
        return mapperType != null && (!hydratedCollections.isEmpty() || !hydratedRelationships.isEmpty());
    }

    /**
     * The single hydration hook every read shape calls once its parents are materialized: the umbrella
     * {@code hydrateAll} dispatches to each collection loader. {@code parentsExpr} is either a
     * materialized local (a list of parents) or an inline {@code java.util.List.of(...)} for the
     * optional / single cases. Task 4 reuses this hook when many-to-one / one-to-many loaders join the
     * umbrella, so the wrapping stays in one place.
     */
    private static CodeBlock hydrationCall(EntityModel entity, String parentsExpr) {
        // `entity` is unused today (the hook is a bare hydrateAll call) but is kept in the signature as
        // the Task 4 widening shape, where per-relationship hydration may branch on the entity model.
        return CodeBlock.of("hydrateAll($L)", parentsExpr);
    }

    /**
     * Emits the per-entity umbrella: {@code private void hydrateAll(Collection<Entity> parents)} calling
     * each emitted {@code hydrate<Field>(parents)} loader. One method reached from every read shape keeps
     * the wiring sites free of per-collection / per-relationship detail. Task 4 adds the relationship
     * loaders (many-to-one, one-to-many) to the same dispatch.
     */
    private MethodSpec hydrationUmbrella(EntityModel entity, List<ElementCollection> hydrated,
                                         List<EntityRelationship> relationships) {
        MethodSpec.Builder umbrella = MethodSpec.methodBuilder("hydrateAll")
                .addJavadoc("Dispatches every generated hydration loader over the given parents. "
                        + "Hydration is depth-1: entities produced by a loader are not themselves hydrated.\n")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Collection.class),
                        entity.typeName()), "parents");
        for (ElementCollection c : hydrated) {
            umbrella.addStatement("hydrate$L(parents)", capitalize(c.field()));
        }
        for (EntityRelationship r : relationships) {
            umbrella.addStatement("hydrate$L(parents)", capitalize(r.attribute().name()));
        }
        return umbrella.build();
    }

    /**
     * Emits one element-collection loader (Task 3): {@code private void hydrate<Field>(Collection<Entity>
     * parents)}, selecting the VO's columns from the child table WHERE the parent fk IN the parent ids.
     * Delegates the whole shape to {@link #appendGroupedKeyHydration}, which the one-to-many loader
     * reuses; the emission is textually the Task 3 body, so a VO corpus's generated impl stays identical.
     */
    private MethodSpec appendCollectionHydration(Map<String, String> constants,
                                           EntityModel entity, ElementCollection collection, ClassName voMapper) {
        String selectConst = addConstant(constants, "SQL_SELECT_" + upperSnake(collection.field()),
                "SELECT " + quotedColumnList(collection) + " FROM " + Names.quote(collection.childTable())
                        + " WHERE " + Names.quote(collection.parentFk()) + " IN (");
        return appendGroupedKeyHydration(constants, entity, collection.field(), selectConst,
                collection.parentFk(), collection.valueObjectType(), voMapper);
    }

    /**
     * Emits one one-to-many loader (Task 4 / ruling: child's OWN table): {@code parents}' ids group the
     * TARGET's own physical columns — INCLUDING the inverse fk, which the anonymous pairing reads by
     * label — WHERE {@code <inverse>_id IN (...)}. The delegate row mapper is the target entity's
     * generated {@code <Target>RowMapper} (no VO involved), and the grouped list is assigned to the
     * parent's collection setter. The target's own relationships are NOT hydrated here (depth-1);
     * non-composing back-references on children materialized by this loader carry an id-only partial
     * when the child's row mapper resolves the FK, and composing references stay loader-filled.
     */
    private MethodSpec appendOneToManyHydration(Map<String, String> constants, EntityModel entity,
                                                EntityRelationship relationship) {
        Prop.EntityList a = (Prop.EntityList) relationship.attribute();
        EntityModel targetModel = relationship.targetModel();
        String inverseFk = Names.fkColumn(a.inverseName());
        String selectConst = addConstant(constants, "SQL_SELECT_" + upperSnake(a.name()),
                "SELECT " + entityColumnList(targetModel) + " FROM "
                        + Names.quote(targetModel.metadata().tableName())
                        + " WHERE " + Names.quote(inverseFk) + " IN (");
        return appendGroupedKeyHydration(constants, entity, a.name(), selectConst, inverseFk,
                targetModel.typeName(), relationship.targetMapper());
    }

    /**
     * The one grouping loader shape shared by element-collection (Task 3) and one-to-many (Task 4)
     * hydration. It gathers the parents' ids, builds a {@code WHERE <fk> IN (:key0, ...)} at runtime
     * (JdbcClient has no collection-parameter expansion), and reads each child row as an
     * {@code Object[]} pair of {@code [<fk>, <Child>]} via an anonymous {@code RowMapper} — the row
     * mapper is row-scoped and has no client access, so the pairing is assembled here — then groups the
     * children by fk and assigns each parent's collection. The pair's second element delegates to
     * {@code childMapper} (a {@code <VO>RowMapper} for element collections, the target entity's
     * {@code <Entity>RowMapper} for one-to-many). FK-only child columns are never selected by the
     * element-collection column list (see {@link #quotedColumnList}); the association targets stay
     * {@code null} (Decision 8).
     */
    private MethodSpec appendGroupedKeyHydration(Map<String, String> constants, EntityModel entity,
                                                 String field, String selectConst, String fkLabel,
                                                 TypeName childType, ClassName childMapper) {
        ClassName sqlSpec = ClassName.get(JdbcClient.class).nestedClass("SqlSpec");
        TypeName entityType = entity.typeName();
        // I-2: the parent-key / child-FK column shares the entity id's JDBC type, so derive it from
        // the id rather than hardcoding Long — an entity with an Integer (or other JDBC-safe) id would
        // otherwise emit a loader whose $T casts/getObject calls do not compile in the consumer build.
        // box() normalizes a primitive id (long -> java.lang.Long) so a Long-id corpus stays byte-identical.
        TypeName keyType = entity.id().type().box();
        TypeName parentKeyList = ParameterizedTypeName.get(ClassName.get(java.util.List.class), keyType);
        TypeName rowArrayType = ArrayTypeName.of(TypeName.OBJECT);
        TypeName rowsType = ParameterizedTypeName.get(ClassName.get(java.util.List.class), rowArrayType);
        TypeName groupedType = ParameterizedTypeName.get(ClassName.get(java.util.Map.class), keyType,
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), childType));

        MethodSpec.Builder loader = MethodSpec.methodBuilder("hydrate" + capitalize(field))
                .addJavadoc("Hydrates the $L collection over the given parents. Depth-1: the loaded "
                        + "children's own relationships are not hydrated.\n", field)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Collection.class), entityType), "parents");
        loader.beginControlFlow("if (parents.isEmpty())").addStatement("return").endControlFlow();
        loader.addStatement("$T keys = new $T<>()", parentKeyList, java.util.ArrayList.class);
        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("keys.add(parent.$L())", getter(entity.id().name()));
        loader.endControlFlow();

        loader.addStatement("$T sql = new $T($L)", StringBuilder.class, StringBuilder.class, selectConst);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.beginControlFlow("if (i > 0)");
        loader.addStatement("sql.append($S)", ", ");
        loader.endControlFlow();
        loader.addStatement("sql.append($S).append(i)", ":key");
        loader.endControlFlow();
        loader.addStatement("sql.append($S)", ")");
        loader.addStatement("$T spec = jdbcClient.sql(sql.toString())", sqlSpec);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.addStatement("spec = spec.param($S + i, keys.get(i))", "key");
        loader.endControlFlow();
        loader.addStatement("$T rows = spec.query((rs, rowNum) -> new $T[] { rs.getObject($S, $T.class), "
                        + "new $T().mapRow(rs, rowNum) }).list()",
                rowsType, Object.class, fkLabel, keyType, childMapper);

        loader.addStatement("$T grouped = new $T<>()", groupedType, java.util.HashMap.class);
        loader.beginControlFlow("for ($T row : rows)", rowArrayType);
        loader.addStatement("grouped.computeIfAbsent(($T) row[0], k -> new $T<>()).add(($T) row[1])",
                keyType, java.util.ArrayList.class, childType);
        loader.endControlFlow();
        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("parent.$L(grouped.getOrDefault(parent.$L(), new $T<>()))",
                setter(field), getter(entity.id().name()), java.util.ArrayList.class);
        loader.endControlFlow();

        return loader.build();
    }

    /**
     * Emits one many-to-one loader (Task 4 / Decision 4: {@code RowMapper} has no client access, so the
     * target cannot be fetched from inside the parent row's mapping — the read is split in two). Phase 1
     * re-reads {@code <id>} and {@code <prop>_id} from the PARENT's own table WHERE the parent id IN
     * (:key<i>) and pairs them into a parent-id -> fk map (a NULL fk stays in the map as a NULL value).
     * Phase 2 collects the DISTINCT non-null fks and fetches whole targets from the TARGET's table
     * WHERE {@code "id"} IN (:tid<i>) through the target's generated row mapper (no ResultSet pairing
     * happens there), then assigns each parent's reference from the two maps. The target's OWN
     * relationships are not hydrated (depth-1), and hydrated targets are not cascaded on update
     * (contract locks, ruling 6).
     */
    private MethodSpec appendManyToOneHydration(Map<String, String> constants, EntityModel entity,
                                                EntityRelationship relationship) {
        Prop.Reference a = (Prop.Reference) relationship.attribute();
        EntityModel targetModel = relationship.targetModel();
        ClassName targetMapper = relationship.targetMapper();
        TypeName targetType = targetModel.typeName();
        String idColumn = entity.id().column();
        String fkColumn = a.fkColumn();
        String targetIdColumn = targetModel.id().column();

        String fkConst = addConstant(constants, "SQL_SELECT_" + upperSnake(a.name()) + "_FK",
                "SELECT " + Names.quote(idColumn) + ", " + Names.quote(fkColumn) + " FROM "
                        + Names.quote(entity.metadata().tableName())
                        + " WHERE " + Names.quote(idColumn) + " IN (");
        String targetConst = addConstant(constants,
                "SQL_FIND_" + upperSnake(targetSimpleName(targetModel)) + "_BY_IDS",
                "SELECT " + entityColumnList(targetModel) + " FROM "
                        + Names.quote(targetModel.metadata().tableName())
                        + " WHERE " + Names.quote(targetIdColumn) + " IN (");

        ClassName sqlSpec = ClassName.get(JdbcClient.class).nestedClass("SqlSpec");
        TypeName entityType = entity.typeName();
        // I-2 in force again: the parent-key list/cast follows the entity id's boxed type, while the FK
        // side follows the TARGET's id type (the fk column shares the referenced id's JDBC type).
        TypeName keyType = entity.id().type().box();
        TypeName targetKeyType = targetModel.id().type().box();
        TypeName parentKeyList = ParameterizedTypeName.get(ClassName.get(java.util.List.class), keyType);
        TypeName fkIdList = ParameterizedTypeName.get(ClassName.get(java.util.List.class), targetKeyType);
        TypeName rowArrayType = ArrayTypeName.of(TypeName.OBJECT);
        TypeName rowsType = ParameterizedTypeName.get(ClassName.get(java.util.List.class), rowArrayType);
        TypeName fkByParentType = ParameterizedTypeName.get(ClassName.get(java.util.Map.class),
                keyType, targetKeyType);
        TypeName byIdType = ParameterizedTypeName.get(ClassName.get(java.util.Map.class),
                targetKeyType, targetType);

        MethodSpec.Builder loader = MethodSpec.methodBuilder("hydrate" + capitalize(a.name()))
                .addJavadoc("Hydrates the $L reference over the given parents (fk re-read, then target "
                        + "fetch). Depth-1: a fetched target's own relationships are not hydrated.\n", a.name())
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Collection.class), entityType), "parents");
        loader.beginControlFlow("if (parents.isEmpty())").addStatement("return").endControlFlow();

        // Phase 1: parent id -> fk, read from the parent's own table.
        loader.addStatement("$T keys = new $T<>()", parentKeyList, java.util.ArrayList.class);
        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("keys.add(parent.$L())", getter(entity.id().name()));
        loader.endControlFlow();
        loader.addStatement("$T sql = new $T($L)", StringBuilder.class, StringBuilder.class, fkConst);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.beginControlFlow("if (i > 0)");
        loader.addStatement("sql.append($S)", ", ");
        loader.endControlFlow();
        loader.addStatement("sql.append($S).append(i)", ":key");
        loader.endControlFlow();
        loader.addStatement("sql.append($S)", ")");
        loader.addStatement("$T spec = jdbcClient.sql(sql.toString())", sqlSpec);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.addStatement("spec = spec.param($S + i, keys.get(i))", "key");
        loader.endControlFlow();
        loader.addStatement("$T rows = spec.query((rs, rowNum) -> new $T[] { rs.getObject($S, $T.class), "
                        + "rs.getObject($S, $T.class) }).list()",
                rowsType, Object.class, idColumn, keyType, fkColumn, targetKeyType);
        loader.addStatement("$T fkByParent = new $T<>()", fkByParentType, java.util.HashMap.class);
        loader.beginControlFlow("for ($T row : rows)", rowArrayType);
        loader.addStatement("fkByParent.put(($T) row[0], ($T) row[1])", keyType, targetKeyType);
        loader.endControlFlow();

        // Phase 2: distinct non-null fks -> targets, fetched with the target's generated row mapper.
        loader.addStatement("$T fkIds = new $T<>()", fkIdList, java.util.ArrayList.class);
        loader.beginControlFlow("for ($T fk : fkByParent.values())", targetKeyType);
        loader.beginControlFlow("if (fk != null && !fkIds.contains(fk))");
        loader.addStatement("fkIds.add(fk)");
        loader.endControlFlow();
        loader.endControlFlow();
        loader.addStatement("$T byId = new $T<>()", byIdType, java.util.HashMap.class);
        loader.beginControlFlow("if (!fkIds.isEmpty())");
        loader.addStatement("$T targetSql = new $T($L)", StringBuilder.class, StringBuilder.class, targetConst);
        loader.beginControlFlow("for (int i = 0; i < fkIds.size(); i++)");
        loader.beginControlFlow("if (i > 0)");
        loader.addStatement("targetSql.append($S)", ", ");
        loader.endControlFlow();
        loader.addStatement("targetSql.append($S).append(i)", ":tid");
        loader.endControlFlow();
        loader.addStatement("targetSql.append($S)", ")");
        loader.addStatement("$T targetSpec = jdbcClient.sql(targetSql.toString())", sqlSpec);
        loader.beginControlFlow("for (int i = 0; i < fkIds.size(); i++)");
        loader.addStatement("targetSpec = targetSpec.param($S + i, fkIds.get(i))", "tid");
        loader.endControlFlow();
        loader.beginControlFlow("for ($T target : targetSpec.query(new $T()).list())", targetType, targetMapper);
        loader.addStatement("byId.put(target.$L(), target)", getter(targetModel.id().name()));
        loader.endControlFlow();
        loader.endControlFlow();

        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("parent.$L(byId.get(fkByParent.get(parent.$L())))",
                setter(a.name()), getter(entity.id().name()));
        loader.endControlFlow();

        return loader.build();
    }

    /** The simple name of a target entity model, for the {@code SQL_FIND_<TARGET>_BY_IDS} constant base. */
    private static String targetSimpleName(EntityModel targetModel) {
        String fqn = targetModel.typeName().toString();
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * The physical column list of an ENTITY (not a VO): every {@code metadata().columns()} entry,
     * quoted and comma-joined — id first, then scalars, many-to-one/association FKs and flattened
     * embedded leaves. Unlike {@link #quotedColumnList} (element-collection children, whose FK-only
     * columns are never selected) a one-to-many/many-to-one SELECT must carry the target's own FK
     * columns so its row mapper and, for one-to-many, the inverse grouping label are readable.
     */
    private static String entityColumnList(EntityModel entity) {
        StringBuilder sb = new StringBuilder();
        for (ColumnRef column : entity.metadata().columns()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(Names.quote(column.column()));
        }
        return sb.toString();
    }

    /**
     * Memoized {@link #buildEntity} for a relationship target. {@code buildEntity} is idempotent (it
     * walks the element fresh), so this is a pure cache that keeps a target reused by several
     * repositories from being re-walked, mirroring {@link #voModels} for value objects.
     */
    private EntityModel entityModel(TypeElement targetElement) {
        String fqn = targetElement.getQualifiedName().toString();
        EntityModel model = entityModels.get(fqn);
        if (model == null) {
            model = buildEntity(targetElement);
            entityModels.put(fqn, model);
        }
        return model;
    }

    /**
     * Resolves (generating on first use) the {@code <Entity>RowMapper} for a managed relationship
     * target. The managed-target gate already proved the target has a repository, but its own
     * {@link #generate} pass may not have run yet in this round, so the metadata + mapper are emitted
     * here on demand, exactly as the entity's own pass would: the {@link #rowMappers} and
     * {@link #metadataTypes} caches make the emission idempotent, which keeps one {@code Filer} write
     * per type however the repositories are ordered. A target failing the reflection-free gate yields
     * {@code null}: the caller records the gate NOTE and leaves the reference unmapped (no loader).
     */
    private ClassName ensureEntityRowMapper(TypeElement targetElement, EntityModel targetModel) {
        String targetFqn = targetElement.getQualifiedName().toString();
        ClassName cached = rowMappers.get(targetFqn);
        if (cached != null) {
            return cached;
        }
        if (!canGenerateRowMapper(targetModel)) {
            return null;
        }
        if (!metadataTypes.containsKey(targetFqn)) {
            generateEmbeddedDescriptors(targetModel.metadata());
            ensureDescriptorArtifacts(targetElement, targetModel, true);
        }
        ClassName mapper = generateRowMapper(targetElement, targetModel, metadataTypes.get(targetFqn));
        rowMappers.put(targetFqn, mapper);
        return mapper;
    }

    /**
     * A relationship carried into emission: either a READ hydration loader member (cleared the Task 4
     * managed-target gate: attribute, target's compile-time model, generated row mapper — every such
     * instance is loader-emittable) or a WRITE cascade member from {@link #generate}'s EntityList scan
     * (fix-round 2 I-2 decoupling: the plain-SQL child INSERT/DELETE uses only attribute/targetModel,
     * so a cascade member may carry a {@code null} {@code targetMapper} — the child's row mapper is
     * irrelevant to writes).
     */
    private record EntityRelationship(Prop attribute, EntityModel targetModel,
                                      ClassName targetMapper) { }


    private static String capitalize(String field) {
        return Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    // ------------------------------------------------------------------ helpers

    private void rebindTypeVariables(MethodSpec.Builder builder, ExecutableElement method, TypeName entityType) {
        if (!builder.typeVariables.isEmpty() && !method.getTypeParameters().isEmpty()) {
            builder.typeVariables.clear();
            builder.typeVariables.add(TypeVariableName.get(
                    method.getTypeParameters().get(0).getSimpleName().toString(), entityType));
        }
    }

    private boolean isEntityParameter(TypeMirror parameterType, EntityModel entity) {
        return types.isAssignable(types.erasure(parameterType), types.erasure(entity.typeMirror()));
    }

    private String addConstant(Map<String, String> constants, String base, String sql) {
        String name = base;
        int suffix = 2;
        while (constants.containsKey(name) && !constants.get(name).equals(sql)) {
            name = base + "_" + suffix++;
        }
        constants.put(name, sql);
        return name;
    }

    private List<SortSpec> sorts(ExecutableElement method, ParsedMethodName parsed) {
        List<SortSpec> sorts = new ArrayList<>();
        if (parsed != null) {
            sorts.addAll(parsed.sorts());
        }
        for (OrderBy orderBy : method.getAnnotationsByType(OrderBy.class)) {
            String property = By.ID.equals(orderBy.value()) ? "id" : orderBy.value();
            sorts.add(SortSpec.of(property, !orderBy.descending(), orderBy.ignoreCase()));
        }
        return sorts;
    }

    private Operator operatorFor(ParsedMethodName parsed, String property) {
        if (parsed != null) {
            for (Predicate predicate : parsed.predicates()) {
                if (predicate.property().equals(property)) {
                    return predicate.operator();
                }
            }
        }
        return Operator.EQ;
    }

    private ParsedMethodName tryParse(String name) {
        for (String action : new String[]{"find", "count", "delete", "exists"}) {
            if (name.startsWith(action)) {
                try {
                    return new QueryByMethodNameParser().parse(name);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<Integer> nonSpecialIndices(List<? extends TypeMirror> parameterTypes) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (!isSpecial(parameterTypes.get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }

    private boolean isSpecial(TypeMirror type) {
        String erased = types.erasure(type).toString();
        if (PAGE_REQUEST.equals(erased) || ORDER.equals(erased) || LIMIT.equals(erased) || SORT.equals(erased)) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) type).getComponentType();
            return SORT.equals(types.erasure(component).toString());
        }
        return false;
    }

    private Kind kindOf(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.VOID) {
            return Kind.VOID;
        }
        if (returnType.getKind() == TypeKind.ARRAY) {
            return Kind.LIST;
        }
        return switch (types.erasure(returnType).toString()) {
            case "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable" -> Kind.LIST;
            case "java.util.Optional" -> Kind.OPTIONAL;
            case "java.util.stream.Stream" -> Kind.STREAM;
            case "jakarta.data.page.Page" -> Kind.PAGE;
            case "jakarta.data.page.CursoredPage" -> Kind.CURSORED_PAGE;
            default -> Kind.SINGLE;
        };
    }

    private TypeName queryResultType(TypeMirror returnType, TypeName fallback) {
        if (returnType.getKind() == TypeKind.ARRAY) {
            return TypeName.get(((ArrayType) returnType).getComponentType()).box();
        }
        if (returnType instanceof DeclaredType declared && declared.getTypeArguments().size() == 1
                && CONTAINER_TYPES.contains(types.erasure(declared).toString())) {
            return concrete(TypeName.get(declared.getTypeArguments().get(0)), fallback);
        }
        return concrete(TypeName.get(returnType).box(), fallback);
    }

    private TypeName concrete(TypeName candidate, TypeName fallback) {
        return candidate instanceof TypeVariableName ? fallback : candidate;
    }

    private String parameterName(VariableElement parameter, String fallback) {
        String name = parameter.getSimpleName().toString();
        return name.isEmpty() ? fallback : name;
    }

    private static String getter(String field) {
        return "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private static String setter(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private static String upperSnake(String name) {
        return Names.toSnakeCase(name).toUpperCase();
    }

    /** Walks the interface hierarchy for the {@code DataRepository}/{@code BasicRepository}/{@code CrudRepository} supertype. */
    private TypeMirror resolveEntityType(TypeElement repository, Set<String> visited) {
        if (!visited.add(repository.getQualifiedName().toString())) {
            return null;
        }
        for (TypeMirror supertype : types.directSupertypes(repository.asType())) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            String erasure = types.erasure(declared).toString();
            if (DATA_REPOSITORY.equals(erasure) || BASIC_REPOSITORY.equals(erasure) || CRUD_REPOSITORY.equals(erasure)) {
                List<? extends TypeMirror> arguments = declared.getTypeArguments();
                if (!arguments.isEmpty()) {
                    return arguments.get(0);
                }
            }
            if (declared.asElement() instanceof TypeElement parent) {
                TypeMirror resolved = resolveEntityType(parent, visited);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private boolean extendsCrud(TypeElement repository, Set<String> visited) {
        if (!visited.add(repository.getQualifiedName().toString())) {
            return false;
        }
        for (TypeMirror supertype : types.directSupertypes(repository.asType())) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (CRUD_REPOSITORY.equals(types.erasure(declared).toString())) {
                return true;
            }
            if (declared.asElement() instanceof TypeElement parent && extendsCrud(parent, visited)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------- compile-time entity model

    private EntityModel buildEntity(TypeElement element) {
        boolean valueObject = isValueObjectType(element);
        // The owner stereotype drives relationship classification (Classification Table): a bare
        // entity-typed field composes only inside an @AggregateRoot; elsewhere it is an association.
        Stereotype ownerStereotype = stereotypeOf(element);
        TypeMirror typeMirror = types.erasure(element.asType());
        TypeName typeName = TypeName.get(typeMirror);
        String table = valueObject ? null : Names.tableName(element.getSimpleName().toString());
        String typeNameString = element.getQualifiedName().toString();
        Property id = null;
        Prop.Id idProp = null;
        List<Property> properties = new ArrayList<>();
        List<Prop> props = new ArrayList<>();
        List<ElementCollection> collections = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(element)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (!seen.add(name)) {
                continue;
            }
            String fieldType = field.asType().toString();
            TypeName fieldTypeName = safeFieldType(field.asType());
            boolean identityAnnotated = field.getAnnotation(Identity.class) != null;

            if (valueObject && identityAnnotated) {
                // A value object has no identity: an @Identity field is demoted to a scalar and a
                // NOTE records the ignored annotation. (A field merely NAMED "id" is already scalar
                // via the !valueObject guard below, so it needs no NOTE.)
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "value object '" + element.getSimpleName() + "' declares '" + name
                                + "'; ignored — value objects have no identity", element);
            }
            if (!valueObject && (identityAnnotated || name.equals("id"))) {
                idProp = new Prop.Id(name, Names.columnName(name), fieldType);
                id = new Property(name, idProp.column(), fieldTypeName, idProp, null);

            } else if (field.getAnnotation(Association.class) != null) {
                // External aggregate reference: fk-only, never hydrated.
                TypeElement target = declaredElement(field);
                Prop.Reference ref = referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, target, false);
                properties.add(new Property(name, ref.fkColumn(), fieldTypeName, ref, null));
                props.add(ref);

            } else if (embeddedTarget(field) != null) {
                // Direct value object: flatten leaves into this table. The Property carries the VO's
                // compile-time model so the mapper gate and the embedded read emitter can recurse.
                TypeElement vo = embeddedTarget(field);
                Prop.Embedded embedded = new Prop.Embedded(name, Names.columnName(name), embeddedOf(descriptorFor(vo)));
                properties.add(new Property(name, embedded.column(), fieldTypeName, embedded, voModel(vo)));
                props.add(embedded);

            } else if (isEntityType(declaredElement(field))) {
                // A bare entity-typed field is a many-to-one whose ownership is set by the OWNER
                // stereotype (Classification Table): it composes only inside an @AggregateRoot (root-
                // owned, hydrated on read). Inside an @Entity or a @ValueObject the same field is an
                // association (composition=false) — the fk column is kept but no loader is emitted.
                TypeElement target = declaredElement(field);
                boolean composition = ownerStereotype == Stereotype.AGGREGATE_ROOT;
                Prop.Reference ref = referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), target, composition);
                properties.add(new Property(name, ref.fkColumn(), fieldTypeName, ref, null));
                props.add(ref);

            } else if (valueObjectElementType(field) != null) {
                // List<@ValueObject>: element collection in a child table. The child-table placement
                // lives in the Prop.ElementList the collection carries, so props and collections agree.
                TypeElement vo = valueObjectElementType(field);
                ElementCollection collection = buildCollection(table, name, vo);
                collections.add(collection);
                props.add(collection.element());

            } else if (entityTypeElement(field) != null) {
                // List<@Entity>: one-to-many across the child's own table. The inverse is resolved
                // in the second pass below, so the placeholder inverse stays null here.
                TypeElement child = entityTypeElement(field);
                props.add(new Prop.EntityList(name, entityOf(descriptorFor(child)), null, null));

            } else {
                Prop.Scalar scalar = new Prop.Scalar(name, Names.columnName(name), fieldType);
                properties.add(new Property(name, scalar.column(), fieldTypeName, scalar, null));
                props.add(scalar);
            }
        }
        if (!valueObject && id == null) {
            idProp = new Prop.Id("id", Names.columnName("id"), "java.lang.Long");
            id = new Property("id", idProp.column(), ClassName.get(Long.class), idProp, null);
        }
        // Second pass: resolve ONE_TO_MANY inverses now that all parent properties are known.
        DataModel self = valueObject ? new EmbeddedValueObjectModel(typeNameString, props)
                : new EntityDataModel(ownerStereotype, typeNameString, table, idProp, props);
        List<Prop> resolved = new ArrayList<>(props.size());
        for (Prop p : props) {
            if (p instanceof Prop.EntityList entityList && entityList.targetTypeName() != null) {
                TypeElement childEl = elements.getTypeElement(entityList.targetTypeName());
                EntityDataModel child = childEl != null ? entityOf(descriptorFor(childEl)) : null;
                String inverse = child != null ? findInverseName(self, child) : null;
                resolved.add(new Prop.EntityList(entityList.name(), entityList.target(),
                        inverse, inverse == null ? null : Names.fkColumn(inverse)));
            } else {
                resolved.add(p);
            }
        }
        DataModel metadata = valueObject ? new EmbeddedValueObjectModel(typeNameString, resolved)
                : new EntityDataModel(ownerStereotype, typeNameString, table, idProp, resolved);
        return new EntityModel(typeMirror, typeName, id, properties, collections, metadata);
    }

    /** The DDD stereotype for an entity/value-object element. */
    private Stereotype stereotypeOf(TypeElement element) {
        if (isValueObjectType(element)) {
            return Stereotype.VALUE_OBJECT;
        }
        return element.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null
                ? Stereotype.AGGREGATE_ROOT : Stereotype.ENTITY;
    }

    /**
     * A foreign-key reference property. {@code composition} distinguishes an in-aggregate many-to-one
     * (root-owned, hydrated on read) from an external {@code @Association} (fk-only, never hydrated);
     * both share the {@link Prop.Reference} shape. The target's table/id column resolve the join edge
     * for downstream traversal and are resolved from the target's own descriptor (memoized, cycle-safe).
     */
    private Prop.Reference referenceProp(String name, String fkColumn, String targetTypeName,
                                         TypeElement target, boolean composition) {
        DataModel targetModel = target != null ? descriptorFor(target) : null;
        return new Prop.Reference(name, fkColumn, targetTypeName, entityOf(targetModel), composition);
    }

    private ElementCollection buildCollection(String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = parentTable + "_id";
        // The value object's flattened descriptor is the single source for the child-table column list
        // and the param bindings; per the global contract an @Association field is a (non-composing)
        // Reference there, exactly as in buildEntity, and binds the target's id through boundValue.
        // The Prop.ElementList is the one source of truth for the child-table placement and columns.
        ExternalValueObjectModel target = valueObjectMetadata(valueObject, childTable, parentFk);
        Prop.ElementList element = new Prop.ElementList(field, target, target.columns());
        return new ElementCollection(field, TypeName.get(types.erasure(valueObject.asType())),
                element, target);
    }

    /**
     * {@link TypeName#get(javax.lang.model.type.TypeMirror)} for a possibly exotic field type. A
     * mirror JavaPoet cannot represent (it throws {@link IllegalArgumentException}) degrades to
     * {@link TypeName#OBJECT}: never JDBC-safe, so the existing {@link #canGenerateRowMapper} gate
     * silently skips the mapper exactly as the old string-only path did, instead of aborting
     * annotation processing.
     */
    private static TypeName safeFieldType(TypeMirror type) {
        try {
            return TypeName.get(type);
        } catch (IllegalArgumentException e) {
            return TypeName.OBJECT;
        }
    }

    /**
     * Builds the id-less descriptor for a {@code List<@ValueObject>} element type. Classification
     * follows the global contract: {@code @Association} → a non-composing {@link Prop.Reference} (never
     * composing); everything else is a {@link Prop.Scalar} column.
     */
    private ExternalValueObjectModel valueObjectMetadata(TypeElement valueObject, String childTable,
                                                       String parentFk) {
        List<Prop> props = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(valueObject)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) member;
            if (field.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = field.getSimpleName().toString();
            if (!seen.add(name) || field.getAnnotation(Identity.class) != null || name.equals("id")) {
                continue;
            }
            String fieldType = field.asType().toString();
            if (field.getAnnotation(Association.class) != null) {
                // Mirror buildEntity's external-reference branch: carry the target's FQN so the
                // relationship hydration / embedded mapping can resolve the association target.
                TypeElement target = declaredElement(field);
                props.add(referenceProp(name, Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, target, false));
            } else {
                props.add(new Prop.Scalar(name, Names.columnName(name), fieldType));
            }
        }
        return new ExternalValueObjectModel(valueObject.getQualifiedName().toString(),
                childTable, parentFk, List.copyOf(props));
    }

    private boolean isEntityType(TypeElement te) {
        return te != null
                && (te.getAnnotation(io.github.fludakit.data.annotation.Entity.class) != null
                 || te.getAnnotation(io.github.fludakit.data.annotation.AggregateRoot.class) != null);
    }

    private boolean isValueObjectType(TypeElement te) {
        return te != null && te.getAnnotation(io.github.fludakit.data.annotation.ValueObject.class) != null;
    }

    /** The TypeElement of a direct field whose type is a value object (for EMBEDDED), else null. */
    private TypeElement embeddedTarget(VariableElement field) {
        if (field.getAnnotation(Association.class) != null) return null;
        TypeMirror type = field.asType();
        if (!(type instanceof DeclaredType declared)) return null;
        if (!(declared.asElement() instanceof TypeElement te)) return null;
        return isValueObjectType(te) ? te : null;
    }

    /** Element TypeElement of a {@code java.util.List<E>} field, when E matches; null otherwise. */
    private TypeElement listElementType(VariableElement field,
                                        java.util.function.Predicate<TypeElement> elementMatcher) {
        if (field.getAnnotation(Association.class) != null) return null;
        if (!(field.asType() instanceof DeclaredType declared) || declared.getTypeArguments().size() != 1
                || !"java.util.List".equals(types.erasure(declared).toString())) {
            return null;
        }
        TypeMirror argument = declared.getTypeArguments().get(0);
        if (!(argument instanceof DeclaredType element) || !(element.asElement() instanceof TypeElement te)) {
            return null;
        }
        return elementMatcher.test(te) ? te : null;
    }

    /** The element TypeElement of a List&lt;E&gt; field when E is an entity (for ONE_TO_MANY), else null. */
    private TypeElement entityTypeElement(VariableElement field) {
        return listElementType(field, this::isEntityType);
    }

    /** The element TypeElement of a List&lt;E&gt; field when E is a value object (for ELEMENT_COLLECTION). */
    private TypeElement valueObjectElementType(VariableElement field) {
        return listElementType(field, this::isValueObjectType);
    }

    /** The declared TypeElement of a (non-collection) field, or null. */
    private TypeElement declaredElement(VariableElement field) {
        if (field.asType() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement te) {
            return te;
        }
        return null;
    }

    /**
     * Builds the compile-time descriptor for any entity or value-object element, reusing
     * {@link #buildEntity}'s classification without generating sources. Memoized, and cycle-bounded:
     * a descriptor already being built returns a shallow property-less placeholder (only
     * {@code typeName} is consumed for a back-reference).
     */
    private DataModel descriptorFor(TypeElement te) {
        String fqn = te.getQualifiedName().toString();
        DataModel cached = descriptorCache.get(fqn);
        if (cached != null) return cached;
        if (inProgress.contains(fqn)) {
            // Cycle: return a shallow, property-less placeholder; only typeName is consumed
            // for a back-reference, and findInverseName tolerates an empty property list.
            return stereotypeOf(te) == Stereotype.VALUE_OBJECT
                    ? new EmbeddedValueObjectModel(fqn, List.of())
                    : new EntityDataModel(stereotypeOf(te), fqn,
                            Names.tableName(te.getSimpleName().toString()), null, List.of());
        }
        inProgress.add(fqn);
        try {
            DataModel d = buildEntity(te).metadata();
            descriptorCache.put(fqn, d);
            return d;
        } finally {
            inProgress.remove(fqn);
        }
    }

    /** The entity-kind view of a built model, or {@code null} when it is not an entity model.
     *  Kind mismatches (e.g. an {@code @Association} at a value object) degrade exactly as the
     *  old null-target snapshot did: no table, no id column, target left unresolved. */
    private static EntityDataModel entityOf(DataModel m) {
        return m instanceof EntityDataModel e ? e : null;
    }

    /** The embedded-kind view of a built model, or {@code null} when it is not one. */
    private static EmbeddedValueObjectModel embeddedOf(DataModel m) {
        return m instanceof EmbeddedValueObjectModel e ? e : null;
    }

    /**
     * Compile-time model of an {@code EMBEDDED} target value object: memoized like {@link #descriptorFor}
     * and cycle-bounded — a re-entrant build (A embeds B embeds A, which is Java-legal although VOs
     * cannot cycle back into an aggregate) returns {@code null} so the mapper gate fails closed on that
     * property instead of recursing forever.
     */
    private EntityModel voModel(TypeElement voElement) {
        String fqn = voElement.getQualifiedName().toString();
        EntityModel cached = voModels.get(fqn);
        if (cached != null) {
            return cached;
        }
        if (voModelsInProgress.contains(fqn)) {
            return null;
        }
        voModelsInProgress.add(fqn);
        try {
            EntityModel model = buildEntity(voElement);
            voModels.put(fqn, model);
            return model;
        } finally {
            voModelsInProgress.remove(fqn);
        }
    }

    /**
     * Scans the child's properties for a back-reference to the parent; a cyclic child whose descriptor
     * is the shallow placeholder yields {@code null}, which Plan C's hydration resolves instead from
     * the child's own generated metadata. An unresolved inverse is treated as read-only/unmapped. Both
     * the composing many-to-one and the external {@code @Association} are {@link Prop.Reference}s, so
     * either matches the parent's type name.
     */
    private String findInverseName(DataModel parent, DataModel child) {
        for (Prop p : child.props()) {
            if (p instanceof Prop.Reference reference
                    && parent.typeName().equals(reference.targetTypeName())) {
                return reference.name();
            }
        }
        return null; // unresolved inverse: hydration (Plan C) will treat this as read-only/unmapped
    }

    // --------------------------------------------------------------- nested types

    /**
     * The processor's resolved view of ONE query-by-names method: the single source feeding both the
     * rendered SQL constant (E3) and the generated {@code <Repository>Model}. {@code modelName} is
     * the nested class's simple name (overload-suffixed); {@code QueryModel<T>}'s {@code Object}
     * parameter is irrelevant to rendering — the generated class re-types it to the entity.
     */
    private record ResolvedQueryModel(String modelName, Operation operation, ResultKind resultKind,
                                      List<ResolvedPredicate> predicates, List<ResolvedSort> sorts,
                                      PagingMode paging, int limit, List<SeekTerm> seekTerms,
                                      List<ParamBinding> paramBindings) implements QueryModel<Object> {
    }

    /**
     * Fold one query-by-names method's resolved lists into a {@link ResolvedQueryModel}. The paging
     * mode and result kind come from the return {@link Kind} plus the parsed action; seek terms are
     * the runtime cursor key for CURSOR and the published default key for the other paged modes
     * (E1's base constant drops sorts/limit at render time, so the
     * declared sorts stay in the model for introspection). {@code paramBindings} are exactly the
     * {@code .param($S, ...)} tokens {@link #appendBoundParameters} emits, keyed by argument slot.
     */
    private static ResolvedQueryModel queryModelOf(String modelName, Operation operation, Kind kind,
                                                   DataModel md, List<Predicate> predicates,
                                                   List<ColumnRef> predicateRefs,
                                                   List<Boolean> existentialPredicates,
                                                   List<SortSpec> sorts, List<ColumnRef> sortRefs,
                                                   List<Boolean> existentialSorts, int limit,
                                                   List<Binding> bindings,
                                                   List<? extends VariableElement> parameters) {
        List<ResolvedPredicate> resolvedPredicates = new ArrayList<>();
        for (int i = 0; i < predicates.size(); i++) {
            resolvedPredicates.add(new ResolvedPredicate(predicates.get(i), predicateRefs.get(i),
                    Boolean.TRUE.equals(existentialPredicates.get(i))));
        }
        List<ResolvedSort> resolvedSorts = new ArrayList<>();
        for (int i = 0; i < sorts.size(); i++) {
            resolvedSorts.add(new ResolvedSort(sorts.get(i), sortRefs.get(i),
                    Boolean.TRUE.equals(existentialSorts.get(i))));
        }
        PagingMode paging = kind == Kind.PAGE ? PagingMode.OFFSET
                : kind == Kind.CURSORED_PAGE ? PagingMode.CURSOR : PagingMode.NONE;
        ResultKind resultKind = switch (operation) {
            case COUNT -> ResultKind.COUNT;
            case EXISTS -> ResultKind.EXISTS;
            default -> switch (kind) {
                case LIST -> ResultKind.LIST;
                case STREAM -> ResultKind.STREAM;
                case OPTIONAL -> ResultKind.OPTIONAL;
                case SINGLE -> ResultKind.SINGLE;
                case VOID -> ResultKind.VOID;
                case PAGE -> ResultKind.PAGE;
                case CURSORED_PAGE -> ResultKind.CURSORED_PAGE;
            };
        };
        // Every paged shape (OFFSET as well as CURSOR) publishes the keyset key: the runtime emitPaged
        // wiring computes the same props/dirs lists for both, and SqlBuilder.build ignores seek terms
        // (paging != NONE renders the base constant), so this changes no rendered SQL.
        List<SeekTerm> seekTerms = paging == PagingMode.NONE ? List.of() : seekTerms(md, resolvedSorts);
        List<ParamBinding> paramBindings = new ArrayList<>();
        for (Binding binding : bindings) {
            Operator operator = binding.operator();
            if (operator == Operator.IS_NULL || operator == Operator.IS_NOT_NULL
                    || operator == Operator.TRUE || operator == Operator.FALSE) {
                continue; // rendered without a token, so not bound
            }
            paramBindings.add(new ParamBinding(binding.paramIndex(),
                    parameters.get(binding.paramIndex()).getSimpleName().toString()));
        }
        return new ResolvedQueryModel(modelName, operation, resultKind,
                List.copyOf(resolvedPredicates), List.copyOf(resolvedSorts), paging, limit,
                List.copyOf(seekTerms), List.copyOf(paramBindings));
    }

    /**
     * The keyset seek terms a cursor uses: the declared sorts (or the {@code id ASC} default when the
     * method declares none), plus the root-id tiebreak exactly when a to-many sort is present and the
     * last term is not already an ascending id — the same rule {@link #emitPaged} applies to the
     * runtime {@code props}/{@code dirs} lists, kept here so the published model matches the wiring.
     */
    private static List<SeekTerm> seekTerms(DataModel md, List<ResolvedSort> sorts) {
        ColumnRef idRef = new ColumnRef(md.idColumn(), md.id().name());
        if (sorts.isEmpty()) {
            return List.of(new SeekTerm(idRef, true));
        }
        List<SeekTerm> out = new ArrayList<>();
        for (ResolvedSort sort : sorts) {
            out.add(new SeekTerm(sort.ref(), sort.sort().ascending()));
        }
        boolean toManySort = sorts.stream().anyMatch(ResolvedSort::existential);
        ResolvedSort last = sorts.get(sorts.size() - 1);
        boolean lastIsIdAsc = md.id().name().equals(last.sort().property()) && last.sort().ascending();
        if (toManySort && !lastIsIdAsc) {
            out.add(new SeekTerm(idRef, true));
        }
        return out;
    }

    /**
     * A mapped non-id property. {@code prop} is the descriptor property whose sealed shape drives the
     * gate and the codegen loops (embedded recursion, fk-only skips); {@code embeddedModel} is the
     * compile-time {@link EntityModel} of an {@link Prop.Embedded} target — the read mapper recurses on
     * it — {@code null} otherwise or when the VO build cycles. The descriptor's own props list is
     * built in parallel from the same {@link Prop} instances (one source of truth).
     */
    private record Property(String name, String column, TypeName type, Prop prop,
                            EntityModel embeddedModel) { }

    /**
     * A {@code List<@ValueObject>} element collection. {@code element} (the {@link Prop.ElementList}
     * that is also this collection's entry in the parent's props) is the single source of truth for the
     * child table and parent fk; {@code descriptor} (the value object's flattened column view) drives
     * the param bindings, so fk-bearing children resolve through the same {@link #boundValue} walk as
     * parent-entity paths.
     */
    private record ElementCollection(String field, TypeName valueObjectType,
                                     Prop.ElementList element, ExternalValueObjectModel descriptor) {
        String childTable() {
            return element.childTable();
        }

        String parentFk() {
            return element.parentFkColumn();
        }
    }

    private record EntityModel(TypeMirror typeMirror, TypeName typeName, Property id,
                               List<Property> properties, List<ElementCollection> collections,
                               DataModel metadata) { }

    private record Binding(String property, Operator operator, int paramIndex) { }

    private enum Kind { LIST, STREAM, OPTIONAL, SINGLE, VOID, PAGE, CURSORED_PAGE }
}
