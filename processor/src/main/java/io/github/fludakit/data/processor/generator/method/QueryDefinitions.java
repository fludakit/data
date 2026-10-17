package io.github.fludakit.data.processor.generator.method;

import io.github.fludakit.data.processor.generator.RepositoryEmission;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.QueryDefinition;

/**
 * Publishes a method's {@link QueryDefinition} into the session registry under the field name the
 * generated {@code <Repository>Queries} holder writes it as.
 *
 * <p>The name is {@code <Method>Query}, suffixed when an overload already took it — the same rule the
 * finder emitter applies, so the holder's field names stay consistent whichever shape the method came
 * from.
 */
public final class QueryDefinitions {

    private QueryDefinitions() {
    }

    public static void publish(GeneratedArtifactRegistry registry, RepositoryEmission emission,
                               String methodName, QueryDefinition definition) {
        String name = MemberNames.capitalize(methodName) + "Query";
        int clash = emission.queryDefinitionNames().merge(name, 1, Integer::sum);
        if (clash > 1) {
            name = name + clash;
        }
        registry.queryDefinitions(emission.repositoryFqn()).put(name, definition);
    }
}
