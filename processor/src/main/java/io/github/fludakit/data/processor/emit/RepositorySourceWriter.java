package io.github.fludakit.data.processor.emit;

import com.squareup.javapoet.JavaFile;

import io.github.fludakit.data.processor.support.Diagnostics;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/** Writes generated repository sources and records every emitted type name for collision checks. */
public final class RepositorySourceWriter {

    private final Filer filer;
    private final Diagnostics diagnostics;
    private final Set<String> generatedTypeNames;

    public RepositorySourceWriter(Filer filer, Diagnostics diagnostics, Set<String> generatedTypeNames) {
        this.filer = filer;
        this.diagnostics = diagnostics;
        this.generatedTypeNames = generatedTypeNames;
    }

    public void write(String qualifiedName, Element origin, JavaFile javaFile) {
        generatedTypeNames.add(qualifiedName);
        try {
            JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, origin);
            try (Writer writer = sourceFile.openWriter()) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            diagnostics.error(
                    "Failed to write " + qualifiedName + ": " + e.getMessage(), origin);
        }
    }
}
