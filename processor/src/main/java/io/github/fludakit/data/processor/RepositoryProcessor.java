package io.github.fludakit.data.processor;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.annotation.Association;
import io.github.fludakit.data.annotation.Identity;
import io.github.fludakit.data.annotation.ValueObject;
import io.github.fludakit.data.page.SimplePage;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.jdbc.JdbcClient;
import io.github.fludakit.jdbc.support.GeneratedKeyHolder;
import jakarta.data.Sort;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates a {@code <Repository>Impl} class for every {@link Repository @Repository} interface.
 *
 * <p>The generated class is {@link ApplicationScoped} and carries a single injected collaborator,
 * a {@link JdbcClient}. Every query's SQL is emitted as a {@code private static final String}
 * constant and executed with direct {@code jdbcClient.sql(...).param(...).query(...)} calls: there
 * is no runtime query model and no engine indirection. The entity's table/columns are derived at
 * compile time from its fields by convention (see {@link Names}).</p>
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

    private Types types;
    private Elements elements;

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
            methods.add(generateMethod(method, repositoryType, entity, crud, constants));
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

        JavaFile javaFile = JavaFile.builder(packageName, classBuilder.build())
                .skipJavaLangImports(true)
                .build();
        try {
            JavaFileObject sourceFile = processingEnv.getFiler()
                    .createSourceFile(packageName + "." + implementationName, repository);
            try (Writer writer = sourceFile.openWriter()) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write " + implementationName + ": " + e.getMessage(), repository);
        }
    }

    // ------------------------------------------------------------- method dispatch

    private MethodSpec generateMethod(ExecutableElement method, DeclaredType repositoryType, EntityModel entity,
                                      boolean crud, Map<String, String> constants) {
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
            emitFindById(builder, parameterName(parameters.get(0), "id"), entity, constants);
            return builder.build();
        }
        if (arity == 1 && name.equals("deleteById")) {
            emitDeleteById(builder, parameterName(parameters.get(0), "id"), entity, constants);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 0) {
            emitFindAll(builder, entity, constants);
            return builder.build();
        }
        if (name.equals("findAll") && arity == 2) {
            emitFindAllPage(builder, parameters, entity, constants);
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

        emitFind(builder, method, resolved, entity, constants);
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
        String constant = addConstant(constants, "SQL_INSERT", insertSql(entity));
        builder.addStatement("$T keys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        for (Property property : entity.properties()) {
            sql.add(".param($S, $L)", property.column(), readValue(property, value));
        }
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
        for (Property property : collection.properties()) {
            insert.add(".param($S, $L)", property.column(), readValue(property, "item"));
        }
        insert.add(".update()");
        builder.addStatement("$L", insert.build());
        builder.endControlFlow();
        builder.endControlFlow();
    }

    private void appendUpdateStatements(MethodSpec.Builder builder, String value, EntityModel entity,
                                        Map<String, String> constants) {
        List<Property> updated = entity.properties();
        StringBuilder sql = new StringBuilder("UPDATE ").append(Names.quote(entity.table())).append(" SET ");
        for (int i = 0; i < updated.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(Names.quote(updated.get(i).column())).append(" = :").append(updated.get(i).column());
        }
        sql.append(" WHERE ").append(Names.quote(entity.id().column())).append(" = :id");
        String constant = addConstant(constants, "SQL_UPDATE", sql.toString());

        CodeBlock.Builder update = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        for (Property property : updated) {
            update.add(".param($S, $L)", property.column(), readValue(property, value));
        }
        update.add(".param($S, $L.$L())", "id", value, getter(entity.id().name()));
        update.add(".update()");
        builder.addStatement("$L", update.build());
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
                              Map<String, String> constants) {
        String constant = addConstant(constants, "SQL_FIND_BY_ID",
                "SELECT * FROM " + Names.quote(entity.table()) + " WHERE " + Names.quote(entity.id().column()) + " = :id");
        builder.addStatement("return jdbcClient.sql($L).param($S, $L).query($T.class).optional()",
                constant, "id", value, entity.typeName());
    }

    private void emitDeleteById(MethodSpec.Builder builder, String value, EntityModel entity,
                                Map<String, String> constants) {
        String constant = addConstant(constants, "SQL_DELETE",
                "DELETE FROM " + Names.quote(entity.table()) + " WHERE " + Names.quote(entity.id().column()) + " = :id");
        builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", constant, "id", value);
    }

    private void emitFindAll(MethodSpec.Builder builder, EntityModel entity, Map<String, String> constants) {
        String constant = addConstant(constants, "SQL_FIND_ALL", "SELECT * FROM " + Names.quote(entity.table()));
        builder.addStatement("return jdbcClient.sql($L).query($T.class).stream()", constant, entity.typeName());
    }

    private void emitFindAllPage(MethodSpec.Builder builder, List<? extends VariableElement> parameters,
                                 EntityModel entity, Map<String, String> constants) {
        String pageRequest = parameterName(parameters.get(0), "pageRequest");
        String sortBy = parameterName(parameters.get(1), "sortBy");
        String findConstant = addConstant(constants, "SQL_FIND_ALL", "SELECT * FROM " + Names.quote(entity.table()));
        String countConstant = addConstant(constants, "SQL_COUNT_ALL", "SELECT COUNT(*) FROM " + Names.quote(entity.table()));

        builder.addStatement("long offset = ($L.page() - 1) * $L.size()", pageRequest, pageRequest);
        builder.addStatement("$T orderBy = new $T()", StringBuilder.class, StringBuilder.class);
        builder.beginControlFlow("for ($T<?> s : $L)", Sort.class, sortBy);
        builder.beginControlFlow("if (orderBy.length() > 0)");
        builder.addStatement("orderBy.append($S)", ", ");
        builder.endControlFlow();
        builder.addStatement("orderBy.append($S).append(column(s.property())).append($S)"
                        + ".append(s.isAscending() ? $S : $S)", "\"", "\"", " ASC", " DESC");
        builder.endControlFlow();
        builder.addStatement("$T<$T> content = jdbcClient.sql($L + (orderBy.length() > 0 ? $S + orderBy : $S)"
                        + " + $S + $L.size() + $S + offset).query($T.class).list()",
                List.class, entity.typeName(), findConstant, " ORDER BY ", "", " LIMIT ", pageRequest, " OFFSET ",
                entity.typeName());
        builder.addStatement("long total = $L.requestTotal() ? jdbcClient.sql($L).singleValue($T.class) : -1L",
                pageRequest, countConstant, Long.class);
        builder.addStatement("return new $T<>($L, content, total)", SimplePage.class, pageRequest);
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
        terminal(sql, kind, queryType);
        if (kind == Kind.VOID) {
            builder.addStatement("$L", sql.build());
        } else {
            builder.addStatement("return $L", sql.build());
        }
    }

    // ------------------------------------------------------------- find / by-method-name

    private void emitFind(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                          EntityModel entity, Map<String, String> constants) {
        String name = method.getSimpleName().toString();
        ParsedMethodName parsed = tryParse(name);
        String action = parsed != null ? parsed.action() : "find";
        List<? extends VariableElement> parameters = method.getParameters();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        List<Binding> bindings = bindings(parameters, parameterTypes, parsed);
        TypeMirror returnMirror = resolved.getReturnType();
        Kind kind = kindOf(returnMirror);

        if ("count".equals(action) || "exists".equals(action)) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(Names.quote(entity.table()));
            appendWhere(sql, bindings, parameters);
            String constant = addConstant(constants, "SQL_" + upperSnake(name), sql.toString());
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
            StringBuilder sql = new StringBuilder("DELETE FROM ").append(Names.quote(entity.table()));
            appendWhere(sql, bindings, parameters);
            String constant = addConstant(constants, "SQL_" + upperSnake(name), sql.toString());
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

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(Names.quote(entity.table()));
        appendWhere(sql, bindings, parameters);
        appendOrderBy(sql, sorts(method, parsed));
        if (parsed != null && parsed.limit() > 0) {
            sql.append(" LIMIT ").append(parsed.limit());
        }
        String constant = addConstant(constants, "SQL_" + upperSnake(name), sql.toString());
        CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendBoundParameters(call, bindings, parameters);
        terminal(call, kind, entity.typeName());
        if (kind == Kind.VOID) {
            builder.addStatement("$L", call.build());
        } else {
            builder.addStatement("return $L", call.build());
        }
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

    private void appendWhere(StringBuilder sql, List<Binding> bindings,
                             List<? extends VariableElement> parameters) {
        StringBuilder clause = new StringBuilder();
        for (Binding binding : bindings) {
            Operator operator = binding.operator();
            String column = Names.quote(Names.columnName(binding.property()));
            String param = parameters.get(binding.paramIndex()).getSimpleName().toString();
            String fragment;
            if (operator == Operator.IS_NULL || operator == Operator.IS_NOT_NULL
                    || operator == Operator.TRUE || operator == Operator.FALSE) {
                fragment = column + " " + operator.sql();
            } else if (operator == Operator.IGNORE_CASE_EQ) {
                fragment = "LOWER(" + column + ") = LOWER(:" + param + ")";
            } else {
                fragment = column + " " + operator.sql() + " :" + param;
            }
            if (clause.length() > 0) {
                clause.append(" AND ");
            }
            clause.append(fragment);
        }
        if (clause.length() > 0) {
            sql.append(" WHERE ").append(clause);
        }
    }

    private void appendOrderBy(StringBuilder sql, List<SortSpec> sorts) {
        if (sorts.isEmpty()) {
            return;
        }
        sql.append(" ORDER BY ");
        for (int i = 0; i < sorts.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            SortSpec sort = sorts.get(i);
            sql.append(Names.quote(Names.columnName(sort.property())))
                    .append(sort.ascending() ? " ASC" : " DESC");
        }
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

    private void terminal(CodeBlock.Builder call, Kind kind, TypeName queryType) {
        switch (kind) {
            case LIST -> call.add(".query($T.class).list()", queryType);
            case STREAM -> call.add(".query($T.class).stream()", queryType);
            case OPTIONAL -> call.add(".query($T.class).optional()", queryType);
            case VOID -> call.add(".update()");
            case SINGLE -> call.add(".query($T.class).single()", queryType);
        }
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

    private String insertSql(EntityModel entity) {
        List<Property> columns = entity.properties();
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(Names.quote(entity.table())).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(Names.quote(columns.get(i).column()));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(':').append(columns.get(i).column());
        }
        return sql.append(')').toString();
    }

    private CodeBlock readValue(Property property, String variable) {
        String accessor = variable + "." + getter(property.name()) + "()";
        if (property.association()) {
            return CodeBlock.of("$L == null ? null : $L.getId()", accessor, accessor);
        }
        return CodeBlock.of("$L", accessor);
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
        TypeMirror typeMirror = types.erasure(element.asType());
        TypeName typeName = TypeName.get(typeMirror);
        String table = Names.toSnakeCase(element.getSimpleName().toString());
        Property id = null;
        List<Property> properties = new ArrayList<>();
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
            TypeElement valueObject = valueObjectElement(field);
            if (field.getAnnotation(Identity.class) != null || name.equals("id")) {
                id = new Property(name, Names.columnName(name), false);
            } else if (field.getAnnotation(Association.class) != null) {
                properties.add(new Property(name, Names.fkColumn(name), true));
            } else if (valueObject != null) {
                collections.add(buildCollection(table, name, valueObject));
            } else {
                properties.add(new Property(name, Names.columnName(name), false));
            }
        }
        if (id == null) {
            id = new Property("id", Names.columnName("id"), false);
        }
        return new EntityModel(typeMirror, typeName, table, id, properties, collections);
    }

    private ElementCollection buildCollection(String parentTable, String field, TypeElement valueObject) {
        String childTable = Names.childTable(parentTable, field);
        String parentFk = parentTable + "_id";
        List<Property> properties = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Element member : elements.getAllMembers(valueObject)) {
            if (member.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement property = (VariableElement) member;
            if (property.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            String name = property.getSimpleName().toString();
            if (!seen.add(name) || property.getAnnotation(Identity.class) != null || name.equals("id")) {
                continue;
            }
            if (property.getAnnotation(Association.class) != null) {
                properties.add(new Property(name, Names.fkColumn(name), true));
            } else {
                properties.add(new Property(name, Names.columnName(name), false));
            }
        }
        return new ElementCollection(field, TypeName.get(types.erasure(valueObject.asType())),
                childTable, parentFk, properties);
    }

    private TypeElement valueObjectElement(VariableElement field) {
        if (field.getAnnotation(Association.class) != null) {
            return null;
        }
        TypeMirror type = field.asType();
        if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().size() != 1
                || !"java.util.List".equals(types.erasure(declared).toString())) {
            return null;
        }
        TypeMirror argument = declared.getTypeArguments().get(0);
        if (!(argument instanceof DeclaredType element) || !(element.asElement() instanceof TypeElement typeElement)) {
            return null;
        }
        return typeElement.getAnnotation(ValueObject.class) != null ? typeElement : null;
    }

    // --------------------------------------------------------------- nested types

    private record Property(String name, String column, boolean association) { }

    private record ElementCollection(String field, TypeName valueObjectType, String childTable,
                                     String parentFk, List<Property> properties) { }

    private record EntityModel(TypeMirror typeMirror, TypeName typeName, String table, Property id,
                               List<Property> properties, List<ElementCollection> collections) { }

    private record Binding(String property, Operator operator, int paramIndex) { }

    private enum Kind { LIST, STREAM, OPTIONAL, SINGLE, VOID }
}
