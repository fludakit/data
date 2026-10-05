package io.github.fludakit.data.processor;

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
import io.github.fludakit.data.metadata.EntityDescriptor;
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
 * Generates three artifacts at compile time: a {@code <Repository>Impl} class for every
 * {@link Repository @Repository} interface, a reflection-free {@code <Entity>Metadata} class
 * implementing {@link EntityDescriptor} (the compile-time descriptor model behind all SQL), and a
 * {@code <Entity>RowMapper} for entities whose fields are all JDBC-safe or embeddable value objects.
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

    /** Entity/value-object FQN → compile-time descriptor, memoized for {@link #descriptorFor}. */
    private final Map<String, Descriptor> descriptorCache = new HashMap<>();

    /** FQNs whose descriptor is currently being built; the cycle guard for {@link #descriptorFor}. */
    private final Set<String> inProgress = new HashSet<>();

    /** Embedded value-object FQN → compile-time model, memoized for {@link #voModel}. */
    private final Map<String, EntityModel> voModels = new HashMap<>();

    /** FQNs whose embedded model is currently on the build stack; the cycle guard for {@link #voModel}. */
    private final Set<String> voModelsInProgress = new HashSet<>();

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
        boolean claimed = false;
        for (Element element : roundEnv.getElementsAnnotatedWith(Repository.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            generate((TypeElement) element);
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
        ClassName mapperType = null;
        if (canGenerateRowMapper(entity)) {
            String entityFqn = entityElement.getQualifiedName().toString();
            mapperType = rowMappers.get(entityFqn);
            if (mapperType == null) {
                ClassName metadataType = generateMetadata(entityElement, entity);
                metadataTypes.put(entityFqn, metadataType);
                mapperType = generateRowMapper(entityElement, entity, metadataType);
                rowMappers.put(entityFqn, mapperType);
            }
        }
        // EMBEDDED value objects carry descriptor metadata even when the owner's mapper gate skips
        // them; the generated <VO>Metadata classes are what the owner's attributes() reference.
        generateEmbeddedDescriptors(entity.metadata());
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
            methods.add(generateMethod(method, repositoryType, entity, crud, constants, mapperType));
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
            if (p.association() || p.kind() == EntityDescriptor.Kind.MANY_TO_ONE) {
                continue; // FK-only or hydrated by loaders (Tasks 3-4)
            }
            if (isJdbcSafe(p.type())) {
                continue;
            }
            if (p.kind() == EntityDescriptor.Kind.EMBEDDED && p.embeddedModel() != null
                    && canGenerateRowMapper(p.embeddedModel())) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Emits {@code <Entity>Metadata implements EntityDescriptor}: Plan A's column-name constants
     * (one per mapped property plus the table name) alongside the descriptor view — {@code INSTANCE},
     * {@code typeName()}, {@code tableName()}, {@code id()} and {@code attributes()}. A value
     * object (EMBEDDED target) has no table and no id, so its TABLE_NAME constant is omitted and the
     * matching accessors return {@code null}. An EMBEDDED property contributes no constant of its
     * own — its {@code column} slot holds a leaf prefix, so no physical column named after the
     * property exists — but each flattened leaf of its value-object descriptor gets a constant
     * {@code <PREFIX>_<LEAF>} named {@code <prefix>_<leaf>} (Decision 3; nesting is resolved inside
     * the descriptor, so one loop per embedded attribute suffices).
     */
    private ClassName generateMetadata(TypeElement entityElement, EntityModel entity) {
        String packageName = elements.getPackageOf(entityElement).getQualifiedName().toString();
        String simpleName = entityElement.getSimpleName() + "Metadata";
        ClassName self = ClassName.get(packageName, simpleName);
        ClassName descriptor = ClassName.get("io.github.fludakit.data.metadata", "EntityDescriptor");
        ClassName attribute = descriptor.nestedClass("Attribute");
        ClassName kind = descriptor.nestedClass("Kind");

        TypeSpec.Builder builder = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(descriptor)
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
        for (EntityDescriptor.Attribute a : entity.metadata().attributes()) {
            if (a.kind() == EntityDescriptor.Kind.EMBEDDED) {
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
        for (EntityDescriptor.Attribute a : entity.metadata().attributes()) {
            if (a.kind() != EntityDescriptor.Kind.EMBEDDED || a.embedded() == null) {
                continue;
            }
            for (EntityDescriptor.ColumnRef leaf : a.embedded().columns()) {
                builder.addField(stringConstant(
                        Names.toSnakeCase(a.column() + "_" + leaf.column()).toUpperCase(),
                        a.column() + "_" + leaf.column()));
            }
        }

        builder.addMethod(stringAccessor("typeName", entityElement.getQualifiedName().toString()));
        builder.addMethod(stringAccessor("tableName", entity.metadata().tableName()));
        EntityDescriptor.Attribute idAttribute = entity.metadata().id();
        if (idAttribute != null) {
            builder.addMethod(attributeAccessor("id", idAttribute, attribute, kind));
        } else {
            builder.addMethod(MethodSpec.methodBuilder("id").addModifiers(Modifier.PUBLIC)
                    .addAnnotation(Override.class).returns(attribute)
                    .addStatement("return null").build());
        }
        List<EntityDescriptor.Attribute> attrs = entity.metadata().attributes();
        CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", ClassName.get(java.util.List.class));
        for (int i = 0; i < attrs.size(); i++) {
            if (i > 0) {
                list.add(", ");
            }
            list.add(attributeLiteral(attrs.get(i), attribute, kind));
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
    private void generateEmbeddedDescriptors(EntityDescriptor owner) {
        for (EntityDescriptor.Attribute a : owner.attributes()) {
            if (a.kind() != EntityDescriptor.Kind.EMBEDDED || a.embedded() == null) {
                continue;
            }
            String voFqn = a.embedded().typeName();
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
            generateMetadata(voElement, voModel);
        }
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

    private MethodSpec attributeAccessor(String name, EntityDescriptor.Attribute attributeValue,
                                         ClassName attribute, ClassName kind) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class).returns(attribute)
                .addStatement("return $L", attributeLiteral(attributeValue, attribute, kind)).build();
    }

    /** Renders one {@code new EntityDescriptor.Attribute(...)} literal with the record's 7 components. */
    private CodeBlock attributeLiteral(EntityDescriptor.Attribute a, ClassName attribute, ClassName kind) {
        CodeBlock embedded = a.embedded() == null
                ? CodeBlock.of("null")
                : CodeBlock.of("$T.INSTANCE", embeddedMetadataName(a.embedded().typeName()));
        return CodeBlock.of("new $T($L, $L, $T.$L, $L, $L, $L, $L)",
                attribute,
                stringLiteral(a.name()), stringLiteral(a.typeName()),
                kind, a.kind().name(),
                stringLiteral(a.column()), stringLiteral(a.targetTypeName()), stringLiteral(a.inverseName()),
                embedded);
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
            if (p.association() || p.kind() == EntityDescriptor.Kind.MANY_TO_ONE) {
                continue; // FK-only reference; left null on read (loaders fill it in Tasks 3-4)
            }
            if (p.kind() == EntityDescriptor.Kind.EMBEDDED) {
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
     * ({@code <prefix>_<leaf>}, e.g. {@code address_point_lat}); the tree is then constructed
     * post-order — deepest child first — inside a single guard that fires iff at least one boxed
     * leaf is non-null. Primitive leaves follow the {@link #appendPropertyRead} temp+{@code wasNull()}
     * rule and never join the presence test, so a VO with a primitive leaf cannot be NULL-absent in
     * the database. The whole block stays reflection-free.
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
            if (p.association() || p.kind() == EntityDescriptor.Kind.MANY_TO_ONE) {
                continue; // fk-only children stay null on read, as at owner level
            }
            if (p.kind() == EntityDescriptor.Kind.EMBEDDED) {
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
     * Constructs one value-object instance from its already-read leaf locals: nested children are
     * emitted first (post-order, mirroring {@link #generateEmbeddedDescriptors}) so every child local
     * exists before this node's setters reference it.
     */
    private void buildEmbeddedValue(MethodSpec.Builder mapRow, EntityModel vo, String prefix, String local) {
        for (Property p : vo.properties()) {
            if (p.kind() == EntityDescriptor.Kind.EMBEDDED) {
                buildEmbeddedValue(mapRow, p.embeddedModel(), prefix + "_" + p.column(), prefix + "_" + p.column());
            }
        }
        mapRow.addStatement("$T $L = new $T()", vo.typeName(), local, vo.typeName());
        for (Property p : vo.properties()) {
            if (p.association() || p.kind() == EntityDescriptor.Kind.MANY_TO_ONE) {
                continue;
            }
            mapRow.addStatement("$L.$L($L)", local, setter(p.name()), prefix + "_" + p.column());
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
                                      boolean crud, Map<String, String> constants, ClassName mapperType) {
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
            emitFindById(builder, parameterName(parameters.get(0), "id"), entity, constants, mapperType);
            return builder.build();
        }
        if (arity == 1 && name.equals("deleteById")) {
            emitDeleteById(builder, parameterName(parameters.get(0), "id"), entity, constants);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 0) {
            emitFindAll(builder, entity, constants, mapperType);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 2) {
            emitFindAllPage(builder, parameters, entity, constants, mapperType);
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

        emitFind(builder, method, resolved, entity, constants, mapperType);
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
        builder.addStatement("$L.$L((($T) keys.getKey()).longValue())",
                value, setter(entity.id().name()), Number.class);
        builder.endControlFlow();

        for (ElementCollection collection : entity.collections()) {
            appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
    }

    private void appendElementCollectionInsert(MethodSpec.Builder builder, String value, EntityModel entity,
                                               ElementCollection collection, Map<String, String> constants) {
        List<String> columns = new ArrayList<>();
        columns.add(collection.parentFk());
        for (Property property : collection.properties()) {
            columns.add(property.column());
        }
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
        // property columns (:product_id, :quantity), so the params must match them. The value still
        // walks the descriptor path through boundValue, which makes fk-bearing children bind their
        // id exactly like the previous property-driven loop did.
        for (EntityDescriptor.ColumnRef ref : collection.descriptor().columns()) {
            insert.add(".param($S, $L)", ref.column(), boundValue(collection.descriptor(), "item", ref));
        }
        insert.add(".update()");
        builder.addStatement("$L", insert.build());
        builder.endControlFlow();
        builder.endControlFlow();
    }

    private void appendUpdateStatements(MethodSpec.Builder builder, String value, EntityModel entity,
                                        Map<String, String> constants) {
        String constant = addConstant(constants, "SQL_UPDATE", new SqlBuilder().update(entity.metadata()));

        CodeBlock.Builder update = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendColumnParams(update, entity, value);
        update.add(".param($S, $L.$L())", "id", value, getter(entity.id().name()));
        update.add(".update()");
        builder.addStatement("$L", update.build());
    }

    /**
     * Binds one {@code .param(...)} per physical column exactly as {@link SqlBuilder} renders it:
     * the name is {@code paramName(path)} and the value walks the dot-path. The identifier is
     * skipped by COLUMN EQUALITY, mirroring {@code SqlBuilder.insert(md, false)} and
     * {@code SqlBuilder.update(md)} (which both also exclude the id from SET); any other predicate
     * would drift against the rendered placeholders.
     */
    private void appendColumnParams(CodeBlock.Builder sql, EntityModel entity, String var) {
        EntityDescriptor md = entity.metadata();
        for (EntityDescriptor.ColumnRef ref : md.columns()) {
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
    private static CodeBlock boundValue(EntityDescriptor md, String rootVar, EntityDescriptor.ColumnRef ref) {
        String[] segments = ref.path().split("\\.");
        StringBuilder chain = new StringBuilder(rootVar);
        List<String> nullChecks = new ArrayList<>();
        EntityDescriptor current = md;
        for (int i = 0; i < segments.length; i++) {
            EntityDescriptor.Attribute attr = current.attribute(segments[i]);
            chain.append('.').append(getter(segments[i])).append("()");
            if (i < segments.length - 1) {
                nullChecks.add(chain.toString());
                current = attr.embedded();
            } else if (attr.kind() == EntityDescriptor.Kind.ASSOCIATION
                    || attr.kind() == EntityDescriptor.Kind.MANY_TO_ONE) {
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
                              Map<String, String> constants, ClassName mapperType) {
        String constant = addConstant(constants, "SQL_FIND_BY_ID",
                new SqlBuilder().selectBy(entity.metadata(),
                        List.of(Predicate.of(entity.id().name(), Operator.EQ, "id")), List.of(), 0, null));
        builder.addStatement("return jdbcClient.sql($L).param($S, $L).query($L).optional()",
                constant, "id", value, queryArg(entity, mapperType));
    }

    private void emitDeleteById(MethodSpec.Builder builder, String value, EntityModel entity,
                                Map<String, String> constants) {
        String constant = addConstant(constants, "SQL_DELETE", new SqlBuilder().deleteById(entity.metadata()));
        builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", constant, "id", value);
    }

    private void emitFindAll(MethodSpec.Builder builder, EntityModel entity, Map<String, String> constants,
                             ClassName mapperType) {
        String constant = addConstant(constants, "SQL_FIND_ALL",
                new SqlBuilder().selectBy(entity.metadata(), List.of(), List.of(), 0, null));
        builder.addStatement("return jdbcClient.sql($L).query($L).stream()",
                constant, queryArg(entity, mapperType));
    }

    private void emitFindAllPage(MethodSpec.Builder builder, List<? extends VariableElement> parameters,
                                 EntityModel entity, Map<String, String> constants, ClassName mapperType) {
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
        builder.addStatement("return new $T<>($L, content, cursors, -1L)", SimpleCursoredPage.class, pageRequest);
    }

    private void emitPaged(MethodSpec.Builder builder, EntityModel entity, String pageRequest, List<SortSpec> sorts,
                           String findConstant, Map<String, String> constants, List<Binding> bindings,
                           List<? extends VariableElement> parameters, boolean cursored, ClassName mapperType) {
        List<SortSpec> effective = sorts.isEmpty()
                ? List.of(SortSpec.of(entity.id().name(), true, false))
                : sorts;
        List<String> columns = new ArrayList<>();
        List<String> properties = new ArrayList<>();
        List<String> directions = new ArrayList<>();
        for (SortSpec s : effective) {
            columns.add(Names.columnName(s.property()));
            properties.add(s.property());
            directions.add(Boolean.toString(s.ascending()));
        }
        String colsExpr = "java.util.List.of(" + quoted(columns) + ")";
        String propsExpr = "java.util.List.of(" + quoted(properties) + ")";
        String dirsExpr = "java.util.List.of(" + String.join(", ", directions) + ")";
        String orderBy = new SqlBuilder().orderBy(effective);
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
     * Emits the private {@code column(String)} helper that maps an entity property name to its SQL column,
     * used by the paginated {@code findAll} to honour the runtime {@link Sort} criteria. Every scalar column
     * and the identifier are mapped; associations map to their foreign-key column.
     */
    private MethodSpec columnHelper(EntityModel entity) {
        MethodSpec.Builder helper = MethodSpec.methodBuilder("column")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "property");
        helper.beginControlFlow("switch (property)");
        helper.addStatement("case $S: return $S", entity.id().name(), entity.id().column());
        for (Property property : entity.properties()) {
            helper.addStatement("case $S: return $S", property.name(), property.column());
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
                          EntityModel entity, Map<String, String> constants, ClassName mapperType) {
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
                    kind == Kind.CURSORED_PAGE, mapperType);
            return;
        }
        String sql = new SqlBuilder().selectBy(entity.metadata(), predicates, methodSorts,
                parsed != null ? parsed.limit() : 0, null);
        String constant = addConstant(constants, "SQL_" + upperSnake(name), sql);
        CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendBoundParameters(call, bindings, parameters);
        terminal(call, kind, queryArg(entity, mapperType));
        if (kind == Kind.VOID) {
            builder.addStatement("$L", call.build());
        } else {
            builder.addStatement("return $L", call.build());
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
        EntityDescriptor.Attribute idAttr = null;
        List<Property> properties = new ArrayList<>();
        List<EntityDescriptor.Attribute> attrs = new ArrayList<>();
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
                id = new Property(name, Names.columnName(name), false, fieldTypeName);
                idAttr = new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.ID,
                        Names.columnName(name), null, null, null);

            } else if (field.getAnnotation(Association.class) != null) {
                // External aggregate reference: fk-only, never hydrated.
                properties.add(new Property(name, Names.fkColumn(name), true, fieldTypeName));
                TypeElement target = declaredElement(field);
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.ASSOCIATION,
                        Names.fkColumn(name),
                        target != null ? target.getQualifiedName().toString() : null, null, null));

            } else if (embeddedTarget(field) != null) {
                // Direct value object: flatten leaves into this table. The Property carries the VO's
                // compile-time model so the mapper gate and the embedded read emitter can recurse.
                TypeElement vo = embeddedTarget(field);
                Descriptor embedded = descriptorFor(vo);
                properties.add(new Property(name, Names.columnName(name), false, fieldTypeName,
                        EntityDescriptor.Kind.EMBEDDED, voModel(vo)));
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.EMBEDDED,
                        Names.columnName(name), vo.getQualifiedName().toString(), null, embedded));

            } else if (isEntityType(declaredElement(field))) {
                // In-aggregate many-to-one / one-to-one: fk column, hydrated on read (Plan C).
                // Not an external ASSOCIATION: the field is left null by the mapper and the
                // loaders (Task 4) fill it, so it skips the gate via its kind, not the flag.
                TypeElement target = declaredElement(field);
                properties.add(new Property(name, Names.fkColumn(name), false, fieldTypeName,
                        EntityDescriptor.Kind.MANY_TO_ONE, null));
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.MANY_TO_ONE,
                        Names.fkColumn(name), target.getQualifiedName().toString(), null, null));

            } else if (valueObjectElementType(field) != null) {
                // List<@ValueObject>: element collection in a child table.
                TypeElement vo = valueObjectElementType(field);
                ElementCollection collection = buildCollection(table, name, vo);
                collections.add(collection);
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.ELEMENT_COLLECTION,
                        null, vo.getQualifiedName().toString(), null, null));

            } else if (entityTypeElement(field) != null) {
                // List<@Entity>: one-to-many across the child's own table. The inverse is resolved
                // in the second pass below, so the placeholder inverse stays null here.
                TypeElement child = entityTypeElement(field);
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.ONE_TO_MANY,
                        null, child.getQualifiedName().toString(), null, null));

            } else {
                properties.add(new Property(name, Names.columnName(name), false, fieldTypeName));
                attrs.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.SCALAR,
                        Names.columnName(name), null, null, null));
            }
        }
        if (!valueObject && id == null) {
            id = new Property("id", Names.columnName("id"), false, ClassName.get(Long.class));
            idAttr = new EntityDescriptor.Attribute("id", "java.lang.Long", EntityDescriptor.Kind.ID,
                    Names.columnName("id"), null, null, null);
        }
        // Second pass: resolve ONE_TO_MANY inverses now that all parent attributes are known.
        Descriptor self = new Descriptor(typeNameString, table, idAttr, attrs);
        List<EntityDescriptor.Attribute> resolved = new ArrayList<>(attrs.size());
        for (EntityDescriptor.Attribute a : attrs) {
            if (a.kind() == EntityDescriptor.Kind.ONE_TO_MANY && a.targetTypeName() != null) {
                TypeElement childEl = elements.getTypeElement(a.targetTypeName());
                Descriptor child = childEl != null ? descriptorFor(childEl) : null;
                String inverse = child != null ? findInverseName(self, child) : null;
                resolved.add(new EntityDescriptor.Attribute(a.name(), a.typeName(), a.kind(), a.column(),
                        a.targetTypeName(), inverse, a.embedded()));
            } else {
                resolved.add(a);
            }
        }
        EntityDescriptor metadata = new Descriptor(typeNameString, table, idAttr, resolved);
        return new EntityModel(typeMirror, typeName, id, properties, collections, metadata);
    }

    private ElementCollection buildCollection(String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = parentTable + "_id";
        // The child columns are classified by the value-object-local classifier below; per the
        // global contract an @Association field is Kind.ASSOCIATION there, exactly as in
        // buildEntity. Both fk-bearing relationship kinds (MANY_TO_ONE for an in-aggregate target
        // and ASSOCIATION for an external reference) bind the target's id, so the association flag
        // must accept BOTH: dropping either label here silently breaks the child-table fk binding.
        EntityDescriptor target = valueObjectMetadata(valueObject);
        List<Property> properties = new ArrayList<>();
        for (EntityDescriptor.Attribute attribute : target.attributes()) {
            properties.add(new Property(attribute.name(), attribute.column(),
                    attribute.kind() == EntityDescriptor.Kind.MANY_TO_ONE
                            || attribute.kind() == EntityDescriptor.Kind.ASSOCIATION,
                    typeNameOfString(attribute.typeName()), attribute.kind(), null));
        }
        return new ElementCollection(field, TypeName.get(types.erasure(valueObject.asType())),
                childTable, parentFk, properties, target);
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
     * Converts a metadata type-name string (as recorded on a {@code @ValueObject} property) back into
     * a {@link TypeName}. Element-collection children are not mapped in this iteration, so this is
     * best-effort: primitives map to their {@link TypeName} constants and anything
     * {@link ClassName#bestGuess} cannot parse (arrays, parameterized types) falls back to
     * {@link TypeName#OBJECT} instead of aborting annotation processing.
     */
    private static TypeName typeNameOfString(String typeName) {
        switch (typeName) {
            case "boolean": return TypeName.BOOLEAN;
            case "byte": return TypeName.BYTE;
            case "short": return TypeName.SHORT;
            case "int": return TypeName.INT;
            case "long": return TypeName.LONG;
            case "float": return TypeName.FLOAT;
            case "double": return TypeName.DOUBLE;
            case "char": return TypeName.CHAR;
            default:
                try {
                    return ClassName.bestGuess(typeName);
                } catch (IllegalArgumentException e) {
                    return TypeName.OBJECT;
                }
        }
    }

    /**
     * Builds the id-less descriptor for a {@code List<@ValueObject>} element type. Classification
     * follows the global contract: {@code @Association} → {@code ASSOCIATION} (never
     * {@code MANY_TO_ONE}); everything else is a {@code SCALAR} column.
     */
    private EntityDescriptor valueObjectMetadata(TypeElement valueObject) {
        List<EntityDescriptor.Attribute> attributes = new ArrayList<>();
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
                // Mirror buildEntity's ASSOCIATION branch: carry the target's FQN so Tasks 2-4
                // (relationship hydration / embedded mapping) can resolve the association target.
                TypeElement target = declaredElement(field);
                attributes.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.ASSOCIATION,
                        Names.fkColumn(name), target != null ? target.getQualifiedName().toString() : null,
                        null, null));
            } else {
                attributes.add(new EntityDescriptor.Attribute(name, fieldType, EntityDescriptor.Kind.SCALAR,
                        Names.columnName(name), null, null, null));
            }
        }
        return new Descriptor(valueObject.getQualifiedName().toString(), null, null, List.copyOf(attributes));
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
     * a descriptor already being built returns a shallow attribute-less placeholder (only
     * {@code targetTypeName} is consumed for a back-reference).
     */
    private Descriptor descriptorFor(TypeElement te) {
        String fqn = te.getQualifiedName().toString();
        Descriptor cached = descriptorCache.get(fqn);
        if (cached != null) return cached;
        if (inProgress.contains(fqn)) {
            // Cycle: return a shallow, attribute-less placeholder; only targetTypeName is consumed
            // for a back-reference, and findInverseName tolerates an empty attribute list.
            return new Descriptor(fqn, Names.tableName(te.getSimpleName().toString()), null, List.of());
        }
        inProgress.add(fqn);
        try {
            Descriptor d = (Descriptor) buildEntity(te).metadata();
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
     * Scans the child's attributes for a back-reference to the parent; a cyclic child whose descriptor
     * is the shallow placeholder yields {@code null}, which Plan C's hydration resolves instead from
     * the child's own generated metadata. An unresolved inverse is treated as read-only/unmapped.
     */
    private String findInverseName(Descriptor parent, Descriptor child) {
        for (EntityDescriptor.Attribute a : child.attributes()) {
            if ((a.kind() == EntityDescriptor.Kind.MANY_TO_ONE || a.kind() == EntityDescriptor.Kind.ASSOCIATION)
                    && parent.typeName().equals(a.targetTypeName())) {
                return a.name();
            }
        }
        return null; // unresolved inverse: hydration (Plan C) will treat this as read-only/unmapped
    }

    // --------------------------------------------------------------- nested types

    /**
     * A mapped non-id property. {@code kind} carries the descriptor classification the gate and the
     * codegen loops need (embedded recursion, fk-only skips); {@code embeddedModel} is the compile-time
     * model of an {@code EMBEDDED} target, {@code null} otherwise or when the VO build cycles. Call sites
     * that do not consume the new components use the 4-arg convenience constructor.
     */
    private record Property(String name, String column, boolean association, TypeName type,
                            EntityDescriptor.Kind kind, EntityModel embeddedModel) {
        Property(String name, String column, boolean association, TypeName type) {
            this(name, column, association, type, association ? EntityDescriptor.Kind.ASSOCIATION
                    : EntityDescriptor.Kind.SCALAR, null);
        }
    }

    private record Descriptor(String typeName, String tableName,
                              EntityDescriptor.Attribute id,
                              List<EntityDescriptor.Attribute> attributes) implements EntityDescriptor {
        Descriptor {
            // Defensive copy: the immutability invariant of a descriptor must not rely on
            // call-site discipline.
            attributes = List.copyOf(attributes);
        }
    }

    /**
     * A {@code List<@ValueObject>} element collection. {@code properties} drives the child-table column
     * list; {@code descriptor} (the value object's flattened column view) drives the param bindings so
     * fk-bearing children resolve through the same {@link #boundValue} walk as parent-entity paths.
     */
    private record ElementCollection(String field, TypeName valueObjectType, String childTable,
                                     String parentFk, List<Property> properties,
                                     EntityDescriptor descriptor) { }

    private record EntityModel(TypeMirror typeMirror, TypeName typeName, Property id,
                               List<Property> properties, List<ElementCollection> collections,
                               EntityDescriptor metadata) { }

    private record Binding(String property, Operator operator, int paramIndex) { }

    private enum Kind { LIST, STREAM, OPTIONAL, SINGLE, VOID, PAGE, CURSORED_PAGE }
}
