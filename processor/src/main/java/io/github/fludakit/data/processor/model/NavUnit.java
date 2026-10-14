package io.github.fludakit.data.processor.model;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;

/**
 * One generated nav class, complete enough to be RETURNED rather than pushed into a caller's list:
 * the name it is written under, the {@link ClassName} a metamodel member points at, and the file.
 *
 * <p>Carrying {@code type} is what lets a nav generator be a query — it used to take the owner's
 * {@code List<NavUnit>} as an OUT parameter and mutate it while also returning the {@code ClassName},
 * so the caller could not tell which of the two was the result. Now the generator returns this and
 * the owner decides where to collect it, which is the only place the collection is reasoned about.</p>
 */
public record NavUnit(String fqn, ClassName type, JavaFile file) { }
