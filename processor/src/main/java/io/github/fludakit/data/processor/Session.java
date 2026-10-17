package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.RepositorySourceWriter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.sql.SqlDialect;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The session-scoped primitives a processing round draws on: the compiler services ({@code Types},
 * {@code Elements}), the diagnostic channel, the configured SQL dialect, and the three pieces of state
 * that outlive a round — the generated-artifact registry, the source writer, and the type-model
 * builder.
 *
 * <p>This is the slim replacement for the round-assembly god object. It holds ONLY what is stable
 * across rounds; everything rebuilt per round (the generators and emitters, the managed-entity set, the
 * repository list) lives in {@code Round}, which is constructed from this context for one round
 * at a time.
 */
public final class Session {

    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final SqlDialect sqlDialect;
    private final GeneratedArtifacts registry;
    private final RepositorySourceWriter sourceWriter;
    private final TypeModelBuilder typeMappingBuilder;

    public Session(Types types, Elements elements, Diagnostics diagnostics,
                             SqlDialect sqlDialect, GeneratedArtifacts registry,
                             RepositorySourceWriter sourceWriter, TypeModelBuilder typeMappingBuilder) {
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.sqlDialect = sqlDialect;
        this.registry = registry;
        this.sourceWriter = sourceWriter;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    public Types types() {
        return types;
    }

    public Elements elements() {
        return elements;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public SqlDialect sqlDialect() {
        return sqlDialect;
    }

    public GeneratedArtifacts registry() {
        return registry;
    }

    public RepositorySourceWriter sourceWriter() {
        return sourceWriter;
    }

    public TypeModelBuilder typeMappingBuilder() {
        return typeMappingBuilder;
    }
}
