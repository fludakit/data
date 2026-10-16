package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.analysis.EntityAnalysis;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.nav.NavigationGenerator;
import io.github.fludakit.data.processor.generator.metadata.QueriesWriter;
import io.github.fludakit.data.processor.generator.RepositoryGenerator;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.PostgreSqlDialect;
import io.github.fludakit.data.sql.SqlDialect;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Generates five artifacts at compile time: a {@code <Repository>Impl} class for every
 * {@link jakarta.data.repository.Repository @Repository} interface, a reflection-free
 * {@code <Entity>Metadata} class implementing {@link io.github.fludakit.data.metadata.TypeMapping}
 * (the runtime metamodel for the entity), a {@code <Entity>RowMapper} for entities whose fields are
 * all JDBC-safe or embeddable value objects, a Jakarta Data 1.0 {@code _Entity} static metamodel
 * interface per aggregate root (see {@link MetamodelGenerator}) with one navigable member class per
 * embedded or relation member (see {@link NavigationGenerator}), and — only when the repository
 * declares at least one query-by-names or {@code @Find} finder — a {@code <Repository>Queries} holder
 * publishing one nested {@link io.github.fludakit.data.query.QueryDefinition} per query-by-names
 * method (see {@link QueriesWriter}).
 *
 * <p>The generated implementation class is
 * {@link jakarta.enterprise.context.ApplicationScoped @ApplicationScoped} and carries a single
 * injected collaborator, a {@link io.github.fludakit.jdbc.JdbcClient}. Every query's SQL is emitted as
 * a {@code private static final String} constant and executed with direct
 * {@code jdbcClient.sql(...).param(...).query(...)} calls: there is no runtime query model and no
 * engine indirection. The entity's table/columns are derived at compile time from its fields by
 * convention (see {@link io.github.fludakit.data.Names}).</p>
 *
 * <p>This class is the APT entry point and nothing else: it reads the {@code fluda.dialect} option,
 * assembles each round's collaborators (see {@link RoundWiring}) and hands every repository to
 * {@link RepositoryGenerator}. The caches that must survive a round live in
 * {@link GeneratedArtifactRegistry}; the per-entity compile-time model and the on-demand artifact
 * resolution live in {@link EntityAnalysis}.</p>
 */
@SupportedAnnotationTypes("jakarta.data.repository.Repository")
@SupportedOptions("fluda.dialect")
public class RepositoryProcessor extends AbstractProcessor {

    private SqlDialect sqlDialect = H2Dialect.INSTANCE;

    /** Everything this session has already emitted or claimed a name for — survives rounds. */
    private final GeneratedArtifactRegistry registry = new GeneratedArtifactRegistry();

    /**
     * The entity/value-object model builder. Session-scoped: the descriptor memo it keeps is what
     * stops a value object reached from two owners from being analysed twice.
     */
    private MappedTypeBuilder typeMappingBuilder;

    /** The only holder of the {@code Filer} in this package. Session-scoped for the same reason. */
    private RepositorySourceWriter sourceWriter;

    /**
     * The diagnostic channel every generator reports through. Built once from the environment's
     * {@code Messager}: {@link Diagnostics} is the package's only adapter onto it, and nothing below
     * this class names {@code Messager} any more.
     */
    private Diagnostics diagnostics;

    /** This round's collaborator assembly. */
    private RoundWiring wiring;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        String name = processingEnv.getOptions().get("fluda.dialect");
        if (name == null || name.isBlank() || "h2".equalsIgnoreCase(name)) {
            sqlDialect = H2Dialect.INSTANCE;
        } else if ("postgres".equalsIgnoreCase(name) || "postgresql".equalsIgnoreCase(name)) {
            sqlDialect = PostgreSqlDialect.INSTANCE;
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Unknown SQL dialect '" + name + "' — falling back to h2");
            sqlDialect = H2Dialect.INSTANCE;
        }
        this.diagnostics = Diagnostics.of(processingEnv.getMessager());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        // Session-scoped, so built on the first round only: the type-mapping builder memoises the
        // entity and value-object descriptors, and the source writer is the package's only holder of
        // the Filer — neither may be rebuilt mid-session, or a name already written would be writable
        // again.
        if (typeMappingBuilder == null) {
            typeMappingBuilder = new MappedTypeBuilder(processingEnv.getTypeUtils(),
                    processingEnv.getElementUtils(), diagnostics);
        }
        if (sourceWriter == null) {
            sourceWriter = new RepositorySourceWriter(processingEnv.getFiler(), diagnostics,
                    registry.claimedNames());
        }
        this.wiring = new RoundWiring(processingEnv, sqlDialect, registry,
                typeMappingBuilder, sourceWriter, roundEnv);
        boolean claimed = false;
        for (TypeElement repository : wiring.repositories()) {
            generateSafely(repository);
            claimed = true;
        }
        return claimed;
    }

    /**
     * One channel for every failure the generator cannot recover from. An exception escaping
     * {@link RepositoryGenerator#generate} reaches javac, which reports it as a stack trace and ABORTS
     * THE ROUND — so one repository the processor cannot handle would also cost every other repository
     * in the same round its implementation. Reporting it as an ERROR on the declaration that caused it
     * and carrying on keeps the failure where it belongs.
     *
     * <p>This is the net, not the fix: a shape the model cannot express must be REJECTED where it is
     * recognised (see {@code MappedTypeBuilder}'s nested element-collection diagnostic), so
     * that the user gets the reason instead of an exception class. What reaches here is a shape nobody
     * anticipated, and the message says so.</p>
     */
    private void generateSafely(TypeElement repository) {
        try {
            wiring.generator().generate(repository);
        } catch (RuntimeException failure) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "fluda-data could not generate " + repository.getSimpleName() + "Impl: "
                            + describe(failure) + " — the repository shape is not supported; no implementation"
                            + " was generated", repository);
        }
    }

    /** An exception rendered for a diagnostic: its type when it carries no message of its own. */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
