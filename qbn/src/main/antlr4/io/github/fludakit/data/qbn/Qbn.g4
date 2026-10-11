grammar Qbn;

methodName
    : action first? DISTINCT? (BY conditionList)? (ORDER_BY orderList)? EOF
    ;

action: FIND | DELETE | COUNT | EXISTS;

first: FIRST;

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
