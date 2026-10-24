package io.github.fludakit.data.processor;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.emitter.MetamodelEmitter;
import io.github.fludakit.data.processor.emitter.RowMapperEmitter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlDialect;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
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

        // Phase 1: Scan types into the shared graph
        try {
            new TypeMappingVisitor(types, elements, diagnostics, typeGraph).scanRound(roundEnv);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            diagnostics.error("fluda-data: " + msg, null);
        }

        // Phase 2: Visit repositories
        for (Element element : roundEnv.getElementsAnnotatedWith(jakarta.data.repository.Repository.class)) {
            if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE) continue;
            TypeElement repository = (TypeElement) element;
            try {
                new RepositoryVisitor(repository, types, elements, diagnostics, filer, typeGraph, dialect).visit();
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                diagnostics.error(
                    "fluda-data could not generate " + repository.getSimpleName() + "_: " + msg,
                    repository);
            }
        }
        
        // Phase 3: Generate row mappers for all entity types (including association targets)
        generateMissingRowMappers();
        
        // Phase 4: Generate metamodels for all value objects (needed for get() path composition)
        generateValueObjectMetamodels();
        
        return false;
    }
    
    private void generateMissingRowMappers() {
        RowMapperEmitter emitter = new RowMapperEmitter(types, elements, filer);

        // Generate row mappers for entities and aggregates
        for (String fqn : typeGraph.managedEntities()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def != null && (def instanceof TypeDefinition.Entity || def instanceof TypeDefinition.Aggregate)) {
                emitter.emit(def);
            }
        }

        // Generate row mappers for value objects used in element collections
        for (String fqn : typeGraph.elementCollectionValueObjects()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def != null && def instanceof TypeDefinition.ValueObject) {
                emitter.emit(def);
            }
        }
    }

    private void generateValueObjectMetamodels() {
        MetamodelEmitter emitter = new MetamodelEmitter(filer, diagnostics);
        for (String fqn : typeGraph.valueObjects()) {
            TypeDefinition def = typeGraph.get(fqn);
            if (def != null) {
                emitter.emit(def);
            }
        }
    }

    private SqlDialect resolveDialect(String option) {
        if ("postgresql".equalsIgnoreCase(option)) return io.github.fludakit.data.sql.PostgreSqlDialect.INSTANCE;
        return H2Dialect.INSTANCE;
    }
}
