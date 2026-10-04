package io.github.fludakit.data.processor;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.engine.DataEngine;
import io.github.fludakit.data.qbn.ParsedMethodName;
import io.github.fludakit.data.qbn.QueryByMethodNameParser;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.jdbc.JdbcClient;
import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
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
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates a {@code <Repository>Impl} class for every {@link Repository @Repository} interface.
 *
 * <p>The generated class is {@link ApplicationScoped}, implements the annotated interface, and
 * delegates every method to {@link DataEngine#invoke(QueryModel, Object[])} with a
 * {@link QueryModel} describing the operation to run. It carries exactly two injected
 * collaborators: a {@link JdbcClient} and the {@link DataEngine}.</p>
 */
@SupportedAnnotationTypes("jakarta.data.repository.Repository")
public class RepositoryProcessor extends AbstractProcessor {

    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";
    private static final String BASIC_REPOSITORY = "jakarta.data.repository.BasicRepository";
    private static final String DATA_REPOSITORY = "jakarta.data.repository.DataRepository";

    private static final String PAGE_REQUEST = "jakarta.data.page.PageRequest";
    private static final String ORDER = "jakarta.data.Order";
    private static final String SORT = "jakarta.data.Sort";
    private static final String LIMIT = "jakarta.data.Limit";

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
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@Repository interface " + repository.getQualifiedName()
                            + " must extend DataRepository/BasicRepository/CrudRepository with a concrete entity type",
                    repository);
            return;
        }
        TypeName entityType = TypeName.get(types.erasure(entityMirror));
        DeclaredType repositoryType = (DeclaredType) repository.asType();
        String packageName = elements.getPackageOf(repository).getQualifiedName().toString();
        String implementationName = repository.getSimpleName() + "Impl";

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implementationName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(ApplicationScoped.class)
                .addSuperinterface(TypeName.get(repository.asType()))
                .addField(FieldSpec.builder(JdbcClient.class, "jdbcClient", Modifier.PRIVATE)
                        .addAnnotation(Inject.class)
                        .build())
                .addField(FieldSpec.builder(DataEngine.class, "engine", Modifier.PRIVATE)
                        .addAnnotation(Inject.class)
                        .build());

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
            classBuilder.addMethod(generateMethod(method, repositoryType, entityType));
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
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to write " + implementationName + ": " + e.getMessage(), repository);
        }
    }

    private MethodSpec generateMethod(ExecutableElement method, DeclaredType repositoryType, TypeName entityType) {
        TypeMirror returnMirror = ((ExecutableType) types.asMemberOf(repositoryType, method)).getReturnType();
        TypeName returnType = TypeName.get(returnMirror);
        CodeBlock arguments = arguments(method);
        CodeBlock model = crudModel(method, entityType);
        if (model == null) {
            model = declaredModel(method, repositoryType, entityType);
        }

        MethodSpec.Builder builder = MethodSpec.overriding(method, repositoryType, types);
        // overriding() resolves the entity type, but a generic method's own type variable keeps its
        // declaration-site bound (T) rather than the substituted one (e.g. Book); rebind it.
        if (!builder.typeVariables.isEmpty()) {
            builder.typeVariables.clear();
            builder.typeVariables.add(TypeVariableName.get(
                    method.getTypeParameters().get(0).getSimpleName().toString(), entityType));
        }
        if (returnType.equals(TypeName.VOID)) {
            builder.addStatement("engine.invoke($L, $L)", model, arguments);
        } else {
            builder.addStatement("return ($T) engine.invoke($L, $L)", returnType.box(), model, arguments);
        }
        return builder.build();
    }

    // ------------------------------------------------------------- CRUD methods

    /**
     * Returns the query model for a method inherited from {@code BasicRepository}/{@code CrudRepository},
     * or {@code null} when the method is not one of the well-known CRUD operations.
     */
    private CodeBlock crudModel(ExecutableElement method, TypeName entityType) {
        String name = method.getSimpleName().toString();
        int arity = method.getParameters().size();

        if ("save".equals(name) && arity == 1) return plain(entityType, Operation.SAVE, ResultKind.SINGLE);
        if ("saveAll".equals(name) && arity == 1) return plain(entityType, Operation.SAVE, ResultKind.LIST);
        if ("findById".equals(name) && arity == 1) return byId(entityType, Operation.FIND, ResultKind.OPTIONAL);
        if ("findAll".equals(name) && arity == 0) return plain(entityType, Operation.FIND, ResultKind.STREAM);
        if ("findAll".equals(name) && arity == 2) {
            return model(entityType, Operation.FIND, ResultKind.PAGE)
                    .add(".pageParamIndex($L)", 0)
                    .add(".orderParamIndex($L)", 1)
                    .add(".build()").build();
        }
        if ("deleteById".equals(name) && arity == 1) return byId(entityType, Operation.DELETE, ResultKind.VOID);
        if ("delete".equals(name) && arity == 1) return plain(entityType, Operation.DELETE, ResultKind.VOID);
        if ("deleteAll".equals(name) && arity == 1) return plain(entityType, Operation.DELETE, ResultKind.VOID);
        if ("insert".equals(name) && arity == 1) return plain(entityType, Operation.INSERT, ResultKind.SINGLE);
        if ("insertAll".equals(name) && arity == 1) return plain(entityType, Operation.INSERT, ResultKind.LIST);
        if ("update".equals(name) && arity == 1) return plain(entityType, Operation.UPDATE, ResultKind.SINGLE);
        if ("updateAll".equals(name) && arity == 1) return plain(entityType, Operation.UPDATE, ResultKind.LIST);
        return null;
    }

    private CodeBlock plain(TypeName entityType, Operation operation, ResultKind kind) {
        return model(entityType, operation, kind).add(".build()").build();
    }

    private CodeBlock byId(TypeName entityType, Operation operation, ResultKind kind) {
        return model(entityType, operation, kind)
                .add(".predicate($T.of($S, $T.$L, $S))", Predicate.class, "id", Operator.class, "EQ", "arg0")
                .add(".build()").build();
    }

    // --------------------------------------------------------- declared methods

    private CodeBlock declaredModel(ExecutableElement method, DeclaredType repositoryType, TypeName entityType) {
        TypeMirror returnMirror = ((ExecutableType) types.asMemberOf(repositoryType, method)).getReturnType();
        ResultKind kind = byReturnType(returnMirror);
        List<? extends VariableElement> parameters = method.getParameters();

        Query query = method.getAnnotation(Query.class);
        if (query != null) {
            CodeBlock.Builder builder = model(entityType, Operation.FIND, kind)
                    .add(".sql($S)", query.value());
            for (int i = 0; i < parameters.size(); i++) {
                Param param = parameters.get(i).getAnnotation(Param.class);
                if (param != null) {
                    builder.add(".paramRef($L, $S)", i, param.value());
                }
            }
            appendParamIndices(builder, parameters);
            return builder.add(".build()").build();
        }

        if (method.getAnnotation(Insert.class) != null || method.getAnnotation(Update.class) != null
                || method.getAnnotation(Delete.class) != null || method.getAnnotation(Save.class) != null) {
            Operation operation;
            ResultKind lifecycleKind;
            if (method.getAnnotation(Insert.class) != null) {
                operation = Operation.INSERT;
                lifecycleKind = ResultKind.SINGLE;
            } else if (method.getAnnotation(Update.class) != null) {
                operation = Operation.UPDATE;
                lifecycleKind = ResultKind.SINGLE;
            } else if (method.getAnnotation(Save.class) != null) {
                operation = Operation.SAVE;
                lifecycleKind = ResultKind.SINGLE;
            } else {
                operation = Operation.DELETE;
                lifecycleKind = kind == ResultKind.VOID ? ResultKind.VOID : ResultKind.SINGLE;
            }
            return model(entityType, operation, lifecycleKind).add(".build()").build();
        }

        String name = method.getSimpleName().toString();
        boolean annotated = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        OrderBy[] orderBys = method.getAnnotationsByType(OrderBy.class);

        if (annotated || orderBys.length > 0) {
            CodeBlock.Builder builder = model(entityType, inferOperation(name), kind);
            for (int i = 0; i < parameters.size(); i++) {
                By by = parameters.get(i).getAnnotation(By.class);
                if (by != null) {
                    String property = By.ID.equals(by.value()) ? "id" : by.value();
                    builder.add(".predicate($T.of($S, $T.$L, $S))",
                            Predicate.class, property, Operator.class, "EQ", "arg" + i);
                }
            }
            for (OrderBy orderBy : orderBys) {
                builder.add(".sort($T.of($S, $L, $L))",
                        SortSpec.class, orderBy.value(), !orderBy.descending(), orderBy.ignoreCase());
            }
            appendParamIndices(builder, parameters);
            return builder.add(".build()").build();
        }

        ParsedMethodName parsed = parseByMethodName(name);
        if (parsed != null) {
            CodeBlock.Builder builder = model(entityType, parsedOperation(parsed.action()), kind);
            List<Integer> argumentIndices = nonSpecialIndices(parameters);
            List<Predicate> predicates = parsed.predicates();
            for (int i = 0; i < predicates.size(); i++) {
                int argumentIndex = i < argumentIndices.size() ? argumentIndices.get(i) : i;
                Predicate predicate = predicates.get(i);
                builder.add(".predicate($T.of($S, $T.$L, $S))", Predicate.class, predicate.property(),
                        Operator.class, predicate.operator().name(), "arg" + argumentIndex);
            }
            for (SortSpec sort : parsed.sorts()) {
                builder.add(".sort($T.of($S, $L, $L))",
                        SortSpec.class, sort.property(), sort.ascending(), sort.ignoreCase());
            }
            if (parsed.limit() > 0) {
                builder.add(".staticLimit($L)", parsed.limit());
            }
            appendParamIndices(builder, parameters);
            return builder.add(".build()").build();
        }

        CodeBlock.Builder builder = model(entityType, Operation.FIND, kind);
        appendParamIndices(builder, parameters);
        return builder.add(".build()").build();
    }

    // ----------------------------------------------------------------- helpers

    private CodeBlock.Builder model(TypeName entityType, Operation operation, ResultKind kind) {
        return CodeBlock.builder()
                .add("$T.builder($T.class, $T.$L)", QueryModel.class, entityType, Operation.class, operation.name())
                .add(".resultKind($T.$L)", ResultKind.class, kind.name());
    }

    private CodeBlock arguments(ExecutableElement method) {
        String names = method.getParameters().stream()
                .map(p -> p.getSimpleName().toString())
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? CodeBlock.of("new Object[]{}") : CodeBlock.of("new Object[]{$L}", names);
    }

    private void appendParamIndices(CodeBlock.Builder builder, List<? extends VariableElement> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            TypeMirror type = parameters.get(i).asType();
            String erased = types.erasure(type).toString();
            if (PAGE_REQUEST.equals(erased)) {
                builder.add(".pageParamIndex($L)", i);
            } else if (ORDER.equals(erased)) {
                builder.add(".orderParamIndex($L)", i);
            } else if (type.getKind() == TypeKind.ARRAY
                    && SORT.equals(types.erasure(((ArrayType) type).getComponentType()).toString())) {
                builder.add(".sortParamIndex($L)", i);
            } else if (LIMIT.equals(erased)) {
                builder.add(".limitParamIndex($L)", i);
            }
        }
    }

    private List<Integer> nonSpecialIndices(List<? extends VariableElement> parameters) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (!isSpecial(parameters.get(i).asType())) {
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

    private ParsedMethodName parseByMethodName(String name) {
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

    private Operation inferOperation(String name) {
        if (name.startsWith("count")) return Operation.COUNT;
        if (name.startsWith("exists")) return Operation.EXISTS;
        if (name.startsWith("delete")) return Operation.DELETE;
        return Operation.FIND;
    }

    private Operation parsedOperation(String action) {
        return switch (action) {
            case "count" -> Operation.COUNT;
            case "delete" -> Operation.DELETE;
            case "exists" -> Operation.EXISTS;
            default -> Operation.FIND;
        };
    }

    private ResultKind byReturnType(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.VOID) {
            return ResultKind.VOID;
        }
        if (returnType.getKind() == TypeKind.ARRAY) {
            return ResultKind.LIST;
        }
        return switch (types.erasure(returnType).toString()) {
            case "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable" -> ResultKind.LIST;
            case "java.util.Optional" -> ResultKind.OPTIONAL;
            case "java.util.stream.Stream" -> ResultKind.STREAM;
            case "jakarta.data.page.Page" -> ResultKind.PAGE;
            case "jakarta.data.page.CursoredPage" -> ResultKind.CURSORED_PAGE;
            case "long", "int", "java.lang.Long", "java.lang.Integer", "java.lang.Number" -> ResultKind.COUNT;
            case "boolean", "java.lang.Boolean" -> ResultKind.EXISTS;
            default -> ResultKind.SINGLE;
        };
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
}
