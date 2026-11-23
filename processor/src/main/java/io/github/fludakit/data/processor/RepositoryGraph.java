package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.context.RepositoryContext;

import javax.lang.model.element.TypeElement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory registry of every repository resolved during the compilation.
 *
 * <p>The repository-side twin of {@link TypeGraph}: a flat, FQN-keyed registry of the
 * repositories, each with the plan resolved for it. The key is the repository's own name, so two
 * repositories over one entity never collide — each owns its own generated bean.</p>
 */
public final class RepositoryGraph {

    /** repository FQN → the `@Repository` interface. */
    private final Map<String, TypeElement> repositories = new LinkedHashMap<>();

    /** repository FQN → the resolved plan and the output emitted for it. */
    private final Map<String, RepositoryContext> contexts = new LinkedHashMap<>();

    /** Registers a repository and the plan resolved for it. */
    void put(TypeElement repository, RepositoryContext context) {
        String fqn = repository.getQualifiedName().toString();
        repositories.put(fqn, repository);
        contexts.put(fqn, context);
    }

    /** The repository FQNs, in discovery order. */
    public Set<String> repositories() {
        return Collections.unmodifiableSet(repositories.keySet());
    }

    /** The `@Repository` interface behind a repository FQN, or `null`. */
    public TypeElement repository(String fqn) {
        return repositories.get(fqn);
    }

    /** The plan resolved for a repository FQN, or `null`. */
    public RepositoryContext context(String fqn) {
        return contexts.get(fqn);
    }

    /** Whether the graph holds no repository. */
    public boolean isEmpty() {
        return contexts.isEmpty();
    }
}