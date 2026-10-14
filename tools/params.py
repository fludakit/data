"""Locate Java method/ctor declarations accurately, on RAW text.

A declaration is `name(` whose preceding token is not `.`/`new`/`return`/`=` and whose
matching `)` is followed (modulo `throws …`) by `{` or `;`. Call sites therefore drop out
without needing to strip comments, so line numbers stay true.
"""

import re

NAME = re.compile(r'([A-Za-z_$][A-Za-z0-9_$]*)\s*\(')
PREV_BAD = {'.', 'new', 'return', '=', ',', '(', ':', '?', '>'}


def _skip_literal(text, j):
    """text[j] is a quote char; return the index just past the closing quote."""
    q = text[j]
    j += 1
    while j < len(text):
        if text[j] == '\\':
            j += 2
            continue
        if text[j] == q:
            return j + 1
        j += 1
    return len(text)


def declarations(text):
    """Yield dicts: start, name, plist, plist_start, plist_end, body_start, body_end."""
    out = []
    i, n = 0, len(text)
    while i < n:
        m = NAME.search(text, i)
        if not m:
            break
        open_paren = m.end() - 1
        before = text[:m.start()]
        prev_char = before[-1] if before else ''
        tokens = re.findall(r'[A-Za-z0-9_$]+', before[-40:])
        prev_word = tokens[-1] if tokens else ''
        j = open_paren
        depth = 0
        while j < n:
            c = text[j]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            elif c in '"\'':
                j = _skip_literal(text, j)
                continue
            j += 1
        close_paren = j
        tail = text[close_paren + 1:close_paren + 300]
        tm = re.match(r'\s*(?:throws\s+[\w.,\s<]+?)?\s*([{;])', tail)
        if not tm or prev_char in PREV_BAD or prev_word in ('new', 'return'):
            i = m.end()
            continue
        body_start = body_end = None
        if tm.group(1) == '{':
            brace = close_paren + 1 + tail.index('{', 0)
            depth = 0
            k = brace
            while k < n:
                c = text[k]
                if c == '{':
                    depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        break
                elif c in '"\'':
                    k = _skip_literal(text, k)
                    continue
                elif text.startswith('//', k):
                    nl = text.find('\n', k)
                    k = n if nl < 0 else nl
                    continue
                k += 1
            body_start, body_end = brace, k + 1
        out.append(dict(start=m.start(), name=m.group(1),
                        plist=text[open_paren + 1:close_paren],
                        plist_start=open_paren + 1, plist_end=close_paren,
                        close_paren=close_paren, body_start=body_start, body_end=body_end))
        i = close_paren + 1
    return out


PARAM = re.compile(
    r'^(?:final\s+)?'
    r'(?:[\w.$]+(?:\s*<[^{}<>]*>)?(?:\s*\[\s*\])*'
    r'|[\w.$]+\s*<[^{}<>]*>\s*[\w.$]+(?:\s*<[^{}<>]*>)?(?:\s*\[\s*\])*)'
    r'\s+([a-z_$][A-Za-z0-9_$]*)$')


def parameters(plist):
    """Split a parameter list on top-level commas; return [(text, name_or_None)]."""
    parts, cur, depth = [], '', 0
    for ch in plist:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    parts.append(cur)
    res = []
    for p in parts:
        p = p.strip()
        if not p:
            continue
        m = PARAM.match(p)
        res.append((p, m.group(1) if m else None))
    return res
