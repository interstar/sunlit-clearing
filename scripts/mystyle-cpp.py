#!/usr/bin/env python3
"""Structural style linter for C++ source trees: long functions, long or
numerous local lambdas, deep nesting, parameter counts, mutable global and
static state, fail-soft fallbacks, raw new/delete, C-style casts, oversized
files and near-duplicate functions.

Parses with tree-sitter-cpp, so it needs no include paths or build flags:
each file is read on its own, syntax only. Output format, shared with the
other mystyle linters:

path:line:col [LEVEL] category: message
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

SHARED_VENV_PYTHON = Path.home() / ".main_python_venv" / "bin" / "python"

try:
    import tree_sitter_cpp
    from tree_sitter import Language, Node, Parser
except ImportError:
    # Run from PATH under a python without the grammars: retry under the shared venv.
    if SHARED_VENV_PYTHON.is_file() and Path(sys.executable) != SHARED_VENV_PYTHON:
        os.execv(str(SHARED_VENV_PYTHON), [str(SHARED_VENV_PYTHON), os.path.abspath(__file__), *sys.argv[1:]])
    sys.exit("mystyle-cpp needs tree-sitter: pip install tree-sitter tree-sitter-cpp")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

EXTENSIONS = (".cpp", ".cc", ".cxx", ".c++", ".h", ".hh", ".hpp", ".hxx", ".inl", ".ipp")

# Matched case-insensitively.
IGNORED_DIRS = {
    ".git", ".hg", ".svn", ".venv", "venv", "__pycache__", "node_modules", ".sunlit",
    ".vscode", ".idea", ".cache", "build", "builds", "out", "bin", "obj", "dist",
    "target", "vendor", "coverage", "cache", "generated", "export", "third_party",
    "thirdparty", "external", "jucelibrarycode",
}
# Matched exactly: a JUCE checkout, without skipping an app's own `juce/` folder.
EXACT_IGNORED_DIRS = {"JUCE"}
IGNORED_DIR_PREFIXES = ("cmake-build-",)

LONG_FN_WARN = 50
LONG_FN_HARD = 100
LOCAL_WARN_LINES = 3
LOCAL_HARD_LINES = 6
MAX_LOCALS = 3
MAX_PARAMETERS = 7
MAX_NESTING_DEPTH = 6
FILE_TOO_LONG = 1000

NEAR_DUP_MIN_TOKENS = 50
NEAR_DUP_SHINGLE = 7
NEAR_DUP_SIMILARITY = 0.8
NEAR_DUP_SIZE_RATIO = 0.75

CPP = Language(tree_sitter_cpp.language())

NESTING_TYPES = frozenset({
    "if_statement", "for_statement", "for_range_loop", "while_statement",
    "do_statement", "switch_statement", "try_statement",
})
CLASS_TYPES = frozenset({"class_specifier", "struct_specifier", "union_specifier"})
SCOPE_TYPES = frozenset({
    "function_definition", "lambda_expression", "field_declaration_list",
    "translation_unit", "namespace_definition",
})
PARAMETER_TYPES = frozenset({
    "parameter_declaration", "optional_parameter_declaration", "variadic_parameter_declaration",
})
DECLARATOR_WRAPPERS = frozenset({
    "init_declarator", "pointer_declarator", "reference_declarator", "array_declarator",
    "parenthesized_declarator",
})
EMPTY_TYPES = frozenset({
    "string", "string_view", "String", "vector", "map", "unordered_map", "set",
    "unordered_set", "list", "deque", "Array", "OwnedArray", "StringArray", "var",
})
TOKEN_LEAVES = {
    "identifier": "ID", "type_identifier": "TYPE", "namespace_identifier": "NS",
    "number_literal": "NUM", "string_literal": "STR", "raw_string_literal": "STR",
    "concatenated_string": "STR", "char_literal": "CHAR",
}

# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    col: int
    level: str
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}:{self.col} [{self.level}] {self.message}"


@dataclass(frozen=True)
class Unit:
    """A function definition, or a lambda bound to a local variable."""

    name: str
    node: Node
    body: Node | None
    outer_key: tuple | None  # enclosing unit, for local lambdas
    outer_name: str | None


@dataclass(frozen=True)
class DupCandidate:
    path: str
    name: str
    line: int
    col: int
    start: int
    end: int
    token_count: int
    shingles: frozenset


# Macros tree-sitter cannot expand, blanked (length-preserving) before parsing.
MACRO_ONLY_LINE = re.compile(rb"^[ \t]*[A-Z][A-Z0-9_]*[A-Z0-9][ \t]*\([^\r\n]*\)[ \t]*;?[ \t]*(?=\r?$)(?![\s]*\{)", re.M)
ATTRIBUTE_MACRO = re.compile(rb"\bJUCE_(?:CALLTYPE|API|PUBLIC_IN_DLL_BUILD|EXPORT)\b|__declspec[ \t]*\([^)\n]*\)")
VALUE_INCLUDE = re.compile(rb"(?<=[=(,])([ \t]*\r?\n[ \t]*)(#[ \t]*include[^\n]*)")


def blank(match: re.Match) -> bytes:
    return b" " * len(match.group(0))


def neutralise_macros(data: bytes) -> bytes:
    """Hide macro-only lines (`JUCE_DECLARE_...(X)`), attribute macros
    (`JUCE_CALLTYPE`, `__declspec(...)`) and `#include`s used as values, keeping
    every byte offset (and so every line/column) unchanged."""
    data = MACRO_ONLY_LINE.sub(blank, data)
    data = ATTRIBUTE_MACRO.sub(blank, data)
    return VALUE_INCLUDE.sub(lambda m: m.group(1) + b"0" + b" " * (len(m.group(2)) - 1), data)


@dataclass(frozen=True)
class SourceFile:
    path: str
    data: bytes
    root: Node

    @staticmethod
    def load(path: Path) -> "SourceFile":
        data = path.read_bytes()
        return SourceFile(str(path), data, Parser(CPP).parse(neutralise_macros(data)).root_node)

    def text(self, node: Node) -> str:
        return node.text.decode("utf-8", errors="replace")

    def snippet(self, node: Node, limit: int = 90) -> str:
        s = " ".join(self.text(node).split())
        return s if len(s) <= limit else s[: limit - 3] + "..."

    def finding(self, node: Node, level: str, message: str) -> Finding:
        return Finding(self.path, node.start_point.row + 1, node.start_point.column + 1, level, message)

    def line_count(self) -> int:
        return self.data.count(b"\n") + (0 if self.data.endswith(b"\n") or not self.data else 1)


# ---------------------------------------------------------------------------
# Tree helpers
# ---------------------------------------------------------------------------


def key(node: Node) -> tuple:
    return (node.start_byte, node.end_byte, node.type)


def descendants(node: Node, named_only: bool = True) -> Iterator[Node]:
    """`node` and everything below it, depth-first, in source order."""
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(reversed(n.named_children if named_only else n.children))


def span(node: Node) -> int:
    return node.end_point.row - node.start_point.row + 1


def unwrap_parens(node: Node) -> Node:
    while node.type == "parenthesized_expression" and node.named_children:
        node = node.named_children[0]
    return node


def inner_declarator(node: Node) -> Node | None:
    inner = node.child_by_field_name("declarator")
    if inner is None and node.named_children:
        inner = node.named_children[-1]
    return inner


def function_declarator(fn: Node) -> Node | None:
    d = fn.child_by_field_name("declarator")
    while d is not None and d.type != "function_declarator":
        d = inner_declarator(d) if d.type in DECLARATOR_WRAPPERS else None
    return d


def scope_of(node: Node) -> str:
    """Type of the nearest enclosing scope (function, class body, namespace, file)."""
    p = node.parent
    while p is not None and p.type not in SCOPE_TYPES:
        p = p.parent
    return p.type if p is not None else "translation_unit"


# ---------------------------------------------------------------------------
# Function units
# ---------------------------------------------------------------------------


def enclosing_class_name(src: SourceFile, node: Node) -> str | None:
    p = node.parent
    while p is not None and p.type not in ("function_definition", "lambda_expression"):
        if p.type in CLASS_TYPES:
            name = p.child_by_field_name("name")
            return src.text(name) if name is not None else None
        p = p.parent
    return None


def function_name(src: SourceFile, fn: Node) -> str:
    fd = function_declarator(fn)
    target = fd.child_by_field_name("declarator") if fd is not None else None
    name = src.text(target) if target is not None else "<anonymous>"
    cls = None if "::" in name else enclosing_class_name(src, fn)
    return f"{cls}::{name}" if cls else name


def bound_lambdas(src: SourceFile, decl: Node) -> list[tuple[str, Node]]:
    """(name, lambda) for each `auto name = [..](..) {..}` in a declaration."""
    found = []
    for init in decl.children_by_field_name("declarator"):
        value = init.child_by_field_name("value") if init.type == "init_declarator" else None
        if value is not None and value.type == "lambda_expression":
            found.append((src.text(init.child_by_field_name("declarator")), value))
    return found


def enclosing_unit(node: Node, names: dict[tuple, str]) -> tuple[tuple, str] | None:
    p = node.parent
    while p is not None:
        if key(p) in names:
            return key(p), names[key(p)]
        p = p.parent
    return None


def collect_units(src: SourceFile) -> list[Unit]:
    nodes = list(descendants(src.root))
    functions = [n for n in nodes if n.type == "function_definition"]
    lambdas = [b for n in nodes if n.type == "declaration" for b in bound_lambdas(src, n)]
    names = {key(f): function_name(src, f) for f in functions}
    names.update({key(lam): name for name, lam in lambdas})
    units = [Unit(names[key(f)], f, f.child_by_field_name("body"), None, None) for f in functions]
    for name, lam in lambdas:
        outer = enclosing_unit(lam, names)
        if outer is not None:  # a lambda at namespace scope is not local
            units.append(Unit(name, lam, lam.child_by_field_name("body"), outer[0], outer[1]))
    return units


# ---------------------------------------------------------------------------
# Per-function checks
# ---------------------------------------------------------------------------


def length_findings(src: SourceFile, unit: Unit) -> list[Finding]:
    lines = span(unit.node)
    end = unit.node.end_point.row + 1
    if unit.outer_key is None and lines > LONG_FN_WARN:
        level = "HARD" if lines > LONG_FN_HARD else "WARN"
        return [src.finding(unit.node, level,
                            f"long-function: `{unit.name}` is {lines} lines (ends at line {end}); consider refactoring.")]
    if unit.outer_key is not None and lines > LOCAL_WARN_LINES:
        level = "HARD" if lines > LOCAL_HARD_LINES else "WARN"
        return [src.finding(unit.node, level,
                            f"local-function: lambda `{unit.name}` inside `{unit.outer_name}` spans {lines} lines "
                            f"(ends at line {end}); consider hoisting it out.")]
    return []


def local_count_findings(src: SourceFile, unit: Unit, units: list[Unit]) -> list[Finding]:
    n = sum(1 for u in units if u.outer_key == key(unit.node))
    if n <= MAX_LOCALS:
        return []
    return [src.finding(unit.node, "WARN",
                        f"local-function: `{unit.name}` defines {n} local lambdas (> {MAX_LOCALS}); consider hoisting some out.")]


def parameter_list(unit: Unit) -> Node | None:
    if unit.node.type == "lambda_expression":
        decl = unit.node.child_by_field_name("declarator")
    else:
        decl = function_declarator(unit.node)
    return decl.child_by_field_name("parameters") if decl is not None else None


def is_override(src: SourceFile, unit: Unit) -> bool:
    """An `override`/`final` method: its signature is fixed by the base class."""
    fd = function_declarator(unit.node) if unit.node.type == "function_definition" else None
    return fd is not None and any(c.type == "virtual_specifier" for c in fd.children)


def parameter_findings(src: SourceFile, unit: Unit) -> list[Finding]:
    if is_override(src, unit):
        return []
    plist = parameter_list(unit)
    params = [p for p in plist.named_children if p.type in PARAMETER_TYPES] if plist is not None else []
    if len(params) == 1 and src.text(params[0]).strip() == "void":
        params = []
    if len(params) <= MAX_PARAMETERS:
        return []
    return [src.finding(unit.node, "WARN",
                        f"parameter-count: `{unit.name}` has {len(params)} parameters (> {MAX_PARAMETERS})")]


def nested_children(node: Node, depth: int, own: int) -> list[tuple[Node, int]]:
    """Children with the depth they sit at; an `else if` continues its chain."""
    if node.type != "if_statement":
        return [(c, own) for c in node.named_children]
    condition = node.child_by_field_name("condition")
    found = []
    for c in node.named_children:
        if c.type == "else_clause" and c.named_children and c.named_children[0].type == "if_statement":
            found.append((c.named_children[0], depth))
        else:
            found.append((c, depth if condition is not None and key(c) == key(condition) else own))
    return found


def deepest(node: Node, depth: int, unit_keys: set[tuple]) -> tuple[int, Node]:
    """Depth and node of the most deeply nested control flow, skipping other units."""
    own = depth + 1 if node.type in NESTING_TYPES else depth
    best = (own, node)
    for child, child_depth in nested_children(node, depth, own):
        if key(child) not in unit_keys:
            found = deepest(child, child_depth, unit_keys)
            if found[0] > best[0]:
                best = found
    return best


def nesting_findings(src: SourceFile, unit: Unit, unit_keys: set[tuple]) -> list[Finding]:
    if unit.body is None:
        return []
    depth, node = deepest(unit.body, 0, unit_keys)
    if depth <= MAX_NESTING_DEPTH:
        return []
    return [src.finding(node, "WARN", f"nesting-depth: `{unit.name}` reaches depth {depth} (> {MAX_NESTING_DEPTH})")]


def unit_findings(src: SourceFile, units: list[Unit]) -> list[Finding]:
    unit_keys = {key(u.node) for u in units}
    found = []
    for u in units:
        found += length_findings(src, u)
        found += local_count_findings(src, u, units)
        found += parameter_findings(src, u)
        found += nesting_findings(src, u, unit_keys)
    return found


# ---------------------------------------------------------------------------
# Global and static state
# ---------------------------------------------------------------------------


def is_const(src: SourceFile, decl: Node) -> bool:
    return any(c.type == "type_qualifier" and src.text(c) in ("const", "constexpr") for c in decl.children)


def storage(src: SourceFile, decl: Node) -> set[str]:
    return {src.text(c) for c in decl.children if c.type == "storage_class_specifier"}


def variable_names(src: SourceFile, decl: Node) -> list[str]:
    """Plainly-named variables a declaration defines (not functions, not `Foo::x`)."""
    names = []
    for d in decl.children_by_field_name("declarator"):
        while d is not None and d.type in DECLARATOR_WRAPPERS:
            d = inner_declarator(d)
        if d is not None and d.type in ("identifier", "field_identifier"):
            names.append(src.text(d))
    return names


def global_state(src: SourceFile) -> tuple[list[tuple[Node, str]], list[tuple[Node, str]]]:
    """(mutable globals and static members, function-local statics)."""
    shared, local = [], []
    for node in descendants(src.root):
        if node.type not in ("declaration", "field_declaration") or is_const(src, node):
            continue
        st, scope = storage(src, node), scope_of(node)
        names = variable_names(src, node)
        if node.type == "field_declaration" and "static" in st:
            cls = enclosing_class_name(src, node)
            shared += [(node, f"{cls}::{n}" if cls else n) for n in names]
        elif node.type == "declaration" and "extern" not in st and scope in ("translation_unit", "namespace_definition"):
            shared += [(node, n) for n in names]
        elif node.type == "declaration" and "static" in st and scope in ("function_definition", "lambda_expression"):
            local += [(node, n) for n in names]
    return shared, local


def global_findings(src: SourceFile) -> list[Finding]:
    shared, local = global_state(src)
    advice = ("a single global can be OK, but prefer const/constexpr values or passing state explicitly."
              if len(shared) == 1 else
              "multiple mutable globals found; prefer at most one, or const/constexpr values and explicit state.")
    found = [src.finding(n, "WARN", f"global-usage: `{name}` is mutable static state; {advice}") for n, name in shared]
    found += [src.finding(n, "WARN",
                          f"global-usage: function-local static `{name}` is hidden mutable global state; "
                          "prefer a member or passing state explicitly.") for n, name in local]
    return found


# ---------------------------------------------------------------------------
# Fail-soft fallbacks
# ---------------------------------------------------------------------------


def base_type_name(src: SourceFile, node: Node) -> str:
    """`std::vector<int>` -> `vector`."""
    return src.text(node).split("<", 1)[0].split("::")[-1].strip()


def is_empty(src: SourceFile, node: Node) -> bool:
    """`""`, `{}`, or an empty string/container such as `std::string()` or `juce::String{}`."""
    node = unwrap_parens(node)
    if node.type == "string_literal":
        return all(not src.text(c).strip() for c in node.named_children)
    if node.type == "initializer_list":
        return not node.named_children
    if node.type == "call_expression":
        args = node.child_by_field_name("arguments")
        return (args is not None and not args.named_children
                and base_type_name(src, node.child_by_field_name("function")) in EMPTY_TYPES)
    if node.type == "compound_literal_expression":
        value, kind = node.child_by_field_name("value"), node.child_by_field_name("type")
        return (value is not None and kind is not None and not value.named_children
                and base_type_name(src, kind) in EMPTY_TYPES)
    return False


def null_test(src: SourceFile, cond: Node | None) -> str:
    """"==" or "!=" when `cond` compares something with nullptr/NULL, else ""."""
    cond = unwrap_parens(cond) if cond is not None else None
    if cond is None or cond.type != "binary_expression":
        return ""
    op = cond.child_by_field_name("operator")
    sides = [cond.child_by_field_name("left"), cond.child_by_field_name("right")]
    is_null = any(s is not None and (s.type == "null" or src.text(s) in ("nullptr", "NULL")) for s in sides)
    return op.type if is_null and op is not None and op.type in ("==", "!=") else ""


def fallback_of(src: SourceFile, node: Node) -> Node | None:
    """The empty default `node` falls back to, or None when it is not a fallback."""
    if node.type == "conditional_expression":
        branch = {"!=": "alternative", "==": "consequence"}.get(null_test(src, node.child_by_field_name("condition")))
        candidate = node.child_by_field_name(branch) if branch else None
        return candidate if candidate is not None and is_empty(src, candidate) else None
    if node.type == "call_expression":
        fn = node.child_by_field_name("function")
        field = fn.child_by_field_name("field") if fn is not None and fn.type == "field_expression" else None
        args = node.child_by_field_name("arguments")
        if field is not None and src.text(field) == "value_or" and args is not None and len(args.named_children) == 1:
            return args.named_children[0] if is_empty(src, args.named_children[0]) else None
    return None


def fail_soft_findings(src: SourceFile) -> list[Finding]:
    found = []
    for node in descendants(src.root):
        fallback = fallback_of(src, node)
        if fallback is not None:
            found.append(src.finding(node, "WARN",
                                     f"fail-soft-input: `{src.snippet(node)}` silently falls back to "
                                     f"{src.snippet(fallback)}; validate the input and fail clearly instead"))
    return found


# ---------------------------------------------------------------------------
# Risky constructs, file size, parse errors
# ---------------------------------------------------------------------------

RISKY = {
    "new_expression": "raw-new: `{}`; prefer std::make_unique/std::make_shared or an owning container.",
    "delete_expression": "raw-delete: `{}`; prefer RAII ownership (smart pointers, owning containers).",
    "cast_expression": "c-style-cast: `{}`; prefer static_cast (or const_cast/reinterpret_cast where meant).",
}


def is_void_cast(src: SourceFile, node: Node) -> bool:
    """`(void)x`: the idiom for marking a value as deliberately unused."""
    kind = node.child_by_field_name("type")
    return node.type == "cast_expression" and kind is not None and src.text(kind).strip() == "void"


def risky_findings(src: SourceFile) -> list[Finding]:
    return [src.finding(n, "WARN", RISKY[n.type].format(src.snippet(n)))
            for n in descendants(src.root) if n.type in RISKY and not is_void_cast(src, n)]


def file_length_findings(src: SourceFile) -> list[Finding]:
    n = src.line_count()
    if n <= FILE_TOO_LONG:
        return []
    return [Finding(src.path, 1, 1, "WARN", f"file-too-long: file has {n} lines (> {FILE_TOO_LONG}); consider splitting it.")]


def parse_error_findings(src: SourceFile) -> list[Finding]:
    if not src.root.has_error:
        return []
    bad = [n for n in descendants(src.root, named_only=False) if n.type == "ERROR" or n.is_missing]
    if not bad:
        return []
    return [src.finding(bad[0], "INFO",
                        f"parse-error: syntax not understood here ({len(bad)} place(s) in this file), usually a "
                        "macro or preprocessor split; checks near it may be incomplete.")]


# ---------------------------------------------------------------------------
# Near-duplicates
# ---------------------------------------------------------------------------


def callee_name(src: SourceFile, call: Node) -> str:
    fn = call.child_by_field_name("function")
    if fn is None:
        return "?"
    if fn.type == "field_expression":
        field = fn.child_by_field_name("field")
        return src.text(field) if field is not None else "?"
    return base_type_name(src, fn) if fn.type in ("identifier", "qualified_identifier", "template_function") else "?"


def tokens_of(src: SourceFile, node: Node) -> list[str]:
    """Shape tokens: names and literals collapsed, called and member names kept."""
    tokens = []
    stack = [node]
    while stack:
        n = stack.pop()
        if n.type == "comment":
            continue
        if n.type in TOKEN_LEAVES:
            tokens.append(TOKEN_LEAVES[n.type])
            continue
        if n.type == "field_identifier":
            tokens.append("F:" + src.text(n))
            continue
        tokens.append("CALL:" + callee_name(src, n) if n.type == "call_expression" else n.type)
        stack.extend(reversed(n.children))
    return tokens


def dup_candidate(src: SourceFile, unit: Unit) -> DupCandidate | None:
    if unit.body is None:
        return None
    tokens = tokens_of(src, unit.body)
    if len(tokens) < NEAR_DUP_MIN_TOKENS:
        return None
    shingles = frozenset(hash(tuple(tokens[i:i + NEAR_DUP_SHINGLE]))
                         for i in range(len(tokens) - NEAR_DUP_SHINGLE + 1))
    return DupCandidate(src.path, unit.name, unit.node.start_point.row + 1, unit.node.start_point.column + 1,
                        unit.node.start_byte, unit.node.end_byte, len(tokens), shingles)


def comparable(a: DupCandidate, b: DupCandidate) -> bool:
    size_ok = min(a.token_count, b.token_count) / max(a.token_count, b.token_count) >= NEAR_DUP_SIZE_RATIO
    nested = a.path == b.path and ((a.start <= b.start and b.end <= a.end) or (b.start <= a.start and a.end <= b.end))
    return size_ok and not nested


def similar_pairs(cands: list[DupCandidate]) -> Iterator[tuple[int, int, float]]:
    for i, a in enumerate(cands):
        for j in range(i + 1, len(cands)):
            b = cands[j]
            if comparable(a, b):
                shared = len(a.shingles & b.shingles)
                union = len(a.shingles) + len(b.shingles) - shared
                sim = shared / union if union else 0.0
                if sim >= NEAR_DUP_SIMILARITY:
                    yield i, j, sim


def root_of(parent: list[int], i: int) -> int:
    while parent[i] != i:
        i = parent[i]
    return i


def near_duplicate_findings(cands: list[DupCandidate]) -> list[Finding]:
    """One finding per group of similar functions, at its first member."""
    parent = list(range(len(cands)))
    best = [0.0] * len(cands)  # highest similarity seen in each group, kept at its root
    for i, j, sim in similar_pairs(cands):
        ri, rj = root_of(parent, i), root_of(parent, j)
        lo, hi = min(ri, rj), max(ri, rj)
        best[lo] = max(sim, best[ri], best[rj])
        parent[hi] = lo
    groups: dict[int, list[DupCandidate]] = {}
    for i, c in enumerate(cands):
        groups.setdefault(root_of(parent, i), []).append(c)
    return [group_finding(members, best[r]) for r, members in groups.items() if len(members) > 1]


def group_finding(members: list[DupCandidate], similarity: float) -> Finding:
    first = members[0]
    peers = ", ".join(f"{m.path}:{m.line} (`{m.name}`)" for m in members[1:4])
    more = f" (+{len(members) - 4} more)" if len(members) > 4 else ""
    return Finding(first.path, first.line, first.col, "WARN",
                   f"near-duplicate: `{first.name}` matches {peers}{more}; up to {similarity:.2f} similarity.")


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


def skip_dir(name: str) -> bool:
    low = name.lower()
    return low in IGNORED_DIRS or name in EXACT_IGNORED_DIRS or low.startswith(IGNORED_DIR_PREFIXES)


EXCLUDE_HELP = ("Directory to skip (repeatable). A bare name (`.cache`) is skipped at any depth; "
                "a path (`apps/x/.cache`) is relative to the target directory.")


def normalize_excludes(raw: Iterable[str]) -> tuple[str, ...]:
    return tuple(e.strip().removeprefix("./").strip("/") for e in raw if e.strip().strip("/."))


def is_excluded(rel: str, excludes: tuple[str, ...]) -> bool:
    """Whether directory `rel` (posix, relative to the target) matches an --exclude."""
    name = rel.rsplit("/", 1)[-1]
    return any(name == e if "/" not in e else rel == e or rel.startswith(e + "/") for e in excludes)


def iter_files(target: Path, excludes: tuple[str, ...] = ()) -> Iterable[Path]:
    if target.is_file():
        if target.name.endswith(EXTENSIONS):
            yield target
        return
    for root, dirs, files in os.walk(target):
        here = Path(root).relative_to(target).as_posix()
        dirs[:] = sorted(d for d in dirs if not skip_dir(d)
                         and not is_excluded(d if here == "." else f"{here}/{d}", excludes))
        for name in sorted(files):
            if name.endswith(EXTENSIONS):
                yield Path(root) / name


def analyse_file(path: Path) -> tuple[list[Finding], list[DupCandidate]]:
    src = SourceFile.load(path)
    units = collect_units(src)
    findings = [*file_length_findings(src), *parse_error_findings(src), *global_findings(src),
                *fail_soft_findings(src), *risky_findings(src), *unit_findings(src, units)]
    candidates = [c for c in (dup_candidate(src, u) for u in units) if c is not None]
    return findings, candidates


def lint_path(target: Path, excludes: tuple[str, ...] = ()) -> list[Finding]:
    findings: list[Finding] = []
    candidates: list[DupCandidate] = []
    for path in iter_files(target, excludes):
        file_findings, file_candidates = analyse_file(path)
        findings += file_findings
        candidates += file_candidates
    findings += near_duplicate_findings(candidates)
    return sorted(findings, key=lambda f: (f.path, f.line, f.col, f.level, f.message))


def main() -> int:
    parser = argparse.ArgumentParser(description="Lint C++ code for structural style risks.")
    parser.add_argument("path", nargs="?", default=".", help="Target file or directory")
    parser.add_argument("--exclude", action="append", default=[], metavar="DIR", help=EXCLUDE_HELP)
    args = parser.parse_args()
    target = Path(args.path)
    if not target.exists():
        print(f"{target}:1:1 [WARN] target-path-not-found")
        return 2
    sys.setrecursionlimit(10000)
    for finding in lint_path(target, normalize_excludes(args.exclude)):
        print(finding.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
