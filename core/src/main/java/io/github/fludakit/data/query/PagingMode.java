package io.github.fludakit.data.query;

/** How a query pages: no paging, classic OFFSET paging, or keyset (cursor) paging. */
public enum PagingMode { NONE, OFFSET, CURSOR }
