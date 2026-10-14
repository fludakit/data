grammar Qbn;

// Jakarta Data 1.0 addendum, 2.5 "BNF Grammar for Query Methods":
//
//   query       : find | action
//   find        : "find" limit? ignoredText? restriction? order?
//   action      : ("delete" | "count" | "exists") ignoredText? restriction?
//   restriction : "By" predicate
//   limit       : "First" max?
//   predicate   : condition (("And" | "Or") condition)*
//   condition   : property "IgnoreCase"? "Not"? operator?
//   property    : identifier ("_" identifier)*
//   max         : digit+
//   order       : "OrderBy" (property | orderItem+)
//   orderItem   : property ("Asc" | "Desc")
//
// "Distinct" is a DELIBERATE SUPERSET. It appears nowhere in Jakarta Data 1.0 — the addendum's
// subject clause is "limit? ignoredText?", so strictly findDistinctByName is findByName with the
// "Distinct" discarded as ignored text. We keep it as a keyword instead, because dropping it would
// silently degrade findDistinctByName to a plain find: the name would parse, and the rows the author
// asked to de-duplicate would not be. A superset that renders what the name says beats a spec-literal
// parse that discards it. Only find and count give it meaning; the visitor rejects it elsewhere.
//
// Consequence, shared with any keyword: a property literally named "distinct" cannot be parsed
// (findByDistinct lexes "Distinct" as the keyword). The same is true of every operator keyword.
//
// Two deliberate supersets of the BNF, both taken from the spec's prose rather than from its
// grammar, because the grammar contradicts that prose:
//   - orderItem carries "IgnoreCase"?: table 3 says IgnoreCase applies to "query conditions AND
//     ordering", but the BNF's orderItem has no slot for it. Without the slot, OrderByStreetIgnoreCaseAsc
//     parses "IgnoreCase" as a SECOND sort property instead of as a modifier on "street".
//   - the direction stays optional on every order item, not only on a lone property: every
//     implementation accepts a trailing bare property (findByAgeOrderByHeightDescId).

methodName
    : findQuery
    | actionQuery
    ;

/** "find" is the only action carrying a limit and an order — BNF {@code find} vs {@code action}. */
findQuery
    : FIND first? DISTINCT? ignoredText? restriction? order? EOF
    ;

/** delete / count / exists: the BNF's {@code action} has neither a limit nor an order. */
actionQuery
    : action DISTINCT? ignoredText? restriction? EOF
    ;

action: DELETE | COUNT | EXISTS;

first: FIRST;

/**
 * Text the spec discards. It sits between the action (or the limit) and "By", so findAllByName and
 * findDistinctByName both parse as findByName. The BNF definition — "optional text that does not
 * contain By, All, or First" — is what bounds it: "By" ends it, and "First" is already consumed by
 * {@code limit}, so in practice any hump run that is not a keyword lands here and is thrown away.
 */
ignoredText
    : HUMP+
    ;

restriction
    : BY conditionList
    ;

conditionList
    : condition (connector condition)*
    ;

connector: AND | OR;

condition
    : property IGNORE_CASE? NOT? operator?
    ;

operator
    : BETWEEN | CONTAINS | STARTS_WITH | ENDS_WITH
    | LESS_THAN_EQUAL | GREATER_THAN_EQUAL | LESS_THAN | GREATER_THAN
    | LIKE | IN | IS_NULL | IS_TRUE | IS_FALSE
    ;

property
    : HUMP (UNDERSCORE? HUMP)*
    ;

order
    : ORDER_BY orderList
    ;

orderList
    : orderItem+
    ;

orderItem
    : property IGNORE_CASE? (ASC | DESC)?
    ;

// --- lexer ---
FIND: 'find';
DELETE: 'delete';
COUNT: 'count';
EXISTS: 'exists';
BY: 'By';
FIRST: 'First' [0-9]*;
// 'Distinct' must be declared before HUMP: both match the 8-char run, and on a length tie the
// first-declared rule wins.
DISTINCT: 'Distinct';
AND: 'And';
OR: 'Or';
ORDER_BY: 'OrderBy';
IGNORE_CASE: 'IgnoreCase';
NOT: 'Not';
ASC: 'Asc';
DESC: 'Desc';

LESS_THAN_EQUAL: 'LessThanEqual';
GREATER_THAN_EQUAL: 'GreaterThanEqual';
LESS_THAN: 'LessThan';
GREATER_THAN: 'GreaterThan';
BETWEEN: 'Between';
CONTAINS: 'Contains';
STARTS_WITH: 'StartsWith';
ENDS_WITH: 'EndsWith';
LIKE: 'Like';
IN: 'In';
IS_NULL: 'Null';
IS_TRUE: 'True';
IS_FALSE: 'False';

UNDERSCORE: '_';
HUMP: [a-z0-9]+ | [A-Z][a-z0-9]*;
WS: [ \t\r\n]+ -> skip;
