package io.github.fludakit.data.processor;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.ValueObject;
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
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // Pipeline stages: created once in init() and reused for every round. They hold only
    // compiler utilities, the dialect, Diagnostics and the two compilation-wide graphs, plus
    // per-compilation "already written" bookkeeping in the writers.
    private TypeDefinitionScanner typeScanner;
    private RowMapperWriter rowMapperWriter;
    private MetamodelWriter metamodelWriter;
    private RepositoryScanner repositoryScanner;
    private RepositoryMemberEmitter memberEmitter;
    private RepositoryWriter repositoryWriter;

    /** The type cache for the whole compilation: populated by the early scan, read by everything else. */
    private final TypeGraph typeGraph = new TypeGraph();

    /** The repository cache for the whole compilation: populated by the scan, read by the emitter and writer. */
    private final RepositoryGraph repositoryGraph = new RepositoryGraph();

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
        this.typeScanner = new TypeDefinitionScanner(types, elements, diagnostics, typeGraph);
        this.rowMapperWriter = new RowMapperWriter(elements, filer, diagnostics);
        this.metamodelWriter = new MetamodelWriter(filer, diagnostics);
        this.repositoryScanner = new RepositoryScanner(types, elements, diagnostics, typeGraph, repositoryGraph);
        this.memberEmitter = new RepositoryMemberEmitter(types, elements, dialect, diagnostics);
        this.repositoryWriter = new RepositoryWriter(filer, diagnostics);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        // The round's inputs, one list each: the mapped types and the repositories.
        List<TypeElement> mappedTypes = collect(roundEnv, ElementKind.CLASS,
                Entity.class, AggregateRoot.class, ValueObject.class);
        List<TypeElement> repositories = collect(roundEnv, ElementKind.INTERFACE,
                jakarta.data.repository.Repository.class);

        // Phase 1: Scan annotated types into the shared TypeGraph
        typeScanner.scanTypes(mappedTypes);

        // Phase 2: Write graph-owned artifacts once from TypeGraph (before any repository work)
        rowMapperWriter.write(typeGraph);
        metamodelWriter.write(typeGraph);

        // Phase 3: Resolve every repository into the shared RepositoryGraph, then emit and write from it
        repositoryScanner.scanRepositories(repositories);
        memberEmitter.emit(repositoryGraph);
        repositoryWriter.write(repositoryGraph);

        return false;
    }

    /**
     * Collects the round's elements carrying any of the given annotations, de-duplicated by
     * fully-qualified name and kept in discovery order.
     */
    private List<TypeElement> collect(RoundEnvironment roundEnv, ElementKind kind,
                                      Class<? extends Annotation>... annotations) {
        Map<String, TypeElement> byFqn = new LinkedHashMap<>();
        for (Class<? extends Annotation> annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != kind) continue;
                TypeElement type = (TypeElement) element;
                byFqn.putIfAbsent(type.getQualifiedName().toString(), type);
            }
        }
        return List.copyOf(byFqn.values());
    }

    private SqlDialect resolveDialect(String option) {
        if ("postgresql".equalsIgnoreCase(option)) return io.github.fludakit.data.sql.PostgreSqlDialect.INSTANCE;
        return H2Dialect.INSTANCE;
    }
}