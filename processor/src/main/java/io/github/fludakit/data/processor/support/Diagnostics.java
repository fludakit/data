package io.github.fludakit.data.processor.support;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

public final class Diagnostics {
    private final Messager messager;

    public Diagnostics(Messager messager) {
        this.messager = messager;
    }

    public void error(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    public void warn(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    public void note(String message, Element element) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, element);
    }
}
