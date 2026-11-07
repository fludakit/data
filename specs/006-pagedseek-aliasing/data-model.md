# Data Model: SqlBuilder pagedSeekStatement Aliasing

No domain-model change. The `pagedSeekStatement` signature swaps a pre-rendered `orderBy` string for a
`List<String> orderByTerms` parallel to the existing `terms`/`ascending` lists; two private static helpers
(`renderOrderBy`, `repoint`) are added to `SqlBuilder`.
