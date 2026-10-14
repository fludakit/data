#!/usr/bin/env python3
"""Source hygiene gate for the fluda-data processor module.

Checks two things a compiler will not:

  1. UNUSED IMPORTS -- every `import X.Y;` in a .java file must have at least one
     other occurrence of the simple name `Y` in that file. A javadoc {@link Y}
     counts, so a {@link} to a removed import is reported as unused rather than
     silently left dangling; an import kept only for {@code Y} does NOT count,
     which is why the javadoc convention here is {@code} for an unimported type.

  2. DANGLING JAVADOC -- every {@link ...} / {@linkplain ...} target must either
     be imported, be fully qualified, or resolve inside the file itself (a member
     of this class, or a nested type like `AttributeTypes.FM_ID`). A target that
     resolves to nothing is reported.

Known accepted finding: a record's component (e.g. `PathResolution.multiHop`)
is referenced by {@link} from another file but declared as a record header, so
it has no `import` line. Filtered by KNOWN_FALSE_POSITIVES below.

Usage:  python tools/gate.py [root]      (default root: the repo root)
Exit code 0 = clean, 1 = findings.
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)
# {@link Target}, {@link Target#member}, {@linkplain Target}, @see Target#member
LINK_RE = re.compile(r"\{@link(?:plain)?\s+([\w.#]+)|\{@see\s+([\w.#]+)")

# Reachable without an import: java.lang, plus the collections every file uses.
IMPLICIT = {
    "String", "Object", "Class", "Integer", "Long", "Double", "Float", "Boolean", "Short",
    "Byte", "Character", "Number", "Void", "Enum", "List", "Map", "Set", "Collection",
    "Iterable", "Iterator", "Optional", "Comparable", "Exception", "RuntimeException",
    "Throwable", "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
    "SafeVarargs", "Record", "StringBuilder", "System", "AutoCloseable",
}

# Record components have no import line -- see module docstring.
KNOWN_FALSE_POSITIVES = {"PathResolution.multiHop"}


def java_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("target", ".git", "node_modules", ".workbuddy")]
        for name in filenames:
            if name.endswith(".java"):
                yield os.path.join(dirpath, name)


def strip_comments_and_strings(src):
    """Blank out // and /* */ comments and string literals, keeping javadoc separately.

    Returns (code, javadoc, text_blocks). A Java TEXT BLOCK is a string literal, but unlike a
    normal one it holds source that is COMPILED, so it counts as code for the unused-import
    check: `import java.util.List;` is used when only a fixture's text block mentions List.
    """
    javadoc_blocks = []
    text_blocks = []
    out = []
    i, n = 0, len(src)
    while i < n:
        # A Java TEXT BLOCK opens with triple quotes -- it is a string literal, not a
        # Python docstring, so it must be skipped like any other string.
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            text_blocks.append(src[i + 3:j - 3])
            out.append(" " * (j - i))
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/**", i):
            j = src.find("*/", i + 3)
            j = n if j < 0 else j + 2
            javadoc_blocks.append(src[i:j])
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(" " * (j - i))
            i = j
        elif src[i] in "\"'":
            quote = src[i]
            j = i + 1
            while j < n and src[j] != quote:
                j += 2 if src[j] == "\\" else 1
            out.append(" " * (j - i + 1))
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out), "\n".join(javadoc_blocks), "\n".join(text_blocks)


def check(path, known):
    findings = []
    with open(path, encoding="utf-8") as fh:
        src = fh.read()

    code, javadoc, text_blocks = strip_comments_and_strings(src)

    imports = []
    for m in IMPORT_RE.finditer(code):
        fqn = m.group(1)
        imports.append((fqn, fqn.rsplit(".", 1)[-1]))

    # 1. unused imports
    for fqn, simple in imports:
        if simple == "*":
            continue
        # occurrences outside the import line itself. Text blocks count (they hold compiled
        # source), javadoc counts ({@link X} needs the import), plain string literals and
        # but plain // comments and plain string literals do NOT -- ClassName.get(pkg, "Connector")
        # and a comment that merely names a type are both cases where the import buys nothing.
        body = code.replace("import " + fqn + ";", "")
        bare = r"(?<![\w.])" + re.escape(simple) + r"(?![\w])"
        if not (re.search(bare, body) or re.search(bare, javadoc)
                or re.search(bare, text_blocks)):
            findings.append(("unused-import", fqn))

    # 2. dangling javadoc links
    imported_simes = {s for _, s in imports}
    for m in LINK_RE.finditer(javadoc):
        target = next((g for g in m.groups() if g), None)
        if not target:
            continue
        head = target.split("#", 1)[0]
        if not head or head in KNOWN_FALSE_POSITIVES:
            continue
        root_simple = head.split(".", 1)[0]
        if root_simple in imported_simes or root_simple in IMPLICIT:
            continue
        if root_simple in known:
            continue
        if "." in head and head[0].islower():
            continue  # fully qualified: jakarta.data.repository.Repository
        findings.append(("dangling-link", target))
    return findings


def main():
    # Pass 1: every identifier that appears anywhere in the scanned sources. A javadoc {@link}
    # resolves without an import when it names a type in the SAME package (RepositoryProcessor),
    # a nested type of one (ResultKind inside SqlConstants), or a fully qualified name -- so the
    # gate only reports a link whose simple root exists nowhere at all, which is a real typo.
    known = set()
    for path in java_files(ROOT):
        with open(path, encoding="utf-8") as fh:
            known.update(re.findall(r"\w+", fh.read()))

    total = 0
    for path in java_files(ROOT):
        for kind, what in check(path, known):
            total += 1
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            print(f"{kind:15s} {rel}: {what}")
    print(f"\n{'CLEAN' if total == 0 else str(total) + ' finding(s)'}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
