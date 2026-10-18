package io.github.fludakit.data.processor;

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
    private TypeMappingVisitor typeMappingVisitor;

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
        this.typeMappingVisitor = new TypeMappingVisitor(types, elements, diagnostics);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;

        // Phase 1: Scan types
        typeMappingVisitor.scanRound(roundEnv);

        // Phase 2: Visit repositories
        for (Element element : roundEnv.getElementsAnnotatedWith(jakarta.data.repository.Repository.class)) {
            if (element.getKind() != javax.lang.model.element.ElementKind.INTERFACE) continue;
            TypeElement repository = (TypeElement) element;
            try {
                new RepositoryVisitor(repository, this).visit();
            } catch (RuntimeException e) {
                diagnostics.error(
                    "fluda-data could not generate " + repository.getSimpleName() + "_: "
                        + e.getMessage() + " -- the repository shape is not supported",
                    repository);
            }
        }
        return false;
    }

    private SqlDialect resolveDialect(String option) {
        if ("postgresql".equalsIgnoreCase(option)) return io.github.fludakit.data.sql.PostgreSqlDialect.INSTANCE;
        return H2Dialect.INSTANCE;
    }

    // Package-private accessors for visitors/emitters
    TypeMappingVisitor typeMappingVisitor() { return typeMappingVisitor; }
    public SqlDialect dialect() { return dialect; }
    public Filer filer() { return filer; }
    public Diagnostics diagnostics() { return diagnostics; }
    public Types types() { return types; }
    public Elements elements() { return elements; }
}
