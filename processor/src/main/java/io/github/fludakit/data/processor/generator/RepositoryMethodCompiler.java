package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanCompiler;
import io.github.fludakit.data.processor.analysis.TypeShapes;
import io.github.fludakit.data.processor.generator.metadata.MetadataGenerator;
import io.github.fludakit.data.processor.generator.EmissionContext;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.PagedRead;
import io.github.fludakit.data.processor.analysis.PathOrigin;
import io.github.fludakit.data.processor.analysis.PathResolution;
import io.github.fludakit.data.processor.analysis.QueryPlan;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.support.DataExceptions;
import io.github.fludakit.jdbc.JdbcClientException;
import jakarta.data.Sort;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles one abstract repository method into its {@code MethodSpec}: the SQL constant it renders
 * (registered through {@link SqlConstants}) and the {@code jdbcClient} call that executes it with the
 * method's parameters bound.
 *
 * <p>Four shapes, dispatched on what the method DECLARES rather than on its name: a {@code @Query}
 * whose SQL the application wrote, the CRUD built-ins the interface inherits ({@code findById},
 * {@code findAll}, the paged {@code findAll(PageRequest, Sort)}), the write family — owned by
 * {@link WriteMethodEmitter} — and query-by-names / {@code @Find}, which is the general case and
 * produces the {@code QueryDefinition} the {@code <Repository>Queries} holder publishes.</p>
 *
 * <p>Two reads of the same entity can differ in MORE than their predicate: a read whose compile-time
 * paths traverse a composing to-one projects the target's columns onto the parent's own row and needs
 * a per-method row mapper ({@link JoinedProjectionEmitter#buildFullMapper}), and a paged read defers
 * its ORDER BY to runtime because the caller passes the sort. Those variants are the reason this
 * cluster exists as one class — they share the binding, terminal and hydration wiring, which now
 * lives on {@link QueryInvocations}.</p>
 *
 * <p>Nothing here writes a source file: the {@code MethodSpec.Builder} handed in belongs to the
 * caller's implementation class, and auxiliary methods (the per-method row mappers) are appended to
 * the caller's {@code auxMethods} list.</p>
 */
public final class RepositoryMethodCompiler {

    /**
     * The container types a query-by-names result can be declared as. A {@code List} satisfies
     * {@code Collection} and {@code Iterable}, so those two need no {@link ResultKind} of their own —
     * the materialized list is returned as-is.
     */
    private static final Set<String> CONTAINER_TYPES = Set.of(
            "java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable",
            "java.util.Optional", "java.util.stream.Stream");

    private final TypeShapes typeShapes;
    private final MappedTypeBuilder typeMappingBuilder;
    private final Diagnostics diagnostics;
    private final SqlDialect sqlDialect;
    private final PathResolver pathResolver;
    private final QueryPlanCompiler queryPlanCompiler;
    private final MetadataGenerator metadataGenerator;
    private final WriteMethodEmitter writeMethodEmitter;
    /**
     * The session cache every structured read publishes its {@link QueryDefinition.Criteria} into,
     * keyed by the repository's qualified name (carried on {@link RepositoryEmission#repositoryFqn()}).
     * The generated {@code <Repository>Queries} holder is written from this map, so a definition is
     * stored once here rather than threaded through a per-repository list.
     */
    private final GeneratedArtifactRegistry registry;
    /**
     * The composing to-one JOIN projection: the extra SELECT list and the per-method row mapper that
     * reads it. Injected rather than built here because {@link RepositoryProcessor} asks it for the
     * CRUD full mapper too — the same projection serves the inherited reads and query-by-names.
     */
    private final JoinedProjectionEmitter joinedProjections;
    /** The inherited CRUD reads ({@code findById}, {@code findAll}, the paged {@code findAll}). */
    private final CrudReadEmitter crudReads;
    /**
     * The paged / keyset-paged read emitter. Built here rather than injected because it is a
     * decomposition of THIS class, not a peer collaborator: it owns the two seek constants and the
     * cursor-key wiring, and needs the {@link MetadataGenerator} and {@link PathResolver} this class
     * already holds.
     */
    private final PagingEmitter pagingEmitter;

    public RepositoryMethodCompiler(TypeShapes typeShapes, MappedTypeBuilder typeMappingBuilder,
                             Diagnostics diagnostics, SqlDialect sqlDialect, PathResolver pathResolver,
                             QueryPlanCompiler queryPlanCompiler, MetadataGenerator metadataGenerator,
                             WriteMethodEmitter writeMethodEmitter,
                             JoinedProjectionEmitter joinedProjections,
                             GeneratedArtifactRegistry registry) {
        this.typeShapes = typeShapes;
        this.typeMappingBuilder = typeMappingBuilder;
        this.diagnostics = diagnostics;
        this.sqlDialect = sqlDialect;
        this.pathResolver = pathResolver;
        this.queryPlanCompiler = queryPlanCompiler;
        this.metadataGenerator = metadataGenerator;
        this.writeMethodEmitter = writeMethodEmitter;
        this.joinedProjections = joinedProjections;
        this.registry = registry;
        this.crudReads = new CrudReadEmitter(sqlDialect, typeMappingBuilder, metadataGenerator, joinedProjections);
        this.pagingEmitter = new PagingEmitter(sqlDialect, typeMappingBuilder, pathResolver, metadataGenerator,
                diagnostics);
    }

    // ------------------------------------------------------------- method dispatch

    /**
     * Compiles one abstract repository method into its {@code MethodSpec}. The dispatch reads as a
     * table of contents; each group is a named method below.
     *
     * <p>Ten shapes, in priority order:</p>
     * <ol>
     *   <li>{@code @Query} — native SQL, bind parameters</li>
     *   <li>CRUD paged {@code findAll(PageRequest, Sort)} — runtime sort/join generation</li>
     *   <li>CRUD {@code findById}/{@code findAll} with JOIN projection — joined constant + joined mapper</li>
     *   <li>{@code @Find} — explicit finder, property-based criteria</li>
     *   <li>Lifecycle batch — {@code List<E>} or {@code E[]} with {@code @Insert/@Update/@Save/@Delete}</li>
     *   <li>Single-entity CRUD by name — {@code save}, {@code insert}, {@code update}, {@code delete}</li>
     *   <li>Batch CRUD by name — {@code saveAll}, {@code insertAll}, {@code updateAll}, {@code deleteAll}</li>
     *   <li>Remaining CRUD built-ins — {@code findById}, {@code deleteById}, {@code findAll}</li>
     *   <li>Single-entity annotated writes — {@code @Insert}, {@code @Update}, {@code @Save}, {@code @Delete}</li>
     *   <li>Query-by-name fallthrough — parse method name, resolve paths, render SQL</li>
     * </ol>
     */
    public MethodSpec generateMethod(ExecutableElement method, DeclaredType repositoryType,
                              RepositoryEmission emission) {
        ExecutableType resolved = typeShapes.resolved(repositoryType, method);
        MethodSpec.Builder builder = typeShapes.overriding(method, repositoryType);
        rebindTypeVariables(builder, method, typeMappingBuilder.classNameFor(emission.entity().typeName()));
        // One guard per method rather than per call site: every jdbcClient invocation lives inside the
        // body, so a single catch translates each failure the client raises without relabeling an
        // unrelated bug (a NPE in a row mapper or a hydrate step) as a data exception.
        builder.beginControlFlow("try");
        compileBody(builder, method, resolved, repositoryType, emission);
        builder.nextControlFlow("catch ($T e)", JdbcClientException.class);
        builder.addStatement("throw $T.translate(e)", DataExceptions.class);
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Fills the method body for one shape. The dispatch reads as a table of contents; each group is a
     * named method below. Every branch adds statements to the caller's {@code builder} and returns —
     * the caller owns the enclosing {@code try} and the {@link MethodSpec} build.
     *
     * <p>Ten shapes, in priority order:</p>
     * <ol>
     *   <li>{@code @Query} — native SQL, bind parameters</li>
     *   <li>CRUD paged {@code findAll(PageRequest, Sort)} — runtime sort/join generation</li>
     *   <li>CRUD {@code findById}/{@code findAll} with JOIN projection — joined constant + joined mapper</li>
     *   <li>{@code @Find} — explicit finder, property-based criteria</li>
     *   <li>Lifecycle batch — {@code List<E>} or {@code E[]} with {@code @Insert/@Update/@Save/@Delete}</li>
     *   <li>Single-entity CRUD by name — {@code save}, {@code insert}, {@code update}, {@code delete}</li>
     *   <li>Batch CRUD by name — {@code saveAll}, {@code insertAll}, {@code updateAll}, {@code deleteAll}</li>
     *   <li>Remaining CRUD built-ins — {@code findById}, {@code deleteById}, {@code findAll}</li>
     *   <li>Single-entity annotated writes — {@code @Insert}, {@code @Update}, {@code @Save}, {@code @Delete}</li>
     *   <li>Query-by-name fallthrough — parse method name, resolve paths, render SQL</li>
     * </ol>
     */
    private void compileBody(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                             DeclaredType repositoryType, RepositoryEmission emission) {
        TypeMirror returnMirror = resolved.getReturnType();
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        List<? extends VariableElement> parameters = method.getParameters();
        String name = method.getSimpleName().toString();
        int arity = parameters.size();

        // 1. @Query — native SQL, bind parameters
        Query query = method.getAnnotation(Query.class);
        if (query != null) {
            emitQuery(builder, method, query, returnMirror, emission.entity(), emission.constants());
            return;
        }

        // 2. CRUD paged findAll — runtime sort/join generation
        if (name.equals("findAll") && arity == 2) {
            crudReads.emitFindAllPage(builder, parameters, emission.readContext());
            return;
        }

        // 3. CRUD findById/findAll with JOIN projection — joined constant + joined mapper
        if (tryEmitJoinProjectedCrudRead(builder, name, arity, parameters, returnMirror, emission)) {
            return;
        }

        // 4. @Find — explicit finder, property-based criteria
        if (method.getAnnotation(Find.class) != null) {
            emitFind(builder, method, resolved, emission, true, false);
            return;
        }

        // 5. Lifecycle batch — List<E> or E[] with @Insert/@Update/@Save/@Delete
        if (tryEmitLifecycleBatch(builder, method, name, arity, parameters, parameterTypes, emission)) {
            return;
        }

        // 6. Single-entity CRUD by name — save, insert, update, delete
        if (tryEmitSingleEntityCrudByName(builder, method, name, arity, parameters, parameterTypes, returnMirror, emission)) {
            return;
        }

        // 7. Batch CRUD by name — saveAll, insertAll, updateAll, deleteAll
        if (tryEmitBatchCrudByName(builder, method, name, arity, parameters, emission)) {
            return;
        }

        // 8. Remaining CRUD built-ins — findById, deleteById, findAll
        if (tryEmitCrudBuiltins(builder, name, arity, parameters, returnMirror, emission)) {
            return;
        }

        // 9. Single-entity annotated writes — @Insert, @Update, @Save, @Delete
        if (tryEmitAnnotatedWrite(builder, method, name, arity, parameters, parameterTypes, returnMirror, emission)) {
            return;
        }

        // 10. Query-by-name fallthrough — parse method name, resolve paths, render SQL
        emitFind(builder, method, resolved, emission, false, method.getAnnotation(Delete.class) != null);
    }

    /**
     * Group 3: CRUD {@code findById}/{@code findAll} with JOIN projection. When composing to-one
     * targets are JOIN-projected onto the parent's own row, the CRUD built-ins must route to the
     * CRUD read emitters (which pick the joined constant and the joined row mapper) rather than
     * falling through to the generic query-by-names emitter. Gated on the projection so a descriptor
     * with no composing to-one keeps the byte-identical legacy emission.
     */
    private boolean tryEmitJoinProjectedCrudRead(MethodSpec.Builder builder, String name, int arity,
                                          List<? extends VariableElement> parameters, TypeMirror returnMirror,
                                          RepositoryEmission emission) {
        if (emission.crudFullMapper() == null) {
            return false;
        }
        if (arity == 1 && name.equals("findById")) {
            crudReads.emitFindById(builder, MemberNames.parameterName(parameters.get(0), MemberNames.ID_PARAM),
                    emission.readContext(), resultKindOf(returnMirror));
            return true;
        }
        if (arity == 0 && name.equals("findAll")) {
            crudReads.emitFindAll(builder, emission.readContext(), resultKindOf(returnMirror));
            return true;
        }
        return false;
    }

    /**
     * Group 5: lifecycle batch — {@code List<E>} or {@code E[]} parameter with {@code @Insert},
     * {@code @Update}, {@code @Save}, or {@code @Delete}.
     */
    private boolean tryEmitLifecycleBatch(MethodSpec.Builder builder, ExecutableElement method, String name,
                                   int arity, List<? extends VariableElement> parameters,
                                   List<? extends TypeMirror> parameterTypes, RepositoryEmission emission) {
        if (arity != 1 || !writeMethodEmitter.isEntitySequenceParameter(parameterTypes.get(0), emission.entity())) {
            return false;
        }
        if (method.getAnnotation(Insert.class) == null && method.getAnnotation(Update.class) == null
                && method.getAnnotation(Save.class) == null && method.getAnnotation(Delete.class) == null) {
            return false;
        }
        writeMethodEmitter.emitLifecycleBatch(builder, method, parameters.get(0), parameterTypes.get(0),
                emission.entity(), emission.constants(), emission.cascade());
        return true;
    }

    /**
     * Group 6: single-entity CRUD by name — {@code save}, {@code insert}, {@code update}, {@code delete}.
     * These are inherited from {@code CrudRepository} and matched by method name, not by annotation.
     */
    private boolean tryEmitSingleEntityCrudByName(MethodSpec.Builder builder, ExecutableElement method,
                                           String name, int arity, List<? extends VariableElement> parameters,
                                           List<? extends TypeMirror> parameterTypes, TypeMirror returnMirror,
                                           RepositoryEmission emission) {
        if (arity != 1 || !writeMethodEmitter.isEntityParameter(parameterTypes.get(0), emission.entity())) {
            return false;
        }
        String value = MemberNames.parameterName(parameters.get(0), "entity");
        if (name.equals("delete")) {
            builder.addStatement("deleteById($L.$L())", value, MemberNames.getter(emission.entity().id().name()));
            return true;
        }
        if (name.equals("save")) {
            writeMethodEmitter.emitSave(builder, method, value, emission.entity(), emission.constants(), emission.cascade());
            return true;
        }
        if (name.equals("insert") && emission.flavour().crud()) {
            writeMethodEmitter.appendInsertAndChildCascades(builder, value, emission.entity(), emission.constants(), emission.cascade());
            if (returnMirror.getKind() != TypeKind.VOID) {
                builder.addStatement("return $L", value);
            }
            return true;
        }
        if (name.equals("update") && emission.flavour().crud()) {
            writeMethodEmitter.appendUpdateStatements(builder, value, emission.entity(), emission.constants(), emission.cascade());
            if (returnMirror.getKind() != TypeKind.VOID) {
                builder.addStatement("return $L", value);
            }
            return true;
        }
        return false;
    }

    /**
     * Group 7: batch CRUD by name — {@code saveAll}, {@code insertAll}, {@code updateAll}, {@code deleteAll}.
     * Inherited from {@code CrudRepository} and matched by method name.
     */
    private boolean tryEmitBatchCrudByName(MethodSpec.Builder builder, ExecutableElement method, String name,
                                    int arity, List<? extends VariableElement> parameters,
                                    RepositoryEmission emission) {
        if (arity != 1) {
            return false;
        }
        if (name.equals("saveAll")) {
            writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "save");
            return true;
        }
        if (name.equals("insertAll") && emission.flavour().crud()) {
            writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "insert");
            return true;
        }
        if (name.equals("updateAll") && emission.flavour().crud()) {
            writeMethodEmitter.emitBatch(builder, method, parameters.get(0), emission.entity(), "update");
            return true;
        }
        if (name.equals("deleteAll")) {
            builder.beginControlFlow("for ($T entity : $L)", typeMappingBuilder.classNameFor(emission.entity().typeName()), MemberNames.parameterName(parameters.get(0), "entities"));
            builder.addStatement("delete(entity)");
            builder.endControlFlow();
            return true;
        }
        return false;
    }

    /**
     * Group 8: remaining CRUD built-ins — {@code findById}, {@code deleteById}, {@code findAll}.
     * These are the non-join-projected variants (group 3 handles the join-projected case).
     */
    private boolean tryEmitCrudBuiltins(MethodSpec.Builder builder, String name, int arity,
                                 List<? extends VariableElement> parameters, TypeMirror returnMirror,
                                 RepositoryEmission emission) {
        if (arity == 1 && name.equals("findById")) {
            crudReads.emitFindById(builder, MemberNames.parameterName(parameters.get(0), MemberNames.ID_PARAM),
                    emission.readContext(), resultKindOf(returnMirror));
            return true;
        }
        if (arity == 1 && name.equals("deleteById")) {
            writeMethodEmitter.emitDeleteById(builder, MemberNames.parameterName(parameters.get(0), MemberNames.ID_PARAM),
                    emission.entity(), emission.constants(), emission.cascade());
            return true;
        }
        if (name.equals("findAll") && arity == 0) {
            crudReads.emitFindAll(builder, emission.readContext(), resultKindOf(returnMirror));
            return true;
        }
        return false;
    }

    /**
     * Group 9: single-entity annotated writes — {@code @Insert}, {@code @Update}, {@code @Save},
     * {@code @Delete}. Matched by annotation, not by method name.
     */
    private boolean tryEmitAnnotatedWrite(MethodSpec.Builder builder, ExecutableElement method, String name,
                                   int arity, List<? extends VariableElement> parameters,
                                   List<? extends TypeMirror> parameterTypes, TypeMirror returnMirror,
                                   RepositoryEmission emission) {
        if (arity != 1 || !writeMethodEmitter.isEntityParameter(parameterTypes.get(0), emission.entity())) {
            return false;
        }
        String value = MemberNames.parameterName(parameters.get(0), "entity");
        if (method.getAnnotation(Insert.class) != null) {
            writeMethodEmitter.appendInsertAndChildCascades(builder, value, emission.entity(), emission.constants(), emission.cascade());
            if (returnMirror.getKind() != TypeKind.VOID) {
                builder.addStatement("return $L", value);
            }
            return true;
        }
        if (method.getAnnotation(Update.class) != null) {
            writeMethodEmitter.appendUpdateStatements(builder, value, emission.entity(), emission.constants(), emission.cascade());
            if (returnMirror.getKind() != TypeKind.VOID) {
                builder.addStatement("return $L", value);
            }
            return true;
        }
        if (method.getAnnotation(Save.class) != null) {
            writeMethodEmitter.emitSave(builder, method, value, emission.entity(), emission.constants(), emission.cascade());
            return true;
        }
        if (method.getAnnotation(Delete.class) != null) {
            builder.addStatement("deleteById($L.$L())", value, MemberNames.getter(emission.entity().id().name()));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ @Query

    private void emitQuery(MethodSpec.Builder builder, ExecutableElement method, Query query, TypeMirror returnMirror,
                           TypeMapping<?> entity, Map<String, String> constants) {
        String name = method.getSimpleName().toString();
        String constant = SqlConstants.register(constants, "SQL_" + MemberNames.upperSnake(name), query.value());
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        List<? extends VariableElement> parameters = method.getParameters();
        boolean positional = SqlConstants.hasPositionalParameter(query.value());
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            if (queryPlanCompiler.isSpecial(parameter.asType())) {
                continue;
            }
            if (positional) {
                sql.add(".param($L)", MemberNames.parameterName(parameter, "arg" + i));
            } else {
                Param param = parameter.getAnnotation(Param.class);
                String bindingName = param != null ? param.value() : MemberNames.parameterName(parameter, "arg" + i);
                sql.add(".param($S, $L)", bindingName, MemberNames.parameterName(parameter, "arg" + i));
            }
        }
        ResultKind kind = resultKindOf(returnMirror);
        TypeName queryType = queryResultType(returnMirror, typeMappingBuilder.classNameFor(entity.typeName()));
        if (returnMirror.getKind() == TypeKind.ARRAY) {
            sql.add(".query($T.class).list().toArray($T[]::new)", queryType, queryType);
            builder.addStatement("return $L", sql.build());
        } else if (kind == ResultKind.VOID) {
            QueryInvocations.terminal(sql, kind, CodeBlock.of("$T.class", queryType));
            builder.addStatement("$L", sql.build());
        } else {
            QueryInvocations.terminal(sql, kind, CodeBlock.of("$T.class", queryType));
            builder.addStatement("return $L", sql.build());
        }
    }

    // ------------------------------------------------------------- find / by-method-name

    /**
     * The NAME contributes the operation, the criteria, the sort and the row cap; the SIGNATURE
     * contributes everything the name cannot know — the return type (result kind), the paging
     * parameters and the argument bindings. This record is where the two halves meet, so every
     * branch below assembles one {@link QueryDefinition} from a single source instead of re-deriving
     * the action from the name.
     */
    private record FindInput(String name, List<? extends VariableElement> parameters,
                             Object parsed, Operation operation, boolean distinct,
                             List<? extends TypeMirror> parameterTypes, List<ParamBinding> paramBindings,
                             TypeMirror returnMirror, ResultKind kind, String queryDefinitionName,
                             List<Predicate> predicates, List<ColumnPlacement> predicateRefs,
                             List<String> predicateAttributePaths) {
    }

    /**
     * Resolves everything the find/count/delete emitters need from one repository method: the parsed
     * action, the argument bindings, the predicates and their compile-time column refs, and the
     * collision-suffixed query-definition name.
     *
     * @return the resolved input, or {@code null} when a declared predicate property (method name or
     *         {@code @By}) does not resolve — the ERROR is already reported, so the method must be skipped
     */
    private FindInput resolveFindInput(ExecutableElement method, ExecutableType resolved,
                                       TypeMapping<?> entity, Map<String, Integer> queryDefinitionNames,
                                       boolean explicitFind, boolean explicitDelete) {
        String name = method.getSimpleName().toString();
        List<? extends VariableElement> parameters = method.getParameters();
        // Query-by-method-name IS the method name, so only an unannotated method is parsed. @Find and
        // @Query are annotation-driven — the opposite of QBN — so their name is never analyzed: the
        // criteria come from @By, or from the parameter names when @By is absent.
        Object parsed = (explicitFind || explicitDelete) ? null : queryPlanCompiler.tryParse(name);
        // The operation is the name's own half — find/count/delete/exists — defaulted to a find when
        // the name carries no action (@Find, @Delete, or an unparseable name). Distinct likewise comes
        // from the name; only a find or a count can ask for it.
        Operation operation = explicitDelete ? Operation.DELETE
                : explicitFind ? Operation.FIND
                : parsed != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).operation() : Operation.FIND;
        boolean distinct = parsed != null && ((io.github.fludakit.data.qbn.ParsedMethodName) parsed).distinct()
                && (operation == Operation.FIND || operation == Operation.COUNT);
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        QueryPlanCompiler.ResolvedBindings resolvedBindings = queryPlanCompiler.resolvedBindings(parameters, parameterTypes, explicitFind || explicitDelete, parsed);
        List<Predicate> predicates = resolvedBindings.predicates();
        List<ParamBinding> paramBindings = resolvedBindings.paramBindings();
        TypeMirror returnMirror = resolved.getReturnType();
        ResultKind kind = resultKindOf(returnMirror);
        String queryDefinitionName = MemberNames.capitalize(name) + "Query";
        int nameClash = queryDefinitionNames.merge(queryDefinitionName, 1, Integer::sum);
        if (nameClash > 1) {
            queryDefinitionName = queryDefinitionName + nameClash; // overload collision: declaration-order suffix
        }
        // A property that came from a DECLARATION — the method name or a @By value — must resolve: the
        // declaration is the query, so a typo or an unparseable keyword suffix would otherwise silently
        // query a column that does not exist. Properties taken from parameter NAMES stay lenient, since
        // the application chose those names for the signature, not as criteria.
        boolean annotatedParams = parameters.stream().anyMatch(p -> p.getAnnotation(By.class) != null);
        PathOrigin origin = (parsed != null || annotatedParams) ? PathOrigin.DECLARED : PathOrigin.INFERRED;
        // resolve every method-name predicate property through the compile model, so a path
        // that traverses a relation (Reference / EntityCollection / ElementCollection) resolves at compile time,
        // a to-one head contributing a LEFT JOIN and a to-many head an EXISTS semi-join.
        // Local and embedded paths resolve to plain unqualified refs.
        List<PathResolution> predicatePaths = pathResolver.resolveCompilePaths(entity,
                predicates.stream().map(Predicate::property).toList(), method, origin);
        if (origin.failsOnUnresolved() && predicatePaths.stream().anyMatch(p -> p.placement() == null)) {
            // A declared (method-name or @By) property did not resolve: the ERROR is already reported,
            // so no implementation must be rendered for this method.
            return null;
        }
        return new FindInput(name, parameters, parsed, operation, distinct, parameterTypes, paramBindings,
                returnMirror, kind, queryDefinitionName, predicates,
                predicatePaths.stream().map(PathResolution::placement).toList(),
                predicatePaths.stream().map(PathResolution::attributePath).toList());
    }

    /**
     * One method-shape delegation: {@link FindEmitter} is the body. The repository-fixed half of its
     * inputs travels as the {@link RepositoryEmission} rather than as the four values it used to be
     * unpacked into — the read context plus the three collections the emitter appends to. All four
     * came from the same object at both call sites, and three of them are {@code List}s, so passing
     * them separately was an ordering bug waiting to compile.
     */
    private void emitFind(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                          RepositoryEmission emission, boolean explicitFind, boolean explicitDelete) {
        new FindEmitter(builder, method, resolved, emission, explicitFind, explicitDelete).run();
    }

    /**
     * Method-object for the find/count/delete emitters. Split from {@code emitFind} because the method
     * had grown to a count, a delete, a paged and a plain-find path in one body. A non-static inner
     * class so it reaches the host's collaborators ({@link QueryPlanCompiler#queryDefinitionOf}, {@link SqlConstants#register},
     * {@link PagingEmitter#emitPaged}, ...) unchanged; those collaborators, not this split, are the
     * seam the next extraction moves.
     */
    private final class FindEmitter {
        private final MethodSpec.Builder builder;
        private final ExecutableElement method;
        private final ExecutableType resolved;
        /**
         * The repository-fixed read inputs, held WHOLE rather than unpacked into four fields: the
         * hydration flag used to be the fifth positional argument of {@link PagingEmitter#emitPaged}
         * beside the entity, the constants and the mapper — a {@code boolean} among four values that
         * are always passed together. This record already owns all four, so holding it removes the
         * flag from every signature below without inventing a second bundle.
         */
        private final EmissionContext ctx;
        private final List<MethodSpec> auxMethods;
        /** The key this repository's published query definitions are stored under in the registry. */
        private final String repositoryFqn;
        private final Map<String, Integer> queryDefinitionNames;
        private final boolean explicitFind;
        private final boolean explicitDelete;

        FindEmitter(MethodSpec.Builder builder, ExecutableElement method, ExecutableType resolved,
                    RepositoryEmission emission, boolean explicitFind, boolean explicitDelete) {
            this.builder = builder;
            this.method = method;
            this.resolved = resolved;
            this.ctx = emission.readContext();
            this.auxMethods = emission.auxMethods();
            this.repositoryFqn = emission.repositoryFqn();
            this.queryDefinitionNames = emission.queryDefinitionNames();
            this.explicitFind = explicitFind;
            this.explicitDelete = explicitDelete;
        }

        void run() {
            FindInput input = resolveFindInput(method, resolved, ctx.entity(), queryDefinitionNames,
                    explicitFind, explicitDelete);
            if (input == null) {
                return;
            }
            if (input.operation() == Operation.COUNT || input.operation() == Operation.EXISTS) {
                emitCountOrExists(input);
            } else if (input.operation() == Operation.DELETE) {
                emitDelete(input);
            } else {
                emitFindRead(input);
            }
        }

        /**
         * Builds this method's {@link QueryDefinition.Criteria}, publishes it under the generated field
         * name in the session registry (the source the {@code <Repository>Queries} holder is written
         * from), and returns it so the caller renders the SQL constant from the very same object.
         */
        private QueryDefinition.Criteria publish(FindInput input, QueryPlan plan, boolean distinct,
                Operation operation, ResultKind kind, ResultKind resultKind) {
            QueryDefinition.Criteria definition =
                    QueryPlanCompiler.queryDefinitionOf(operation, kind, resultKind, plan, distinct);
            registry.queryDefinitions(repositoryFqn).put(input.queryDefinitionName(), definition);
            return definition;
        }

        private void emitCountOrExists(FindInput input) {
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(),
                            input.predicateAttributePaths(), List.of(), List.of(), List.of(), List.of(), 0,
                            input.paramBindings(), input.parameters()),
                    input.distinct(), input.operation(), input.kind(), input.kind());
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).count());
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            call.add(".singleValue($T.class)", Long.class);
            if (input.operation() == Operation.EXISTS) {
                builder.addStatement("return $L > 0", call.build());
            } else if (input.kind() == ResultKind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
        }

        private void emitDelete(FindInput input) {
            List<ColumnPlacement> predicateRefs = input.predicateRefs();
            List<String> predicatePaths = input.predicateAttributePaths();
            // A delete whose predicate traverses a relation renders correlated EXISTS
            // semi-joins through SqlBuilder.build's delete mode — the old NOTE-degrade to the join-free
            // legacy predicate could match the wrong rows, and a joined DELETE ... LEFT JOIN is
            // invalid SQL. A multi-hop (2+ join) delete would need a NESTED EXISTS (each intermediate
            // relationship opens its own correlated subquery); that is not rendered yet, so it keeps
            // the legacy NOTE-degrade exactly as before.
            List<ColumnPlacement> deleteRefs = predicateRefs;
            List<String> deletePaths = predicatePaths;
            for (int i = 0; i < predicateRefs.size(); i++) {
                ColumnPlacement ref = predicateRefs.get(i);
                if (ref != null && ref.edges().size() > 1) {
                    diagnostics.note(
                            "path '" + input.predicates().get(i).property() + "' traverses more than one relation; not supported", method);
                    if (deleteRefs == predicateRefs) {
                        deleteRefs = new ArrayList<>(predicateRefs);
                    }
                    // The NOTE-degraded predicate falls back to a bare column, so its published
                    // attribute path must fall back with it rather than keep the traversal's.
                    deletePaths = new ArrayList<>(predicatePaths);
                    deletePaths.set(i, null);
                    deleteRefs.set(i, PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(input.predicates().get(i).property())));
                }
            }
            // A delete-by-criteria is a write: a void method reports nothing, anything else
            // gets the affected-row count the .update() call already returns.
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), deleteRefs, deletePaths,
                            List.of(), List.of(), List.of(), List.of(), 0, input.paramBindings(), input.parameters()),
                    false, Operation.DELETE, input.kind(),
                    writeResultKindOf(input.returnMirror(), input.kind(), ctx.entity()));
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).delete());
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            call.add(".update()");
            if (input.kind() == ResultKind.VOID) {
                builder.addStatement("$L", call.build());
            } else {
                builder.addStatement("return $L", call.build());
            }
        }

        private void emitFindRead(FindInput input) {
            List<SortSpec> methodSorts = queryPlanCompiler.sorts(method, input.parsed());
            // Case-insensitive ordering is rendered for plain sorts only. A paged read bakes its own
            // runtime ORDER BY, and a to-many sort orders by an aggregate whose cursor key is reduced
            // case-sensitively — accepting either would silently ignore the request.
            for (SortSpec sort : methodSorts) {
                if (!sort.ignoreCase()) {
                    continue;
                }
                boolean paged = input.kind() == ResultKind.PAGE || input.kind() == ResultKind.CURSORED_PAGE;
                boolean toMany = pathResolver.resolveCompilePath(ctx.entity(), sort.property()).existential();
                if (paged || toMany) {
                    diagnostics.error(
                            input.name() + ": IgnoreCase ordering is not supported for "
                                    + (paged ? "paged reads" : "a to-many sort path ('" + sort.property() + "')"),
                            method);
                    return;
                }
            }
            if (input.kind() == ResultKind.CURSORED_PAGE || input.kind() == ResultKind.PAGE) {
                emitPagedFind(input, methodSorts);
            } else {
                emitPlainFind(input, methodSorts);
            }
        }

        private void emitPagedFind(FindInput input, List<SortSpec> methodSorts) {
            String pageRequest = queryPlanCompiler.specialParameter(input.parameters(), input.parameterTypes(), QueryPlanCompiler.PAGE_REQUEST);
            if (pageRequest == null) {
                diagnostics.error(
                        input.name() + " returning " + (input.kind() == ResultKind.PAGE ? "Page" : "CursoredPage")
                                + " requires a PageRequest parameter", method);
                return;
            }
            // full materialisation through a joined read is not supported for paged finders —
            // the runtime sort/seek wiring appends its own join chain to the base constant. A paged
            // finder whose compile-time predicates traverse a Reference is degraded to the normal mapper
            // (association fields stay id-only partials read by the base row mapper), with a NOTE.
            if (!joinedProjections.collectTraversedReferences(ctx.entity(), input.predicateRefs(), List.of()).isEmpty()) {
                diagnostics.note(
                        input.name() + ": full materialisation through joins is not supported for paged finders;"
                                + " association fields materialize as id-only partials", method);
            }
            // The base constant carries the compile-time predicates (join-aware); method-name sort
            // paths are resolved per-emit in emitPaged (local / to-one qualified / to-many
            // aggregate), so no sort refs are baked into this constant.
            List<PathResolution> pagedSortPaths = methodSorts.stream()
                    .map(s -> pathResolver.resolveCompilePath(ctx.entity(), s.property()))
                    .toList();
            List<ColumnPlacement> pagedSortRefs = new ArrayList<>();
            List<Boolean> pagedExistentialSorts = new ArrayList<>();
            List<String> pagedSortAttributePaths = new ArrayList<>();
            for (int i = 0; i < pagedSortPaths.size(); i++) {
                // NOTE-free resolution: emitPaged rejects a multi-hop sort path right after this point,
                // so the model degrades a ref-less rejection to the legacy local ref instead of
                // pre-emitting a second diagnostic (and never serialises a null ref).
                ColumnPlacement ref = pagedSortPaths.get(i).placement();
                pagedSortRefs.add(ref == null
                        ? PathResolver.placementOf(PathResolver.snakeCasedLocalColumnRef(methodSorts.get(i).property())) : ref);
                pagedExistentialSorts.add(ref != null && pagedSortPaths.get(i).existential());
                // A ref-less entry is NOTE-degraded to a bare column, so its published path degrades too.
                pagedSortAttributePaths.add(ref != null ? pagedSortPaths.get(i).attributePath() : null);
            }
            QueryDefinition.Criteria definition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(), input.predicateAttributePaths(),
                            methodSorts, pagedSortRefs, pagedSortAttributePaths, pagedExistentialSorts, 0,
                            input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()),
                    new SqlBuilder(ctx.entity(), ctx.entity(), definition).dialect(sqlDialect).select());
            pagingEmitter.emitPaged(builder, ctx, new PagedRead(method, pageRequest, methodSorts, constant,
                    input.paramBindings(), input.parameters(), input.kind() == ResultKind.CURSORED_PAGE));
        }

        private void emitPlainFind(FindInput input, List<SortSpec> methodSorts) {
            // A declared order term is part of the query too, so a property that does not resolve errors.
            PathOrigin sortOrigin = (input.parsed() != null || method.getAnnotationsByType(OrderBy.class).length > 0)
                    ? PathOrigin.DECLARED : PathOrigin.INFERRED;
            List<PathResolution> sortPaths = pathResolver.resolveCompilePaths(ctx.entity(),
                    methodSorts.stream().map(SortSpec::property).toList(), method, sortOrigin);
            if (sortOrigin.failsOnUnresolved() && sortPaths.stream().anyMatch(p -> p.placement() == null)) {
                // A declared order property did not resolve: ERROR already reported, skip the method.
                return;
            }
            List<ColumnPlacement> sortRefs = sortPaths.stream().map(PathResolution::placement).toList();
            List<Boolean> existentialSorts = sortPaths.stream().map(PathResolution::existential).toList();
            List<String> sortAttributePaths = sortPaths.stream().map(PathResolution::attributePath).toList();

            // when a method-name predicate/sort path traversed a Reference,
            // the SELECT carries the target's aliased flattened columns and the read uses a generated
            // per-method full mapper. Only for non-paged ctx.entity()-returning finders with a generated row
            // mapper to delegate the root columns to; otherwise the projection stays root-only (partial).
            List<Prop.Reference> traversed = joinedProjections.collectTraversedReferences(ctx.entity(), input.predicateRefs(), sortRefs);
            boolean fullMaterialisation = !traversed.isEmpty() && ctx.mapperType() != null
                    && input.operation() == Operation.FIND && input.kind() != ResultKind.VOID;
            String extraProjection = fullMaterialisation ? joinedProjections.joinedProjection(ctx.entity(), traversed) : "";
            CodeBlock queryArgForMethod = QueryInvocations.queryArg(
                    typeMappingBuilder.classNameFor(ctx.entity().typeName()), ctx.mapperType());
            if (fullMaterialisation) {
                String fullMapper = "mapRow_full_" + Names.toSnakeCase(input.name());
                auxMethods.add(joinedProjections.buildFullMapper(fullMapper, ctx.entity(), ctx.mapperType(), traversed));
                queryArgForMethod = CodeBlock.of("(rs, rowNum) -> $L(rs, rowNum)", fullMapper);
            }
            QueryDefinition.Criteria queryDefinition = publish(input,
                    new QueryPlan(ctx.entity(), input.predicates(), input.predicateRefs(), input.predicateAttributePaths(),
                            methodSorts, sortRefs, sortAttributePaths, existentialSorts,
                            input.parsed() != null ? ((io.github.fludakit.data.qbn.ParsedMethodName) input.parsed()).limit() : 0, input.paramBindings(), input.parameters()),
                    input.distinct(), Operation.FIND, input.kind(), input.kind());
            String sql = new SqlBuilder(ctx.entity(), ctx.entity(), queryDefinition)
                    .dialect(sqlDialect).select(extraProjection);
            String constant = SqlConstants.register(ctx.constants(), "SQL_" + MemberNames.upperSnake(input.name()), sql);
            CodeBlock.Builder call = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            QueryInvocations.appendBoundParameters(call, input.paramBindings(), input.parameters());
            emitFindTerminal(input, call, queryArgForMethod);
        }

        private void emitFindTerminal(FindInput input, CodeBlock.Builder call, CodeBlock queryArgForMethod) {
            // The array shape is the declared return type's, so it needs toArray at the end — a List
            // terminal alone would not satisfy it.
            if (input.kind() == ResultKind.ARRAY) {
                call.add(".query($L).list()", queryArgForMethod);
                if (!ctx.hydrate()) {
                    builder.addStatement("return $L.toArray($T[]::new)", call.build(), queryResultType(input.returnMirror(),
                            typeMappingBuilder.classNameFor(ctx.entity().typeName())));
                } else {
                    builder.addStatement("$T<$T> results = $L",
                            List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("results"));
                    builder.addStatement("return results.toArray($T[]::new)",
                            queryResultType(input.returnMirror(), typeMappingBuilder.classNameFor(ctx.entity().typeName())));
                }
                return;
            }
            if (!ctx.hydrate() || input.kind() == ResultKind.VOID) {
                QueryInvocations.terminal(call, input.kind(), queryArgForMethod);
                if (input.kind() == ResultKind.VOID) {
                    builder.addStatement("$L", call.build());
                } else {
                    builder.addStatement("return $L", call.build());
                }
                return;
            }
            // Hydrated read shapes: land the query result in a local, run the umbrella loader, then return.
            switch (input.kind()) {
                case LIST, STREAM -> {
                    call.add(".query($L).list()", queryArgForMethod);
                    builder.addStatement("$T<$T> results = $L", List.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("results"));
                    builder.addStatement(input.kind() == ResultKind.STREAM ? "return results.stream()" : "return results");
                }
                case OPTIONAL -> {
                    call.add(".query($L).optional()", queryArgForMethod);
                    builder.addStatement("$T<$T> found = $L", java.util.Optional.class, typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("found.ifPresent(e -> $L)", QueryInvocations.hydrationCall("java.util.List.of(e)"));
                    builder.addStatement("return found");
                }
                case SINGLE -> {
                    call.add(".query($L).single()", queryArgForMethod);
                    builder.addStatement("$T found = $L", typeMappingBuilder.classNameFor(ctx.entity().typeName()), call.build());
                    builder.addStatement("$L", QueryInvocations.hydrationCall("java.util.List.of(found)"));
                    builder.addStatement("return found");
                }
                default -> {
                    QueryInvocations.terminal(call, input.kind(), queryArgForMethod);
                    builder.addStatement("$L", call.build());
                }
            }
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

    private ResultKind resultKindOf(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.VOID) {
            return ResultKind.VOID;
        }
        if (returnType.getKind() == TypeKind.ARRAY) {
            return ResultKind.ARRAY;
        }
        return switch (typeShapes.erasedName(returnType)) {
            // A Set is materialized into a LinkedHashSet: .list() alone returns a List and would not
            // compile against the declared return type.
            case "java.util.Set" -> ResultKind.SET;
            // A List satisfies Collection and Iterable, so those two need no shape of their own — the
            // materialized list is returned as-is.
            case "java.util.List", "java.util.Collection", "java.lang.Iterable" -> ResultKind.LIST;
            case "java.util.Optional" -> ResultKind.OPTIONAL;
            case "java.util.stream.Stream" -> ResultKind.STREAM;
            case "jakarta.data.page.Page" -> ResultKind.PAGE;
            case "jakarta.data.page.CursoredPage" -> ResultKind.CURSORED_PAGE;
            default -> ResultKind.SINGLE;
        };
    }

    private TypeName queryResultType(TypeMirror returnType, TypeName fallback) {
        if (returnType.getKind() == TypeKind.ARRAY) {
            return TypeName.get(((ArrayType) returnType).getComponentType()).box();
        }
        if (returnType instanceof DeclaredType declared && declared.getTypeArguments().size() == 1
                && CONTAINER_TYPES.contains(typeShapes.erasedName(declared))) {
            return concrete(TypeName.get(declared.getTypeArguments().get(0)), fallback);
        }
        return concrete(TypeName.get(returnType).box(), fallback);
    }

    private TypeName concrete(TypeName candidate, TypeName fallback) {
        return candidate instanceof TypeVariableName ? fallback : candidate;
    }

    /**
     * The result shape a WRITE declares. Three shapes, and the return TYPE is what separates the
     * second from the third — the declared read shape alone cannot, because {@code long}, {@code int}
     * and the entity itself all classify as {@link ResultKind#SINGLE}.
     *
     * <ul>
     *   <li>{@code void} reports nothing.</li>
     *   <li>The entity type hands the instance back: an INSERT/UPDATE that returns it lets the caller
     *       see the identifier the database assigned.</li>
     *   <li>Anything else — {@code long}, {@code int}, {@code boolean} — is the affected-row count the
     *       JDBC update returns.</li>
     * </ul>
     */
    private ResultKind writeResultKindOf(TypeMirror returnType, ResultKind kind, TypeMapping<?> entity) {
        if (kind == ResultKind.VOID) {
            return ResultKind.VOID;
        }
        return typeShapes.isEntity(returnType, entity)
                ? ResultKind.SINGLE
                : ResultKind.AFFECTED;
    }
}
