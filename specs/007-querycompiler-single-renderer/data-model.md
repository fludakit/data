# Data Model: QueryCompiler Single Renderer

No domain-model change. `SqlFragment` gains a nullable `tableAlias` field and a `table(String, String)`
overload; `QueryCompiler.Conjunct` becomes the single conjunct shape shared with `SqlFragment`.
