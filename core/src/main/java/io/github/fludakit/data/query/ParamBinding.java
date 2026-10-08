package io.github.fludakit.data.query;

/** Maps a method-argument slot to the named-parameter token its value binds to. */
public record ParamBinding(int argIndex, String paramName) { }
