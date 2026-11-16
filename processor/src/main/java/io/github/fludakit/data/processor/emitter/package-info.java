/**
 * Method-body emitters: turn resolved contexts into {@code MethodSpec}s for repository members
 * (find, write, lifecycle, hydration). {@link RepositoryMemberEmitter} orchestrates them.
 * They do not own {@code Filer} — file output is {@code writer}'s responsibility.
 */
package io.github.fludakit.data.processor.emitter;
