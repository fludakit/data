package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.RepositoryGraph;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * Orchestrates method-body emitters for every resolved repository: finders, lifecycle, writes,
 * and hydration helpers. Appends results to each repository's {@link RepositoryContext}; does not
 * write files — {@code RepositoryWriter} owns the Filer.
 */
public final class RepositoryMemberEmitter {

    private final Types types;
    private final Elements elements;
    private final SqlDialect dialect;
    private final Diagnostics diagnostics;

    public RepositoryMemberEmitter(Types types, Elements elements, SqlDialect dialect, Diagnostics diagnostics) {
        this.types = types;
        this.elements = elements;
        this.dialect = dialect;
        this.diagnostics = diagnostics;
    }

    /** Emits the members of every repository in the graph that has not been emitted yet. */
    public void emit(RepositoryGraph graph) {
        for (String fqn : graph.repositories()) {
            RepositoryContext ctx = graph.context(fqn);
            if (ctx == null || ctx.status() != RepositoryContext.Status.PLANNED) continue;
            TypeElement repository = graph.repository(fqn);
            try {
                emitRepository(repository, ctx);
                ctx.markEmitted();
            } catch (RuntimeException e) {
                ctx.markFailed();
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                diagnostics.error(
                    "fluda-data could not generate " + repository.getSimpleName() + "_: " + msg,
                    repository);
            }
        }
    }

    /** Emits one repository's members: finders, lifecycle, writes, hydration. */
    private void emitRepository(TypeElement repository, RepositoryContext ctx) {
        DeclaredType baseRepositoryType = ctx.baseRepositoryType();
        DeclaredType repositoryOwnType = (DeclaredType) repository.asType();
        emitFinders(ctx, baseRepositoryType, repositoryOwnType);
        emitLifecycleMethods(ctx, baseRepositoryType, repositoryOwnType);
        emitWriteMethods(ctx, baseRepositoryType, repositoryOwnType);
        emitHydrationMethods(ctx, ctx.entity());
    }

    private void emitFinders(RepositoryContext ctx, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        FindEmitter findEmitter = new FindEmitter(dialect, types, baseRepositoryType, repositoryOwnType);
        for (MethodContext mc : ctx.methods()) {
            MethodSpec emitted = findEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }
    }

    private void emitLifecycleMethods(RepositoryContext ctx, DeclaredType baseRepositoryType,
                                      DeclaredType repositoryOwnType) {
        LifecycleEmitter lifecycleEmitter = new LifecycleEmitter(types, baseRepositoryType, repositoryOwnType);
        for (MethodContext mc : ctx.methods()) {
            MethodSpec emitted = lifecycleEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }
    }

    private void emitWriteMethods(RepositoryContext ctx, DeclaredType baseRepositoryType,
                                  DeclaredType repositoryOwnType) {
        WriteEmitter writeEmitter = new WriteEmitter(dialect, types, elements, baseRepositoryType, repositoryOwnType);
        for (MethodContext mc : ctx.methods()) {
            MethodSpec emitted = writeEmitter.emit(ctx, mc);
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        }
    }

    private void emitHydrationMethods(RepositoryContext ctx, TypeDefinition entityMapping) {
        HydrationEmitter hydrationEmitter = new HydrationEmitter(dialect);
        if (hydrationEmitter.hasCollectionsToHydrate(entityMapping)) {
            MethodSpec hydrateMethod = hydrationEmitter.emitHydrate(ctx);
            if (hydrateMethod != null) {
                ctx.auxMethods().add(hydrateMethod);
            }
            MethodSpec hydrateListMethod = hydrationEmitter.emitHydrateList(ctx);
            if (hydrateListMethod != null) {
                ctx.auxMethods().add(hydrateListMethod);
            }
            List<MethodSpec> loaders = hydrationEmitter.emitCollectionLoaders(ctx);
            ctx.auxMethods().addAll(loaders);
        }
    }
}