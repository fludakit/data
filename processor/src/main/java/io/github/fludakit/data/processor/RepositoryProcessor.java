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
import io.github.fludakit.data.processor.DescriptorModel.Prop;
import io.github.fludakit.data.processor.DescriptorModel.Stereotype;
import io.github.fludakit.data.page.CursorSupport;
import io.github.fludakit.data.page.SimpleCursoredPage;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
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
    private static final ClassName FM_EMBEDDED = ClassName.get("io.github.fludakit.data.metamodel", "EmbeddedAttribute");
    private static final ClassName FM_FK = ClassName.get("io.github.fludakit.data.metamodel", "ForeignKeyAttribute");
    private static final ClassName FM_OTM = ClassName.get("io.github.fludakit.data.metamodel", "OneToManyAttribute");
    private static final ClassName FM_EC = ClassName.get("io.github.fludakit.data.metamodel", "ElementCollectionAttribute");
    private static final ClassName FM_COLUMN_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ColumnAttributeRecord");
    private static final ClassName FM_ID_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "IdAttributeRecord");
    private static final ClassName FM_SORTABLE_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "SortableColumnAttributeRecord");
    private static final ClassName FM_TEXT_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "TextColumnAttributeRecord");
    private static final ClassName FM_EMBEDDED_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "EmbeddedAttributeRecord");
    private static final ClassName FM_FK_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ForeignKeyAttributeRecord");
    private static final ClassName FM_OTM_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "OneToManyAttributeRecord");
    private static final ClassName FM_EC_RECORD =
            ClassName.get("io.github.fludakit.data.metamodel", "ElementCollectionAttributeRecord");

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
    private final Map<String, DescriptorModel> descriptorCache = new HashMap<>();

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
            }
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
        // descriptor's attributes (Decision 1). Value objects surface only as dot-path members of
        // their owner's metamodel, never as `_VO` classes.
        ensureDescriptorArtifacts(entityElement, entity, mapperPending);
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
        boolean hydrate = hasGeneratedHydration(entity, mapperType, hydratedCollections, hydratedRelationships);

        DeclaredType repositoryType = (DeclaredType) repository.asType();
        boolean crud = extendsCrud(repository, new HashSet<>());
        String packageName = elements.getPackageOf(repository).getQualifiedName().toString();
        String implementationName = repository.getSimpleName() + "Impl";

        Map<String, String> constants = new LinkedHashMap<>();
        List<MethodSpec> methods = new ArrayList<>();
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
            methods.add(generateMethod(method, repositoryType, entity, crud, constants, mapperType, hydrate));
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
            classBuilder.addMethod(columnHelper(entity));
        }

        writeSourceFile(packageName + "." + implementationName, repository,
                JavaFile.builder(packageName, classBuilder.build()).skipJavaLangImports(true).build());
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
     * appear flat in {@code attributes()}, making {@code attribute("address.city")} a direct hit
     * and {@code columns()} a {@code ColumnAttribute} filter in declaration order.
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

        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build());
        return self;
    }

    /**
     * Emits descriptor metadata for every value object transitively {@code EMBEDDED} in
     * {@code owner}'s attributes. Children are generated before their parent so the parent's
     * {@code <VO>Metadata.INSTANCE} reference resolves to a generated class; the
     * {@link #metadataTypes} guard is set before recursing, which bounds cycles (A embeds B embeds A).
     */
    private void generateEmbeddedDescriptors(DescriptorModel owner) {
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
     * constants hold entity ATTRIBUTE names (dot paths for embedded leaves, e.g.
     * {@code ADDRESS_CITY = "address.city"}) and whose mixed-case fields are FluDa attribute records
     * from {@code io.github.fludakit.data.metamodel}, each also carrying the physical COLUMN — so one
     * field instance is both the user-facing sort anchor and the runtime descriptor attribute
     * (Decision 1). Embedded leaves are emitted flat; {@code <Entity>Metadata} returns these very
     * instances. The member list comes from {@link #collectMembers}, shared with the metadata emitter.
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
        for (MmMember m : members) {
            builder.addField(FieldSpec.builder(memberAttrType(owner, m), m.identifier(),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(memberInitializer(owner, m, true)).build());
        }

        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, builder.build()).skipJavaLangImports(true).build());
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
    private List<MmMember> collectMembers(Element origin, DescriptorModel model) {
        List<MmMember> members = new ArrayList<>();
        addMembers(members, new HashSet<>(), origin, model, "", "");
        return members;
    }

    private void addMembers(List<MmMember> out, Set<String> taken, Element origin,
                             DescriptorModel model, String pathPrefix, String columnPrefix) {
        if (model.id() != null) {
            addMember(out, taken, origin, model.id(), pathPrefix, columnPrefix);
        }
        for (Prop a : model.props()) {
            addMember(out, taken, origin, a, pathPrefix, columnPrefix);
        }
    }

    /**
     * One member plus, for a resolved {@link Prop.Embedded}, its value object's flattened leaves
     * (recursion). Column and path prefixes thread through nesting exactly as
     * {@link DescriptorModel#columns()} flattens, so a leaf reads {@code ADDRESS_POINT_LAT =
     * "address.point.lat"} / column {@code address_point_lat}. Duplicate identifiers keep the first
     * member and report a NOTE (spec collision handling).
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
        if (a instanceof Prop.Embedded embedded && embedded.target() != null) {
            addMembers(out, taken, origin, embedded.target(), path, column + "_");
        }
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
        String targetFqn = memberTargetFqn(p);
        if (p instanceof Prop.Id) {
            return CodeBlock.of("new $T$L($L, $S)", FM_ID_RECORD, typeArgs, name, m.column());
        }
        if (p instanceof Prop.Scalar scalar) {
            ClassName rec = switch (metamodelKind(scalar.typeFqn())) {
                case TEXT -> FM_TEXT_RECORD;
                case SORTABLE -> FM_SORTABLE_RECORD;
                default -> FM_COLUMN_RECORD;
            };
            return CodeBlock.of("new $T$L($L, $S)", rec, typeArgs, name, m.column());
        }
        if (p instanceof Prop.Embedded embedded) {
            return embedded.target() != null
                    ? CodeBlock.of("new $T$L($L, $T.INSTANCE)", FM_EMBEDDED_RECORD, typeArgs, name,
                            embeddedMetadataName(embedded.target().typeName()))
                    : CodeBlock.of("new $T<$T,$T>($L, null)", FM_EMBEDDED_RECORD, owner,
                            classArgOf(targetFqn), name);
        }
        if (p instanceof Prop.Reference) {
            return CodeBlock.of("new $T$L($L, $S, $T.class, $L)",
                    FM_FK_RECORD, typeArgs, name, m.column(), classArgOf(targetFqn),
                    relationDescriptorSupplier(targetFqn, false));
        }
        if (p instanceof Prop.EntityList entityList) {
            return CodeBlock.of("new $T$L($L, $T.class, $L, $L)",
                    FM_OTM_RECORD, typeArgs, name, classArgOf(targetFqn), stringLiteral(entityList.inverseName()),
                    relationDescriptorSupplier(targetFqn, false));
        }
        Prop.ElementList elementList = (Prop.ElementList) p;
        return CodeBlock.of("new $T$L($L, $T.class, $S, $S, $L)",
                FM_EC_RECORD, typeArgs, name, classArgOf(targetFqn),
                elementList.childTable(), elementList.parentFkColumn(),
                relationDescriptorSupplier(targetFqn, true));
    }

    /**
     * The descriptor supplier appended to a relation attribute record: {@code () -> <Target>Metadata.INSTANCE}
     * when that metadata class is (or will be) emitted in this round, else {@code () -> null}. A managed
     * relationship target's {@code <Target>Metadata} is emitted exactly when the target is a managed entity
     * that clears the reflection-free mapper gate — the same predicate its own {@link #generate} pass and the
     * loader gate ({@link #ensureEntityRowMapper}) guard on — so the reference below always resolves against a
     * file the processor is committed to writing. An element-collection element VO's {@code <Vo>Metadata} is
     * emitted exactly when the VO resolves and clears that gate, mirroring {@link #voRowMapperFor}. The
     * referenced class name comes from {@link #metadataTypes} when already resolved, else the same
     * {@code <SimpleName>Metadata} the emitter writes.
     */
    private CodeBlock relationDescriptorSupplier(String targetFqn, boolean elementCollection) {
        if (targetFqn == null) {
            return CodeBlock.of("() -> null");
        }
        int angle = targetFqn.indexOf('<');
        String rawName = angle < 0 ? targetFqn : targetFqn.substring(0, angle);
        TypeElement targetElement = elements.getTypeElement(rawName);
        if (targetElement == null || !canGenerateRowMapper(entityModel(targetElement))) {
            return CodeBlock.of("() -> null");
        }
        if (!elementCollection && !managedEntities.contains(rawName)) {
            return CodeBlock.of("() -> null");
        }
        return CodeBlock.of("() -> $T.INSTANCE", embeddedMetadataName(rawName));
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
     * The {@code <VO>Metadata} type for a value-object FQN: the registered name from the embedded
     * tree walk when available, otherwise a best-effort split of the FQN (default-package safe).
     */
    private ClassName embeddedMetadataName(String voFqn) {
        ClassName known = metadataTypes.get(voFqn);
        if (known != null) {
            return known;
        }
        int dot = voFqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : voFqn.substring(0, dot);
        return ClassName.get(pkg, voFqn.substring(dot + 1) + "Metadata");
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
        for (Property p : entity.properties()) {
            if (p.prop() instanceof Prop.Reference) {
                continue; // FK-only reference; left null on read (loaders fill it in Tasks 3-4)
            }
            if (p.prop() instanceof Prop.Embedded) {
                appendEmbeddedRead(mapRow, metadataType, p, entityElement.getSimpleName().toString());
                continue;
            }
            appendPropertyRead(mapRow, p.type(), metadataType, p.name(), p.column(),
                    entityElement.getSimpleName().toString());
        }
        mapRow.addStatement("return entity");

        TypeSpec type = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(rowMapperInterface, entityType))
                .addMethod(mapRow.build())
                .build();
        writeSourceFile(packageName + "." + simpleName, entityElement,
                JavaFile.builder(packageName, type).skipJavaLangImports(true).build());
        return ClassName.get(packageName, simpleName);
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
                                      boolean hydrate) {
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
                emitSave(builder, value, entity, crud, constants);
                return builder.build();
            }
            if (name.equals("insert") && crud) {
                appendInsertStatements(builder, value, entity, constants);
                builder.addStatement("return $L", value);
                return builder.build();
            }
            if (name.equals("update") && crud) {
                appendUpdateStatements(builder, value, entity, constants);
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
            emitDeleteById(builder, parameterName(parameters.get(0), "id"), entity, constants);
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
            appendInsertStatements(builder, value, entity, constants);
            builder.addStatement("return $L", value);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Update.class) != null) {
            String value = parameterName(parameters.get(0), "entity");
            appendUpdateStatements(builder, value, entity, constants);
            builder.addStatement("return $L", value);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Save.class) != null) {
            emitSave(builder, parameterName(parameters.get(0), "entity"), entity, crud, constants);
            return builder.build();
        }
        if (arity == 1 && method.getAnnotation(Delete.class) != null) {
            String value = parameterName(parameters.get(0), "entity");
            builder.addStatement("deleteById($L.$L())", value, getter(entity.id().name()));
            return builder.build();
        }

        emitFind(builder, method, resolved, entity, constants, mapperType, hydrate);
        return builder.build();
    }

    // ------------------------------------------------------------------- CRUD writes

    private void emitSave(MethodSpec.Builder builder, String value, EntityModel entity, boolean crud,
                          Map<String, String> constants) {
        String idAccess = value + "." + getter(entity.id().name()) + "()";
        if (crud) {
            builder.addStatement("return $L == null ? insert($L) : update($L)", idAccess, value, value);
            return;
        }
        builder.beginControlFlow("if ($L == null)", idAccess);
        appendInsertStatements(builder, value, entity, constants);
        builder.addStatement("return $L", value);
        builder.nextControlFlow("else");
        appendUpdateStatements(builder, value, entity, constants);
        builder.addStatement("return $L", value);
        builder.endControlFlow();
    }

    private void appendInsertStatements(MethodSpec.Builder builder, String value, EntityModel entity,
                                        Map<String, String> constants) {
        // includeId=false: the generated key is read back through GeneratedKeyHolder, so the identity
        // column must be omitted from the INSERT (H2 and most databases reject an explicit NULL).
        String constant = addConstant(constants, "SQL_INSERT",
                new SqlBuilder().insert(entity.metadata(), false));
        builder.addStatement("$T keys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendColumnParams(sql, entity, value);
        sql.add(".update(keys)");
        builder.addStatement("$L", sql.build());

        builder.beginControlFlow("if (keys.getKey() != null)");
        // NEW-I: the generated-key coercion follows the id type instead of hardcoding longValue().
        // A Long id renders `((Number) keys.getKey()).longValue())` — byte-identical to the previous
        // hardcoded emission; an Integer id renders intValue(), so its generated impl compiles.
        // Unsupported id types (String/UUID/...) keep the old longValue() emission, exactly as before.
        builder.addStatement("$L.$L((($T) keys.getKey()).$L())",
                value, setter(entity.id().name()), Number.class, generatedKeyCoercion(entity.id().type()));
        builder.endControlFlow();

        for (ElementCollection collection : entity.collections()) {
            appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
    }

    /**
     * The {@code Number} → id coercion applied to a {@code GeneratedKeyHolder} key (NEW-I), derived
     * from the entity's id type so the insert path compiles for every JDBC-safe integer id.
     * {@code box()} normalizes a primitive id ({@code int} → {@code java.lang.Integer}); the Long
     * corpus renders {@code longValue()} textually identical to the pre-Task-4 hardcoded coercion, and
     * id types a {@code Number} cannot produce (String, UUID, ...) fall back to {@code longValue()} —
     * the previous (non-working for them) emission, preserving existing behavior.
     */
    private static String generatedKeyCoercion(TypeName idType) {
        return switch (idType.box().toString()) {
            case "java.lang.Integer" -> "intValue";
            case "java.lang.Short" -> "shortValue";
            case "java.lang.Byte" -> "byteValue";
            case "java.lang.Float" -> "floatValue";
            case "java.lang.Double" -> "doubleValue";
            default -> "longValue";
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
                                        Map<String, String> constants) {
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
        DescriptorModel md = entity.metadata();
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
    private static CodeBlock boundValue(DescriptorModel md, String rootVar, ColumnRef ref) {
        String[] segments = ref.path().split("\\.");
        StringBuilder chain = new StringBuilder(rootVar);
        List<String> nullChecks = new ArrayList<>();
        DescriptorModel current = md;
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
                                Map<String, String> constants) {
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

        builder.addStatement("$T cols = new $T<>()", List.class, ArrayList.class);
        builder.addStatement("$T dirs = new $T<>()", List.class, ArrayList.class);
        builder.addStatement("$T props = new $T<>()", List.class, ArrayList.class);
        builder.addStatement("$T orderBy = new $T()", StringBuilder.class, StringBuilder.class);
        builder.beginControlFlow("for ($T<?> s : $L)", Sort.class, sortBy);
        builder.addStatement("cols.add(column(s.property()))");
        builder.addStatement("dirs.add(s.isAscending())");
        builder.addStatement("props.add(s.property())");
        builder.beginControlFlow("if (orderBy.length() > 0)");
        builder.addStatement("orderBy.append($S)", ", ");
        builder.endControlFlow();
        builder.addStatement("orderBy.append($S).append(column(s.property())).append($S)"
                        + ".append(s.isAscending() ? $S : $S)", "\"", "\"", " ASC", " DESC");
        builder.endControlFlow();

        builder.beginControlFlow("if ($L.mode() == $T.Mode.OFFSET)", pageRequest, PageRequest.class);
        builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
        builder.addStatement("$T<$T> content = jdbcClient.sql($L + (orderBy.length() > 0 ? $S + orderBy : $S)"
                        + " + $S + $L.size() + $S + offset).query($L).list()",
                List.class, entity.typeName(), findConstant, " ORDER BY ", "", " LIMIT ", pageRequest, " OFFSET ",
                queryArg(entity, mapperType));
        builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                pageRequest, countConstant, Long.class);
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, pageRequest);
        builder.endControlFlow();

        builder.addStatement("$T.Cursor cursor = $L.cursor().orElseThrow()", PageRequest.class, pageRequest);
        builder.addStatement("$T where = $T.seekWhere(cols, dirs, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                String.class, CursorSupport.class, pageRequest, PageRequest.class);
        builder.addStatement("$T<$T> content = jdbcClient.sql($L + $S + where + (orderBy.length() > 0 ? $S + orderBy : $S)"
                        + " + $S + $L.size() + $S).params($T.cursorParams(cursor)).query($L).list()",
                List.class, entity.typeName(), findConstant, " WHERE ", " ORDER BY ", "", " LIMIT ", pageRequest,
                " + 1", CursorSupport.class, queryArg(entity, mapperType));
        builder.addStatement("boolean hasMore = content.size() > $L.size()", pageRequest);
        builder.beginControlFlow("if (hasMore)");
        builder.addStatement("content = content.subList(0, $L.size())", pageRequest);
        builder.endControlFlow();
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor(props, e))", CursorSupport.class);
        builder.endControlFlow();
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
    }

    private void emitPaged(MethodSpec.Builder builder, EntityModel entity, String pageRequest, List<SortSpec> sorts,
                           String findConstant, Map<String, String> constants, List<Binding> bindings,
                           List<? extends VariableElement> parameters, boolean cursored, ClassName mapperType,
                           boolean hydrate) {
        List<SortSpec> effective = sorts.isEmpty()
                ? List.of(SortSpec.of(entity.id().name(), true, false))
                : sorts;
        List<String> columns = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        for (SortSpec s : effective) {
            columns.add(SqlBuilder.resolveColumn(entity.metadata(), s.property()));
            properties.add(s.property());
            directions.add(Boolean.toString(s.ascending()));
        }
        String colsExpr = "java.util.List.of(" + quoted(columns) + ")";
        String propsExpr = "java.util.List.of(" + quoted(properties) + ")";
        String dirsExpr = "java.util.List.of(" + String.join(", ", directions) + ")";
        String orderBy = new SqlBuilder().orderBy(entity.metadata(), effective);
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
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor($L, e))", CursorSupport.class, propsExpr);
        builder.endControlFlow();
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
        builder.endControlFlow();
        builder.addStatement("$T where = $T.seekWhere($L, $L, cursor, $L.mode() == $T.Mode.CURSOR_NEXT)",
                String.class, CursorSupport.class, colsExpr, dirsExpr, pageRequest, PageRequest.class);
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
        builder.addStatement("$T<$T.Cursor> cursors = new $T<>()", List.class, PageRequest.class, ArrayList.class);
        builder.beginControlFlow("for ($T e : content)", entity.typeName());
        builder.addStatement("cursors.add($T.cursorFor($L, e))", CursorSupport.class, propsExpr);
        builder.endControlFlow();
        if (hydrate) {
            builder.addStatement("$L", hydrationCall(entity, "content"));
        }
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

    /**
     * Emits the private {@code column(String)} helper that maps an entity property (or embedded
     * dot path, e.g. {@code "address.city"}) to its SQL column, used by the paginated
     * {@code findAll} to honour the runtime {@link Sort} criteria. Iterating the descriptor's
     * flattened {@code columns()} covers id, scalars, fk columns and embedded leaf paths in one
     * loop; an EMBEDDED container contributes no case (sorting by it addresses no column).
     */
    private MethodSpec columnHelper(EntityModel entity) {
        MethodSpec.Builder helper = MethodSpec.methodBuilder("column")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "property");
        helper.beginControlFlow("switch (property)");
        for (ColumnRef column : entity.metadata().columns()) {
            helper.addStatement("case $S: return $S", column.path(), column.column());
        }
        helper.addStatement("default: throw new $T($S + property)", IllegalArgumentException.class,
                "Unknown sort property: ");
        helper.endControlFlow();
        return helper.build();
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
                          boolean hydrate) {
        String name = method.getSimpleName().toString();
        ParsedMethodName parsed = tryParse(name);
        String action = parsed != null ? parsed.action() : "find";
        List<? extends VariableElement> parameters = method.getParameters();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        List<Binding> bindings = bindings(parameters, parameterTypes, parsed);
        TypeMirror returnMirror = resolved.getReturnType();
        Kind kind = kindOf(returnMirror);

        List<Predicate> predicates = predicates(bindings, parameters);

        if ("count".equals(action) || "exists".equals(action)) {
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().countBy(entity.metadata(), predicates));
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
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().deleteBy(entity.metadata(), predicates));
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
            String constant = addConstant(constants, "SQL_" + upperSnake(name),
                    new SqlBuilder().selectBy(entity.metadata(), predicates, List.of(), 0, null));
            emitPaged(builder, entity, pageRequest, methodSorts, constant, constants, bindings, parameters,
                    kind == Kind.CURSORED_PAGE, mapperType, hydrate);
            return;
        }
        String sql = new SqlBuilder().selectBy(entity.metadata(), predicates, methodSorts,
                parsed != null ? parsed.limit() : 0, null);
        String constant = addConstant(constants, "SQL_" + upperSnake(name), sql);
        CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendBoundParameters(call, bindings, parameters);
        if (!hydrate || kind == Kind.VOID) {
            terminal(call, kind, queryArg(entity, mapperType));
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
                call.add(".query($L).list()", queryArg(entity, mapperType));
                builder.addStatement("$T<$T> results = $L", List.class, entity.typeName(), call.build());
                builder.addStatement("$L", hydrationCall(entity, "results"));
                builder.addStatement(kind == Kind.STREAM ? "return results.stream()" : "return results");
            }
            case OPTIONAL -> {
                call.add(".query($L).optional()", queryArg(entity, mapperType));
                builder.addStatement("$T<$T> found = $L", java.util.Optional.class, entity.typeName(), call.build());
                builder.addStatement("found.ifPresent(e -> $L)", hydrationCall(entity, "java.util.List.of(e)"));
                builder.addStatement("return found");
            }
            case SINGLE -> {
                call.add(".query($L).single()", queryArg(entity, mapperType));
                builder.addStatement("$T found = $L", entity.typeName(), call.build());
                builder.addStatement("$L", hydrationCall(entity, "java.util.List.of(found)"));
                builder.addStatement("return found");
            }
            default -> {
                terminal(call, kind, queryArg(entity, mapperType));
                builder.addStatement("$L", call.build());
            }
        }
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
     * parent's collection setter. The target's own relationships are NOT hydrated here (depth-1), and
     * the back-reference on hydrated children stays NULL because the target's row mapper skips the
     * inverse's fk column on read (coverage lock, ruling 6).
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
     * A relationship that cleared the Task 4 managed-target gate: the descriptor attribute, the target's
     * compile-time model (id/column/table metadata the loader reads) and its generated row mapper, the
     * loader's delegate. Produced only by {@link #generate}'s eligibility scan, so every instance is
     * emittable.
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

            if (field.getAnnotation(Identity.class) != null || (!valueObject && name.equals("id"))) {
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
                Prop.Embedded embedded = new Prop.Embedded(name, Names.columnName(name), descriptorFor(vo));
                properties.add(new Property(name, embedded.column(), fieldTypeName, embedded, voModel(vo)));
                props.add(embedded);

            } else if (isEntityType(declaredElement(field))) {
                // In-aggregate many-to-one / one-to-one: fk column, hydrated on read (Plan C).
                // Not an external ASSOCIATION: the field is left null by the mapper and the
                // loaders (Task 4) fill it, so it skips the gate as a composing Reference.
                TypeElement target = declaredElement(field);
                Prop.Reference ref = referenceProp(name, Names.fkColumn(name),
                        target.getQualifiedName().toString(), target, true);
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
                props.add(new Prop.EntityList(name, child.getQualifiedName().toString(),
                        Names.tableName(child.getSimpleName().toString()), null, null));

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
        Stereotype stereotype = stereotypeOf(element);
        DescriptorModel self = new DescriptorModel(stereotype, typeNameString, table, idProp, props);
        List<Prop> resolved = new ArrayList<>(props.size());
        for (Prop p : props) {
            if (p instanceof Prop.EntityList entityList && entityList.targetTypeName() != null) {
                TypeElement childEl = elements.getTypeElement(entityList.targetTypeName());
                DescriptorModel child = childEl != null ? descriptorFor(childEl) : null;
                String inverse = child != null ? findInverseName(self, child) : null;
                resolved.add(new Prop.EntityList(entityList.name(), entityList.targetTypeName(),
                        entityList.targetTable(), inverse, inverse == null ? null : Names.fkColumn(inverse)));
            } else {
                resolved.add(p);
            }
        }
        DescriptorModel metadata = new DescriptorModel(stereotype, typeNameString, table, idProp, resolved);
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
        DescriptorModel targetModel = target != null ? descriptorFor(target) : null;
        return new Prop.Reference(name, fkColumn, targetTypeName,
                targetModel != null ? targetModel.tableName() : null,
                targetModel != null ? targetModel.idColumn() : null,
                composition);
    }

    private ElementCollection buildCollection(String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = parentTable + "_id";
        // The value object's flattened descriptor is the single source for the child-table column list
        // and the param bindings; per the global contract an @Association field is a (non-composing)
        // Reference there, exactly as in buildEntity, and binds the target's id through boundValue.
        // The Prop.ElementList is the one source of truth for the child-table placement and columns.
        DescriptorModel target = valueObjectMetadata(valueObject);
        Prop.ElementList element = new Prop.ElementList(field, valueObject.getQualifiedName().toString(),
                childTable, parentFk, target.columns());
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
    private DescriptorModel valueObjectMetadata(TypeElement valueObject) {
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
        return new DescriptorModel(Stereotype.VALUE_OBJECT, valueObject.getQualifiedName().toString(),
                null, null, List.copyOf(props));
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
    private DescriptorModel descriptorFor(TypeElement te) {
        String fqn = te.getQualifiedName().toString();
        DescriptorModel cached = descriptorCache.get(fqn);
        if (cached != null) return cached;
        if (inProgress.contains(fqn)) {
            // Cycle: return a shallow, property-less placeholder; only typeName is consumed
            // for a back-reference, and findInverseName tolerates an empty property list.
            return new DescriptorModel(stereotypeOf(te), fqn,
                    Names.tableName(te.getSimpleName().toString()), null, List.of());
        }
        inProgress.add(fqn);
        try {
            DescriptorModel d = buildEntity(te).metadata();
            descriptorCache.put(fqn, d);
            return d;
        } finally {
            inProgress.remove(fqn);
        }
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
    private String findInverseName(DescriptorModel parent, DescriptorModel child) {
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
                                     Prop.ElementList element, DescriptorModel descriptor) {
        String childTable() {
            return element.childTable();
        }

        String parentFk() {
            return element.parentFkColumn();
        }
    }

    private record EntityModel(TypeMirror typeMirror, TypeName typeName, Property id,
                               List<Property> properties, List<ElementCollection> collections,
                               DescriptorModel metadata) { }

    private record Binding(String property, Operator operator, int paramIndex) { }

    private enum Kind { LIST, STREAM, OPTIONAL, SINGLE, VOID, PAGE, CURSORED_PAGE }
}
