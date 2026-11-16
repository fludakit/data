package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * Orchestrates method-body emitters for one resolved repository: finders, lifecycle, writes,
 * and hydration helpers. Does not write files — {@code RepositoryWriter} owns the Filer.
 */
public final class RepositoryMemberEmitter {

    private final TypeElement repository;
    private final Types types;
    private final Elements elements;
    private final SqlDialect dialect;

    public RepositoryMemberEmitter(TypeElement repository, Types types, Elements elements, SqlDialect dialect) {
        this.repository = repository;
        this.types = types;
        this.elements = elements;
        this.dialect = dialect;
    }

    public void emit(RepositoryContext ctx) {
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
