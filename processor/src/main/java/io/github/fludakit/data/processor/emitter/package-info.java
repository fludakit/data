/**
 * Method-body emitters: turn resolved contexts into {@code MethodSpec}s for repository members
 * (find, write, lifecycle, hydration). They do not own {@code Filer}.
 *
 * <p>File output is {@code writer}'s responsibility.</p>
 */
package io.github.fludakit.data.processor.emitter;
