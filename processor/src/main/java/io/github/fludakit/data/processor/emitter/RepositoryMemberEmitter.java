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
import java.util.function.Supplier;

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
            emitGuarded(ctx, mc, () -> findEmitter.emit(ctx, mc));
        }
    }

    private void emitLifecycleMethods(RepositoryContext ctx, DeclaredType baseRepositoryType,
                                      DeclaredType repositoryOwnType) {
        LifecycleEmitter lifecycleEmitter = new LifecycleEmitter(types, baseRepositoryType, repositoryOwnType);
        for (MethodContext mc : ctx.methods()) {
            emitGuarded(ctx, mc, () -> lifecycleEmitter.emit(ctx, mc));
        }
    }

    private void emitWriteMethods(RepositoryContext ctx, DeclaredType baseRepositoryType,
                                  DeclaredType repositoryOwnType) {
        WriteEmitter writeEmitter = new WriteEmitter(dialect, types, elements, baseRepositoryType, repositoryOwnType);
        for (MethodContext mc : ctx.methods()) {
            emitGuarded(ctx, mc, () -> writeEmitter.emit(ctx, mc));
        }
    }

    /**
     * Runs one method's emitter. A failure is reported on that method's element and only that
     * method is skipped (AGENTS.md "Fail early, per method"); the rest of the
     * repository is still generated.
     */
    private void emitGuarded(RepositoryContext ctx, MethodContext mc, Supplier<MethodSpec> emitter) {
        try {
            MethodSpec emitted = emitter.get();
            if (emitted != null) {
                ctx.auxMethods().add(emitted);
            }
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            diagnostics.error("fluda-data could not generate method '" + mc.name() + "': " + msg,
                    mc.method());
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