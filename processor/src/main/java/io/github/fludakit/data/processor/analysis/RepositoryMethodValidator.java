package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.RepositoryFlavour;
import io.github.fludakit.data.processor.support.Diagnostics;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates EVERY abstract repository method before an implementation is generated.
 *
 * <p>Three kinds of method reach a repository interface, and each is validated differently:
 * <ul>
 *   <li><b>Lifecycle-annotated</b> ({@code @Insert}/{@code @Update}/{@code @Save}/{@code @Delete}) —
 *       the signature must be a supported lifecycle shape, otherwise the method is rejected. Without
 *       this check the emitter falls through to the query path and silently generates a
 *       predicate-less SELECT over the whole table.</li>
 *   <li><b>Built-in</b> methods inherited from {@code DataRepository}/{@code BasicRepository}/
 *       {@code CrudRepository} — the parameter shape must be one the emitter implements, for the
 *       same reason.</li>
 *   <li><b>Query-by-method-name</b> — the name must resolve through the query-by-method-name parser,
 *       which is an OPTIONAL compile dependency. When it is absent the diagnostic says so; when it is
 *       present but rejects the name, the diagnostic asks for a corrected name or an explicit
 *       {@code @Query}.</li>
 * </ul>
 */
public final class RepositoryMethodValidator {

    /** The query-by-method-name parser: an OPTIONAL compile dependency, probed before use. */
    private static final String QBN_PARSER = "io.github.fludakit.data.qbn.QueryByMethodNameParser";

    /** Method names {@code CrudRepository}/{@code BasicRepository} contribute. */
    private static final Set<String> BUILT_IN_METHODS = Set.of(
            "save", "saveAll", "insert", "insertAll", "update", "updateAll",
            "delete", "deleteAll", "deleteById", "findById", "findAll", "existsById", "count");

    /** Lifecycle shapes: a single entity, or a {@code List}/{@code array} of them. */
    private static final Set<String> ENTITY_LIFECYCLE_METHODS = Set.of("save", "insert", "update", "delete");
    private static final Set<String> SEQUENCE_LIFECYCLE_METHODS = Set.of("saveAll", "insertAll", "updateAll", "deleteAll");
    private static final Set<String> IDENTIFIER_METHODS = Set.of("findById", "deleteById", "existsById");

    /**
     * The action prefixes a query-by-method name starts with. See
     * {@link #isQueryMethodName(String)} for why they are spelled out here instead of being read
     * from {@code qbn}.
     */
    private static final List<String> QUERY_METHOD_PREFIXES = List.of("find", "count", "delete", "exists");

    /** A query method name carrying no criteria: {@code findBy}, {@code count}, {@code deleteBy}, ... */
    private static final Pattern BARE_QUERY_NAME =
            Pattern.compile("(?:find|count|delete|exists)By?");

    private final Diagnostics diagnostics;
    private final Types types;
    private final Elements elements;
    private boolean queryByNameChecked;
    private boolean queryByNameAvailable;

    public RepositoryMethodValidator(Diagnostics diagnostics, Types types, Elements elements) {
        this.diagnostics = diagnostics;
        this.types = types;
        this.elements = elements;
    }

    /**
     * @return {@code true} when the method may be implemented; {@code false} when a diagnostic was
     *         reported and no implementation must be emitted for it
     */
    public boolean validate(ExecutableElement method, DeclaredType repositoryType, TypeMapping<?> entity,
                     RepositoryFlavour flavour) {
        if (operationAnnotationCount(method) > 1) {
            error(method, "Repository method '" + method.getSimpleName()
                    + "' must not combine query and lifecycle annotations");
            return false;
        }

        String name = method.getSimpleName().toString();
        ExecutableType resolvedType = (ExecutableType) types.asMemberOf(repositoryType, method);
        List<? extends TypeMirror> parameterTypes = resolvedType.getParameterTypes();

        // Slice has no generated shape yet. Left alone it fell through to the single-result path and
        // silently produced a query whose terminal does not match the declaration.
        if ("jakarta.data.page.Slice".equals(types.erasure(resolvedType.getReturnType()).toString())) {
            error(method, "Repository method '" + name + "' returns Slice, which this implementation does not"
                    + " generate yet; return Page or CursoredPage instead");
            return false;
        }

        // Two categories, and the split is exactly whether the method NAME carries the query.
        //
        // ANNOTATION-DRIVEN — the annotation declares the operation, so the name is NEVER analyzed:
        //   (a) write lifecycle annotations decide on the method's signature alone;
        //   (b) the spec's built-in repository methods, matched by name because those interfaces carry
        //       the annotations in the API itself. This must precede (c): CrudRepository.findAll is
        //       itself annotated with @Find;
        //   (c) @Find/@Query on the application's own methods — no name check applies, not even for a
        //       query-by-method-name-shaped spelling.
        //
        // NAME-DRIVEN — query-by-method-name, below: the name IS the query and no annotation is involved.
        if (hasLifecycleAnnotation(method)) {
            return validateLifecycle(method, name, parameterTypes, entity);
        }

        if (flavour.spec() && BUILT_IN_METHODS.contains(name)) {
            return validateBuiltIn(method, name, parameterTypes, entity);
        }

        if (method.getAnnotation(Find.class) != null || method.getAnnotation(Query.class) != null) {
            return true;
        }

        if (flavour == RepositoryFlavour.PLAIN && !hasRepositoryOperationAnnotation(method)) {
            error(method, "Abstract repository method '" + name
                    + "' must declare a Jakarta Data query/lifecycle annotation unless the repository"
                    + " extends DataRepository, BasicRepository, or CrudRepository");
            return false;
        }

        return validateQueryByName(method, name);
    }

    // ------------------------------------------------------------------- lifecycle methods

    /**
     * Tier 1: a lifecycle annotation pins the method to a write on its signature alone, with no
     * reference to the name — exactly one parameter carrying a single entity or a sequence of them.
     * {@code @Delete} is the exception: it doubles as a parameter-derived delete query annotation
     * ({@code @Delete void deleteByStatus(String)}), so a non-entity parameter is accepted as well.
     */
    private boolean validateLifecycle(ExecutableElement method, String name,
                                      List<? extends TypeMirror> parameterTypes,
                                      TypeMapping<?> entity) {
        boolean delete = method.getAnnotation(Delete.class) != null;
        if (parameterTypes.size() == 1) {
            TypeMirror parameter = parameterTypes.get(0);
            if (TypeMirrors.isEntity(types, elements, parameter, entity) || isEntitySequence(parameter, entity)) {
                return true;
            }
        }
        if (delete) {
            // @Delete doubles as a query annotation: a non-entity parameter makes this a
            // parameter-derived delete predicate (the parameter names are the criteria), which the
            // application names freely — so no query-by-method-name check applies.
            return true;
        }
        String entityName = entity.typeName().toString();
        error(method, "Repository method '" + name + "' carries a lifecycle annotation and must accept a single "
                + entityName + ", List<" + entityName + ">, or " + entityName + "[]");
        return false;
    }

    // ------------------------------------------------------------------- built-in methods

    /**
     * A built-in name whose parameter shape the emitter does not implement would otherwise fall
     * through to the query path and generate a predicate-less SELECT.
     */
    private boolean validateBuiltIn(ExecutableElement method, String name,
                                    List<? extends TypeMirror> parameterTypes,
                                    TypeMapping<?> entity) {
        int arity = parameterTypes.size();
        boolean ok;
        if (ENTITY_LIFECYCLE_METHODS.contains(name)) {
            ok = arity == 1 && TypeMirrors.isEntity(types, elements, parameterTypes.get(0), entity);
        } else if (SEQUENCE_LIFECYCLE_METHODS.contains(name)) {
            ok = arity == 1 && isEntitySequence(parameterTypes.get(0), entity);
        } else if (IDENTIFIER_METHODS.contains(name)) {
            ok = arity == 1;
        } else if ("findAll".equals(name)) {
            ok = arity == 0 || arity == 2;
        } else {
            ok = arity == 0;
        }
        if (!ok) {
            error(method, "Built-in repository method '" + name + "' is declared with an unsupported signature "
                    + signature(name, parameterTypes) + "; this implementation cannot generate it");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------- query by method name

    /**
     * A method that is neither annotated nor built-in must be a query-by-method name. Missing parser
     * and rejected name are reported separately because they have different fixes.
     */
    private boolean validateQueryByName(ExecutableElement method, String name) {
        if (!isQueryMethodName(name)) {
            error(method, "Repository method '" + name + "' is not a query-by-method name and carries no Jakarta"
                    + " Data query annotation; annotate it with @Query to supply the statement");
            return false;
        }
        // A bare action (with or without 'By') declares no criteria — a paged read over the whole
        // table — so there is nothing for the parser to resolve.
        if (BARE_QUERY_NAME.matcher(name).matches()) {
            return true;
        }
        if (!queryByNameAvailable()) {
            error(method, "Repository method '" + name + "' needs query-by-method-name support: add"
                    + " fluda-data-qbn to the annotation processor path (-processorpath /"
                    + " annotationProcessorPaths), or annotate the method with @Query");
            return false;
        }
        String rejected = rejectionOf(name);
        if (rejected != null) {
            // The REASON the parser gave, not a restatement of the shape it failed. The parser knows
            // why the name is wrong — Not on an operator the spec does not negate, Distinct on an
            // action that neither selects nor counts, a name the grammar cannot read — and throwing
            // that away to say "not a valid query-by-method name" made the user re-derive it.
            error(method, rejected + " — correct the method name or annotate it with @Query to supply"
                    + " the statement");
            return false;
        }
        return true;
    }

    /**
     * Whether {@code name} starts with one of the four query-by-method-name action prefixes.
     *
     * <p>These four strings repeat {@code io.github.fludakit.data.qbn.Action} on purpose. The parser
     * is an OPTIONAL compile dependency, so this test must still answer when {@code fluda-data-qbn}
     * is absent from the annotation processor path — that absence is exactly what the caller
     * diagnoses next. Reaching for the qbn enum here would answer the question with a
     * {@code NoClassDefFoundError} instead of a {@code boolean}.</p>
     */
    public static boolean isQueryMethodName(String name) {
        for (String prefix : QUERY_METHOD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the optional {@code fluda-data-qbn} parser is on this processor's classpath. */
    private boolean queryByNameAvailable() {
        if (!queryByNameChecked) {
            queryByNameChecked = true;
            queryByNameAvailable = probe();
        }
        return queryByNameAvailable;
    }

    private static boolean probe() {
        try {
            Class.forName(QBN_PARSER);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Why {@code name} is not a query-by-method name, or {@code null} when it is one.
     *
     * <p>The reason comes back rather than a {@code boolean} because the parser computes it and the
     * old {@code boolean} threw it away: {@code findByPriceNotLessThan} was reported as "not a valid
     * query-by-method name" when the parser had already determined that {@code Not} cannot be
     * combined with {@code LESS_THAN}. A {@code boolean} cannot carry that, so the diagnostic had to
     * be written at the point that knew least about it.</p>
     *
     * <p>Fully qualified on purpose: {@code fluda-data-qbn} is an OPTIONAL dependency, so this class
     * is compiled and loaded without it on the classpath. The import that would name the type is the
     * thing that cannot be present.</p>
     */
    private static String rejectionOf(String name) {
        try {
            new io.github.fludakit.data.qbn.QueryByMethodNameParser().parse(name);
            return null;
        } catch (RuntimeException | LinkageError rejected) {
            return rejected.getMessage();
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Whether {@code parameterType} is a sequence OF the entity: {@code List<Book>} or
     * {@code Book[]} against a {@code Book} repository. The element test is
     * {@link TypeMirrors#sequenceElementType} — a {@code List} of something else, or a raw one, is not
     * an entity sequence however much it looks like one at the call site.
     */
    private boolean isEntitySequence(TypeMirror parameterType, TypeMapping<?> entity) {
        TypeMirror element = TypeMirrors.sequenceElementType(parameterType);
        return element != null && TypeMirrors.isEntity(types, elements, element, entity);
    }

    private static String signature(String name, List<? extends TypeMirror> parameterTypes) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameterTypes.get(i));
        }
        return sb.append(')').toString();
    }

    private void error(ExecutableElement method, String message) {
        diagnostics.error( message, method);
    }

    private static boolean hasLifecycleAnnotation(ExecutableElement method) {
        return method.getAnnotation(Insert.class) != null
                || method.getAnnotation(Update.class) != null
                || method.getAnnotation(Save.class) != null
                || method.getAnnotation(Delete.class) != null;
    }

    private static boolean hasRepositoryOperationAnnotation(ExecutableElement method) {
        return method.getAnnotation(Find.class) != null
                || method.getAnnotation(Query.class) != null
                || hasLifecycleAnnotation(method);
    }

    private static int operationAnnotationCount(ExecutableElement method) {
        int count = 0;
        count += method.getAnnotation(Find.class) == null ? 0 : 1;
        count += method.getAnnotation(Query.class) == null ? 0 : 1;
        count += method.getAnnotation(Insert.class) == null ? 0 : 1;
        count += method.getAnnotation(Update.class) == null ? 0 : 1;
        count += method.getAnnotation(Save.class) == null ? 0 : 1;
        count += method.getAnnotation(Delete.class) == null ? 0 : 1;
        return count;
    }
}
