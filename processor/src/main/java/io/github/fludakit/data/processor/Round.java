package io.github.fludakit.data.processor;

import io.github.fludakit.data.processor.generator.RepositoryImplGenerator;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * One processing ROUND: the session-scoped primitives, the round's ANALYZE result, and the round's
 * generators. The constructor runs ANALYZE (building {@link Analysis}); {@link #plan} builds the
 * {@link Generators}; the caller then hands each repository to the generator.
 */
public final class Round {

    private final Session session;
    private final Analysis analysis;
    private Generators generators;

    public Round(Session session, RoundEnvironment roundEnv) {
        this.session = session;
        this.analysis = new Analysis(session, roundEnv);
    }

    /** Builds the round's generators and emitters in dependency order. */
    public void plan() {
        this.generators = new Generators(session, analysis);
    }

    public Session session() {
        return session;
    }

    public Analysis analysis() {
        return analysis;
    }

    public Generators generators() {
        return generators;
    }

    public List<TypeElement> repositories() {
        return analysis.repositories();
    }

    public RepositoryImplGenerator generator() {
        return generators.generator();
    }
}
