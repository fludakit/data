package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.emitter.RepositoryMemberEmitter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.writer.MetamodelWriter;
import io.github.fludakit.data.processor.writer.RepositoryWriter;
import io.github.fludakit.data.processor.writer.RowMapperWriter;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlDialect;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Set;

@SupportedAnnotationTypes({
    "jakarta.data.repository.Repository",
    "io.github.fludakit.data.annotation.Entity",
    "io.github.fludakit.data.annotation.ValueObject",
    "io.github.fludakit.data.annotation.AggregateRoot"
})
@SupportedOptions("fluda.dialect")
public class RepositoryProcessor extends AbstractProcessor {

    private Diagnostics diagnostics;
    private Types types;
    private Elements elements;
    private Filer filer;
    private SqlDialect dialect;

    /** The type cache for the whole compilation: populated by the early scan, read by everything else. */
    private final TypeGraph typeGraph = new TypeGraph();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.diagnostics = new Diagnostics(processingEnv.getMessager());
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.filer = processingEnv.getFiler();
        this.dialect = resolveDialect(processingEnv.getOptions().get("fluda.dialect"));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        // Phase 1: Scan annotated types into the shared TypeGraph
        try {
            new TypeDefinitionScanner(types, elements, diagnostics, typeGraph).scanRound(roundEnv);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            diagnostics.error("fluda-data: " + msg, null);
        }

        // Phase 2: Write graph-owned artifacts once from TypeGraph (before any repository work)
        writeRowMappers();
        writeMetamodels();

        // Phase 3: Analyze each @Repository, emit members, write Repository_
        for (Element element : roundEnv.getElementsAnnotatedWith(jakarta.data.repository.Repository.class)) {
            if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE) continue;
            TypeElement repository = (TypeElement) element;
            try {
                RepositoryContext ctx = new RepositoryAnalyzer(
                        repository, types, elements, diagnostics, typeGraph).resolveContext();
                if (ctx == null) continue;
                new RepositoryMemberEmitter(repository, types, elements, dialect).emit(ctx);
                new RepositoryWriter(repository, ctx, filer, diagnostics).write();
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                diagnostics.error(
                    "fluda-data could not generate " + repository.getSimpleName() + "_: " + msg,
                    repository);
            }
        }

        return false;
    }

    /** Row mappers for managed entities/aggregates and element-collection value objects. */
    private void writeRowMappers() {
        RowMapperWriter writer = new RowMapperWriter(types, elements, filer);

        for (String fqn : typeGraph.managedEntities()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def instanceof TypeDefinition.Entity || def instanceof TypeDefinition.Aggregate) {
                writer.write(def);
            }
        }

        for (String fqn : typeGraph.elementCollectionValueObjects()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def instanceof TypeDefinition.ValueObject) {
                writer.write(def);
            }
        }
    }

    /** Static metamodels for every registered type (entities, aggregates, value objects). */
    private void writeMetamodels() {
        MetamodelWriter writer = new MetamodelWriter(filer, diagnostics);
        for (String fqn : typeGraph.nodes()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def != null) {
                writer.write(def);
            }
        }
    }

    private SqlDialect resolveDialect(String option) {
        if ("postgresql".equalsIgnoreCase(option)) return io.github.fludakit.data.sql.PostgreSqlDialect.INSTANCE;
        return H2Dialect.INSTANCE;
    }
}
