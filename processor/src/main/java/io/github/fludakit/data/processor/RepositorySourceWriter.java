package io.github.fludakit.data.processor;

import com.squareup.javapoet.JavaFile;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/** Writes generated repository sources and records every emitted type name for collision checks. */
final class RepositorySourceWriter {

    private final Filer filer;
    private final Messager messager;
    private final Set<String> generatedTypeNames;

    RepositorySourceWriter(Filer filer, Messager messager, Set<String> generatedTypeNames) {
        this.filer = filer;
        this.messager = messager;
        this.generatedTypeNames = generatedTypeNames;
    }

    void write(String qualifiedName, Element origin, JavaFile javaFile) {
        generatedTypeNames.add(qualifiedName);
        try {
            JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, origin);
            try (Writer writer = sourceFile.openWriter()) {
                javaFile.writeTo(writer);
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write " + qualifiedName + ": " + e.getMessage(), origin);
        }
    }
}
