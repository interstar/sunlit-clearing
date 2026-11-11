#!/usr/bin/env python3
"""Lightweight structural linter for JavaScript/TypeScript source trees.

Uses tree-sitter grammars for JS/JSX (tree-sitter-javascript) and
TS/TSX (tree-sitter-typescript). Output format:

path:line:col [LEVEL] message
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from tree_sitter import Language, Node, Parser
import tree_sitter_javascript
import tree_sitter_typescript

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

IGNORED_DIRS = {
    ".git",
    ".hg",
    ".svn",
    ".venv",
    "venv",
    "__pycache__",
    "node_modules",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".next",
    "build",
    "dist",
    "coverage",
    ".nyc_output",
    "target",
    "resources",
    ".sunlit",
    "out",
    "compiled",
    "vendor",
    "htmlcov",
}

LONG_FN_WARN = 100
LONG_FN_HARD = 200
FILE_TOO_LONG = 1000
MAX_NESTING_DEPTH = 6
MAX_PARAMETERS = 7
MIN_SCOPE_WORDS = 6
MIN_TYPED_FUNCTION_PERCENT = 10.0
ANY_WARN_THRESHOLD = 5  # warn when > N `any` usages in a file

# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    col: int
    level: str
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}:{self.col} [{self.level}] {self.message}"


@dataclass(frozen=True)
class FunctionInfo:
    path: Path
    name: str
    line: int
    col: int
    normalized_hash: str


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Lint JS/TS code for style risks.")
    parser.add_argument("path", nargs="?", default=".", help="Target file or directory")
    parser.add_argument(
        "--extensions",
        default=None,
        help="Comma-separated file extensions to lint (default: .js,.jsx,.mjs,.cjs,.ts,.tsx,.mts,.cts)",
    )
    parser.add_argument("--exclude", action="append", default=[], metavar="DIR", help=EXCLUDE_HELP)
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Dialect
# ---------------------------------------------------------------------------


_JS_LANG = Language(tree_sitter_javascript.language())
_TS_LANG = Language(tree_sitter_typescript.language_typescript())
_TSX_LANG = Language(tree_sitter_typescript.language_tsx())

JS_EXTENSIONS = (".js", ".jsx", ".mjs", ".cjs")
TS_EXTENSIONS = (".ts", ".tsx", ".mts", ".cts")
ALL_EXTENSIONS = JS_EXTENSIONS + TS_EXTENSIONS


def language_for_extension(ext: str) -> Language:
    if ext in (".tsx",):
        return _TSX_LANG
    if ext in TS_EXTENSIONS:
        return _TS_LANG
    return _JS_LANG


def is_ts_extension(ext: str) -> bool:
    return ext in TS_EXTENSIONS


# ---------------------------------------------------------------------------
# File discovery
# ---------------------------------------------------------------------------


EXCLUDE_HELP = ("Directory to skip (repeatable). A bare name (`.cache`) is skipped at any depth; "
                "a path (`apps/x/.cache`) is relative to the target directory.")


def normalize_excludes(raw: Iterable[str]) -> tuple[str, ...]:
    return tuple(e.strip().removeprefix("./").strip("/") for e in raw if e.strip().strip("/."))


def is_excluded(rel: str, excludes: tuple[str, ...]) -> bool:
    """Whether directory `rel` (posix, relative to the target) matches an --exclude."""
    name = rel.rsplit("/", 1)[-1]
    return any(name == e if "/" not in e else rel == e or rel.startswith(e + "/") for e in excludes)


def iter_files(target: Path, extensions: tuple[str, ...], excludes: tuple[str, ...] = ()) -> Iterable[Path]:
    if target.is_file() and any(target.name.endswith(e) for e in extensions):
        yield target
        return

    for root, dirs, files in os.walk(target):
        here = Path(root).relative_to(target).as_posix()
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS
                   and not is_excluded(d if here == "." else f"{here}/{d}", excludes)]
        for name in files:
            if any(name.endswith(e) for e in extensions):
                yield Path(root) / name


# ---------------------------------------------------------------------------
# Tree walking helpers
# ---------------------------------------------------------------------------


def walk_named(node: Node) -> Iterable[Node]:
    """Recursively yield all named descendant nodes (depth-first)."""
    for child in node.named_children:
        yield child
        yield from walk_named(child)


def parent_chain(node: Node) -> list[Node]:
    """Return list of ancestors from root to node."""
    chain = []
    current: Node | None = node
    while current is not None:
        chain.append(current)
        current = current.parent
    chain.reverse()
    return chain


_SUITE_NODE_TYPES = frozenset(
    {
        "function_declaration",
        "generator_function_declaration",
        "arrow_function",
        "method_definition",
        "class_declaration",
        "if_statement",
        "switch_statement",
        "switch_case",
        "for_statement",
        "for_in_statement",
        "while_statement",
        "do_statement",
        "try_statement",
        "with_statement",
    }
)

_ARROW_FN_TYPES = frozenset({"arrow_function"})
_FN_TYPES = frozenset(
    {
        "function_declaration",
        "generator_function_declaration",
        "method_definition",
    }
)
_ALL_FN_TYPES = _FN_TYPES | _ARROW_FN_TYPES


def is_function_node(node: Node) -> bool:
    return node.type in _ALL_FN_TYPES


def is_suite_node(node: Node) -> bool:
    return node.type in _SUITE_NODE_TYPES


def node_line_col(node: Node) -> tuple[int, int]:
    return node.start_point[0] + 1, node.start_point[1] + 1


def node_end_line(node: Node) -> int:
    return node.end_point[0] + 1


def node_span(node: Node) -> int:
    return max(1, node_end_line(node) - node_line_col(node)[0] + 1)


def function_name(node: Node) -> str:
    """Extract function name from a function node."""
    for child in node.named_children:
        if child.type == "identifier":
            return child.text.decode("utf-8", errors="replace")
    return "<anonymous>"


# ---------------------------------------------------------------------------
# Parameter helpers (grammar-aware)
# ---------------------------------------------------------------------------


def _unwrap_param(param: Node) -> Node:
    """Unwrap TS required_parameter / optional_parameter to the inner pattern."""
    if param.type in ("required_parameter", "optional_parameter"):
        for child in param.named_children:
            if child.type not in ("type_annotation",):
                return child
    return param


def count_parameters(func_node: Node) -> int:
    """Count parameters, handling TS wrapping and destructuring."""
    params: list[Node] = []
    for child in func_node.named_children:
        if child.type == "formal_parameters":
            params = [_unwrap_param(p) for p in child.named_children]
            break

    count = 0
    for param in params:
        ptype = param.type
        if ptype == "identifier":
            count += 1
        elif ptype == "assignment_pattern":
            # destructured with default: count the LHS bindings
            count += _count_bindings(param.named_children[0]) if param.named_children else 1
        elif ptype == "object_pattern":
            count += _count_bindings(param)
        elif ptype == "array_pattern":
            count += _count_bindings(param)
        elif ptype in ("rest_pattern", "spread_element"):
            pass  # rest doesn't add a distinct "parameter" in the traditional sense
        else:
            count += 1
    return count


def _count_bindings(pattern: Node) -> int:
    """Count how many variable bindings a destructuring pattern creates."""
    count = 0
    for child in pattern.named_children:
        ctype = child.type
        if ctype in ("identifier", "shorthand_property_identifier", "shorthand_property_identifier_pattern"):
            count += 1
        elif ctype == "pair":
            count += _count_bindings(child)
        elif ctype == "object_pattern":
            count += _count_bindings(child)
        elif ctype == "array_pattern":
            count += _count_bindings(child)
        elif ctype == "rest_pattern":
            count += _count_bindings(child)
        elif ctype == "assignment_pattern":
            count += _count_bindings(child)
    return max(count, 1)  # at least 1 binding


# ---------------------------------------------------------------------------
# Checks: long-function
# ---------------------------------------------------------------------------


def long_function_findings(tree: Node, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if not is_function_node(node):
            continue
        span = node_span(node)
        name = function_name(node)
        line, col = node_line_col(node)
        if span > LONG_FN_HARD:
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="HARD",
                    message=f"long-function: {name} spans {span} lines (> {LONG_FN_HARD})",
                )
            )
        elif span > LONG_FN_WARN:
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message=f"long-function: {name} spans {span} lines (> {LONG_FN_WARN})",
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Checks: nesting-depth
# ---------------------------------------------------------------------------


def nesting_depth_findings(tree: Node, path: Path) -> list[Finding]:
    findings: list[Finding] = []

    def walk(node: Node, depth: int) -> None:
        for child in node.named_children:
            child_depth = depth + 1 if is_suite_node(child) else depth
            if is_suite_node(child) and child_depth > MAX_NESTING_DEPTH:
                line, col = node_line_col(child)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message=f"nesting-depth: depth {child_depth} (> {MAX_NESTING_DEPTH})",
                    )
                )
            walk(child, child_depth)

    walk(tree.root_node, 0)
    return findings


# ---------------------------------------------------------------------------
# Checks: parameter-count
# ---------------------------------------------------------------------------


def parameter_count_findings(tree: Node, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if node.type not in _ALL_FN_TYPES:
            continue
        count = count_parameters(node)
        if count > MAX_PARAMETERS:
            name = function_name(node)
            line, col = node_line_col(node)
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message=f"parameter-count: {name} has {count} parameters (> {MAX_PARAMETERS})",
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Checks: var-usage
# ---------------------------------------------------------------------------


def var_usage_findings(tree: Node, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if node.type != "variable_declaration":
            continue
        line, col = node_line_col(node)
        findings.append(
            Finding(
                path=path,
                line=line,
                col=col,
                level="WARN",
                message="var-usage: prefer const/let over var",
            )
        )
    return findings


# ---------------------------------------------------------------------------
# Checks: eqeqeq
# ---------------------------------------------------------------------------


def _op_text(node: Node, source: bytes) -> str | None:
    """Get the operator text from a binary_expression's anonymous children."""
    for child in node.children:
        if not child.is_named and child.type in ("==", "!=", "===", "!=="):
            return child.type
    return None


def eqeqeq_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if node.type != "binary_expression":
            continue
        op = _op_text(node, source)
        if op in ("==", "!="):
            line, col = node_line_col(node)
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message="eqeqeq: use === or !== instead of == or !=",
                )
            )
    return findings


# ---------------------------------------------------------------------------
# Checks: console-log
# ---------------------------------------------------------------------------


_CONSOLE_METHODS = frozenset({"log", "debug", "warn", "info", "error"})


def console_log_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if node.type != "call_expression":
            continue
        # Look for member_expression where object is console and property is log/debug...
        for child in node.named_children:
            if child.type == "member_expression":
                parts = list(child.named_children)
                if len(parts) >= 2:
                    obj = parts[0]
                    prop = parts[-1]
                    if (
                        obj.type == "identifier"
                        and obj.text == b"console"
                        and prop.type == "property_identifier"
                        and prop.text.decode("utf-8", errors="replace") in _CONSOLE_METHODS
                    ):
                        line, col = node_line_col(node)
                        method = prop.text.decode("utf-8", errors="replace")
                        findings.append(
                            Finding(
                                path=path,
                                line=line,
                                col=col,
                                level="WARN",
                                message=f"console-log: console.{method}() call may be leftover debugging",
                            )
                        )
                    break  # only check first member_expression child
    return findings


# ---------------------------------------------------------------------------
# Checks: module-mutable-state
# ---------------------------------------------------------------------------


_MUTABLE_INIT_TYPES = frozenset({"array", "object"})


def module_mutable_state_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Flag mutable module-level exports (export let, export const with array/object)."""
    findings: list[Finding] = []
    for node in walk_named(tree.root_node):
        if node.type != "export_statement":
            continue
        # Export must be module-level (direct child of program)
        if node.parent is None or node.parent.type != "program":
            continue

        for child in node.named_children:
            # export var x = ...
            if child.type == "variable_declaration":
                line, col = node_line_col(node)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message="module-mutable-state: export var creates mutable module-level binding",
                    )
                )
            # export let x = ...
            elif _is_let_export(child):
                line, col = node_line_col(node)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message="module-mutable-state: export let creates mutable module-level binding",
                    )
                )
            # export const x = [] / export const x = {}
            elif child.type == "lexical_declaration":
                for decl in child.named_children:
                    if decl.type == "variable_declarator":
                        for init in decl.named_children:
                            if init.type in _MUTABLE_INIT_TYPES and not _is_empty_literal(init):
                                pass  # fall through to check
                            elif init.type in _MUTABLE_INIT_TYPES:
                                line, col = node_line_col(node)
                                ident = _declarator_name(decl)
                                findings.append(
                                    Finding(
                                        path=path,
                                        line=line,
                                        col=col,
                                        level="WARN",
                                        message=f"module-mutable-state: export const {ident} = ... creates shared mutable reference",
                                    )
                                )
    return findings


def _is_let_export(node: Node) -> bool:
    """Check if a lexical_declaration uses 'let' keyword."""
    if node.type != "lexical_declaration":
        return False
    for child in node.children:
        if not child.is_named and child.type == "let":
            return True
    return False


def _is_empty_literal(node: Node) -> bool:
    """Check if array/object is an empty literal (x = [], x = {})."""
    return len(list(node.named_children)) == 0


def _declarator_name(node: Node) -> str:
    for child in node.named_children:
        if child.type == "identifier":
            return child.text.decode("utf-8", errors="replace")
    return "?"


# ---------------------------------------------------------------------------
# Checks: fail-soft-input
# ---------------------------------------------------------------------------


def fail_soft_input_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Detect JS fail-soft patterns: ?? fallback, || fallback, try/catch swallowing."""
    findings: list[Finding] = []

    for node in walk_named(tree.root_node):
        if node.type == "binary_expression":
            # Check for ?? operator (nullish coalescing fallback)
            for child in node.children:
                if not child.is_named and child.type == "??":
                    line, col = node_line_col(node)
                    findings.append(
                        Finding(
                            path=path,
                            line=line,
                            col=col,
                            level="WARN",
                            message="fail-soft-input: nullish coalescing (??) fallback may mask missing input; prefer explicit validation",
                        )
                    )
                    break
            # Check for || where LHS is a lookup
            for child in node.children:
                if not child.is_named and child.type == "||":
                    lhs = node.named_children[0] if node.named_children else None
                    if lhs and lhs.type in (
                        "member_expression",
                        "subscript_expression",
                        "call_expression",
                    ):
                        line, col = node_line_col(node)
                        findings.append(
                            Finding(
                                path=path,
                                line=line,
                                col=col,
                                level="WARN",
                                message="fail-soft-input: || fallback around lookup may mask missing input; prefer explicit validation",
                            )
                        )
                    break

        # try { parse/transform } catch { return default }
        if node.type == "try_statement":
            catch_clause = None
            for child in node.named_children:
                if child.type == "catch_clause":
                    catch_clause = child
                    break
            if catch_clause is not None and _catch_swallows_error(catch_clause):
                line, col = node_line_col(node)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message="fail-soft-input: try/catch silently swallows errors with a default; prefer explicit validation before the risky operation",
                    )
                )
    return findings


def _catch_swallows_error(catch_clause: Node) -> bool:
    """Check if a catch clause silently returns a default value."""
    for child in catch_clause.named_children:
        if child.type == "statement_block":
            statements = list(child.named_children)
            if not statements:
                return True  # empty catch block
            if len(statements) == 1 and statements[0].type == "return_statement":
                return True  # single return, likely a default
            if len(statements) == 1 and statements[0].type == "expression_statement":
                # assignment of default
                return True
    return False


# ---------------------------------------------------------------------------
# Checks: context-manager-candidate
# ---------------------------------------------------------------------------


def context_manager_candidate_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Detect try blocks missing finally cleanup or nested error handling."""
    findings: list[Finding] = []

    for node in walk_named(tree.root_node):
        if node.type != "try_statement":
            continue

        has_finally = any(c.type == "finally_clause" for c in node.named_children)
        nested_depth = _nested_try_depth(node)

        issues: list[str] = []
        if nested_depth >= 2:
            issues.append(f"{nested_depth} nested try wrappers")
        if not has_finally:
            # Check if try body has resource-like patterns
            if _has_resource_pattern(node, source):
                issues.append("try body uses resources without finally cleanup")

        if issues:
            line, col = node_line_col(node)
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message="context-manager-candidate: " + "; ".join(issues) + "; consider using a cleanup pattern or 'using' block",
                )
            )
    return findings


_RESOURCE_METHODS = frozenset(
    {
        "close",
        "destroy",
        "end",
        "disconnect",
        "release",
        "unsubscribe",
        "removeListener",
        "off",
        "abort",
    }
)


def _has_resource_pattern(try_node: Node, source: bytes) -> bool:
    """Check if a try block body contains resource-like method calls."""
    body = None
    for child in try_node.named_children:
        if child.type == "statement_block":
            body = child
            break
    if body is None:
        return False

    for node in walk_named(body):
        if node.type == "call_expression":
            for child in node.named_children:
                if child.type == "member_expression":
                    parts = list(child.named_children)
                    if len(parts) >= 2 and parts[-1].type == "property_identifier":
                        method = parts[-1].text.decode("utf-8", errors="replace")
                        if method in _RESOURCE_METHODS:
                            return True
    return False


def _nested_try_depth(node: Node) -> int:
    """Count how many consecutive try wrappers enclose this node."""
    depth = 1
    current = node
    while current.parent is not None:
        parent = current.parent
        if parent.type == "statement_block":
            grandparent = parent.parent
            if grandparent is not None and grandparent.type == "try_statement":
                # Check if this statement_block is the try body (compare by range, not identity)
                parent_range = (parent.start_byte, parent.end_byte)
                for child in grandparent.named_children:
                    child_range = (child.start_byte, child.end_byte)
                    if child_range == parent_range:
                        depth += 1
                        current = grandparent
                        break
                else:
                    break
            else:
                break
        else:
            break
    return depth


# ---------------------------------------------------------------------------
# Checks: file-too-long
# ---------------------------------------------------------------------------


def file_too_long(path: Path, source: bytes) -> list[Finding]:
    line_count = source.count(b"\n") + (0 if not source else 1)
    if line_count > FILE_TOO_LONG:
        return [
            Finding(
                path=path,
                line=1,
                col=1,
                level="WARN",
                message=f"file-too-long: {line_count} lines (> {FILE_TOO_LONG})",
            )
        ]
    return []


# ---------------------------------------------------------------------------
# Checks: file-scope comment
# ---------------------------------------------------------------------------


def file_scope_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Check for a meaningful module-level description comment."""
    findings: list[Finding] = []

    scope_text, comment_line = _module_scope_text(tree.root_node, source)
    if scope_text is None:
        findings.append(
            Finding(
                path=path,
                line=1,
                col=1,
                level="WARN",
                message="file-scope-comment: missing module purpose/scope description near top of file",
            )
        )
    else:
        scope_words = _text_keywords(scope_text)
        if len(scope_words) < MIN_SCOPE_WORDS:
            findings.append(
                Finding(
                    path=path,
                    line=comment_line,
                    col=1,
                    level="WARN",
                    message=f"file-scope-comment: purpose/scope text is too brief ({len(scope_words)} keywords)",
                )
            )

        # Check if exports are covered
        export_names = _exported_symbols(tree.root_node, source)
        if export_names and scope_words:
            uncovered = _uncovered_symbols(export_names, scope_words)
            if uncovered:
                peers = ", ".join(uncovered[:4])
                suffix = "" if len(uncovered) <= 4 else f" (+{len(uncovered) - 4} more)"
                findings.append(
                    Finding(
                        path=path,
                        line=comment_line,
                        col=1,
                        level="WARN",
                        message=f"file-scope-mismatch: header may not cover module scope; uncovered symbols: {peers}{suffix}",
                    )
                )

    return findings


def _module_scope_text(program: Node, source: bytes) -> tuple[str | None, int]:
    """Extract leading comment block text from the file."""
    comments: list[str] = []
    first_line = 1

    for child in program.children:
        if child.is_extra and child.type == "comment":
            text = child.text.decode("utf-8", errors="replace").lstrip("/").lstrip("*").strip()
            # Remove leading * from JSDoc-style lines
            text = "\n".join(
                line.lstrip("*").strip() for line in text.splitlines()
            ).strip()
            if text:
                comments.append(text)
            if first_line == 1:
                first_line = child.start_point[0] + 1
        elif not child.is_extra or child.type not in ("comment",):
            # Stop at the first non-comment meaningful node
            break

    if not comments:
        return None, 1
    return " ".join(comments), first_line


def _exported_symbols(program: Node, source: bytes) -> list[str]:
    """Collect names of exported symbols at module level."""
    names: list[str] = []
    for child in program.named_children:
        if child.type == "export_statement":
            for sub in child.named_children:
                if sub.type in ("function_declaration", "generator_function_declaration", "class_declaration"):
                    for id_node in sub.named_children:
                        if id_node.type == "identifier":
                            names.append(id_node.text.decode("utf-8", errors="replace"))
                            break
                elif sub.type == "lexical_declaration":
                    for decl in sub.named_children:
                        if decl.type == "variable_declarator":
                            for id_node in decl.named_children:
                                if id_node.type == "identifier":
                                    names.append(id_node.text.decode("utf-8", errors="replace"))
                                    break
                            break
                elif sub.type == "variable_declaration":
                    for decl in sub.named_children:
                        if decl.type == "variable_declarator":
                            for id_node in decl.named_children:
                                if id_node.type == "identifier":
                                    names.append(id_node.text.decode("utf-8", errors="replace"))
                                    break
                            break
    return names


def _uncovered_symbols(symbols: list[str], scope_words: set[str]) -> list[str]:
    """Return symbols whose keyword representation isn't covered by scope text."""
    uncovered: list[str] = []
    for sym in symbols:
        sym_words = _identifier_keywords(sym)
        if sym_words and not sym_words.issubset(scope_words):
            uncovered.append(sym)
    return uncovered


# ---------------------------------------------------------------------------
# Keyword extraction (shared with scope checks)
# ---------------------------------------------------------------------------

STOPWORDS = {
    "and", "are", "for", "from", "into", "module", "that", "the", "this", "with",
    "export", "import", "default", "const", "function", "class",
}


def _identifier_keywords(name: str) -> set[str]:
    """Extract meaningful subwords from a camelCase/PascalCase/snake_case identifier."""
    words = re.findall(r"[A-Z]+(?=[A-Z][a-z]|$)|[A-Z]?[a-z]+|[A-Z]+|\d+", name)
    lowered = {w.lower() for w in words if len(w) >= 3}
    return {w for w in lowered if w not in STOPWORDS}


def _text_keywords(text: str) -> set[str]:
    """Extract meaningful keywords from free text."""
    raw = re.findall(r"[A-Za-z][A-Za-z0-9_]*", text)
    keywords: set[str] = set()
    for token in raw:
        keywords |= _identifier_keywords(token)
    return keywords


# ---------------------------------------------------------------------------
# Checks: near-duplicate
# ---------------------------------------------------------------------------


_NORMALIZE_NODE_TYPES = frozenset(
    {
        "identifier",
        "property_identifier",
        "shorthand_property_identifier",
        "statement_identifier",
    }
)
_NORMALIZE_VALUE_TYPES = frozenset(
    {
        "number",
        "string",
        "string_fragment",
        "template_string",
        "template_substitution",
        "regex",
        "regex_pattern",
        "regex_flags",
        "escape_sequence",
    }
)
_SKIP_NODE_TYPES = frozenset({"comment"})


def hash_normalized_body(func_node: Node, source: bytes) -> str:
    """Return SHA-1 hash of the normalized function body + parameters."""
    body_node = None
    params_node = None
    for child in func_node.named_children:
        if child.type == "statement_block":
            body_node = child
        elif child.type == "formal_parameters":
            params_node = child

    parts: list[object] = []
    if params_node is not None:
        parts.append(_normalize_tuple(params_node, source))
    if body_node is not None:
        parts.append(_normalize_tuple(body_node, source))

    serialized = repr(parts).encode("utf-8")
    return hashlib.sha1(serialized).hexdigest()


def _normalize_tuple(node: Node, source: bytes) -> object:
    """Return a nested tuple with identifiers/values normalized."""
    if node.type in _SKIP_NODE_TYPES:
        return None
    if node.type in _NORMALIZE_NODE_TYPES:
        return ("id",)
    if node.type in _NORMALIZE_VALUE_TYPES:
        return ("val",)

    children = []
    for child in node.named_children:
        result = _normalize_tuple(child, source)
        if result is not None:
            children.append(result)

    if children:
        return (node.type,) + tuple(children)
    return (node.type,)


def near_duplicate_findings(
    infos: list[FunctionInfo],
) -> list[Finding]:
    by_hash: dict[str, list[FunctionInfo]] = {}
    for info in infos:
        if info.normalized_hash:
            by_hash.setdefault(info.normalized_hash, []).append(info)

    findings: list[Finding] = []
    for group in by_hash.values():
        if len(group) < 2:
            continue
        peers = ", ".join(f"{g.path}:{g.line}" for g in group[1:4])
        suffix = "" if len(group) <= 4 else f" (+{len(group) - 4} more)"
        first = group[0]
        findings.append(
            Finding(
                path=first.path,
                line=first.line,
                col=first.col,
                level="WARN",
                message=f"near-duplicate: {first.name} matches {peers}{suffix}",
            )
        )
    return findings


# ---------------------------------------------------------------------------
# TS-specific checks
# ---------------------------------------------------------------------------


def ts_any_usage_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Flag `any` type usages in TypeScript files."""
    findings: list[Finding] = []
    any_count = 0

    for node in walk_named(tree.root_node):
        if node.type == "predefined_type" and node.text == b"any":
            any_count += 1

    if any_count > 0:
        line, col = _first_any_location(tree.root_node, source)
        level = "WARN" if any_count > ANY_WARN_THRESHOLD else "INFO"
        findings.append(
            Finding(
                path=path,
                line=line,
                col=col,
                level=level,
                message=f"any-usage: {any_count} `any` type usages (escape hatch from type safety)",
            )
        )
    return findings


def _first_any_location(program: Node, source: bytes) -> tuple[int, int]:
    for node in walk_named(program):
        if node.type == "predefined_type" and node.text == b"any":
            return node_line_col(node)
    return 1, 1


def ts_as_cast_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Flag `as` type assertions in TypeScript files."""
    findings: list[Finding] = []
    as_count = 0

    for node in walk_named(tree.root_node):
        if node.type == "as_expression":
            as_count += 1

    if as_count > 0:
        line, col = _first_as_location(tree.root_node, source)
        findings.append(
            Finding(
                path=path,
                line=line,
                col=col,
                level="WARN",
                message=f"as-cast: {as_count} `as` type assertions (may suppress type errors)",
            )
        )
    return findings


def _first_as_location(program: Node, source: bytes) -> tuple[int, int]:
    for node in walk_named(program):
        if node.type == "as_expression":
            return node_line_col(node)
    return 1, 1


def ts_ignore_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Flag @ts-ignore and @ts-expect-error comments."""
    findings: list[Finding] = []
    for child in tree.root_node.children:
        if child.is_extra and child.type == "comment":
            text = child.text.decode("utf-8", errors="replace")
            if "@ts-ignore" in text:
                line, col = node_line_col(child)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message="ts-ignore: @ts-ignore suppresses type checking; prefer fixing the type error",
                    )
                )
            elif "@ts-expect-error" in text:
                line, col = node_line_col(child)
                findings.append(
                    Finding(
                        path=path,
                        line=line,
                        col=col,
                        level="WARN",
                        message="ts-ignore: @ts-expect-error suppresses type checking; prefer fixing the type error",
                    )
                )
    return findings


def ts_type_coverage_findings(tree: Node, path: Path, source: bytes) -> list[Finding]:
    """Check percentage of functions with full type annotations (TS only)."""
    functions = [node for node in walk_named(tree.root_node) if is_function_node(node)]
    if not functions:
        return []

    typed_count = sum(1 for fn in functions if _is_fully_annotated_ts(fn))
    total_count = len(functions)
    typed_percent = (typed_count / total_count) * 100.0

    if typed_percent >= MIN_TYPED_FUNCTION_PERCENT:
        return []

    return [
        Finding(
            path=path,
            line=1,
            col=1,
            level="INFO",
            message=(
                "type-coverage: "
                f"{typed_count}/{total_count} functions fully annotated "
                f"({typed_percent:.1f}% < {MIN_TYPED_FUNCTION_PERCENT:.1f}%)"
            ),
        )
    ]


def _is_fully_annotated_ts(func_node: Node) -> bool:
    """Check if a TS function has parameter and return type annotations."""
    has_return_type = False
    all_params_annotated = True
    param_count = 0

    for child in func_node.named_children:
        if child.type == "formal_parameters":
            for param in child.named_children:
                if param.type not in ("required_parameter", "optional_parameter"):
                    continue
                param_count += 1
                if not _has_type_annotation(param):
                    all_params_annotated = False

    # Check for return type annotation (type_annotation is a named child)
    for child in func_node.named_children:
        if child.type == "type_annotation":
            has_return_type = True
            break

    if param_count == 0:
        return has_return_type
    return all_params_annotated and has_return_type


def _has_type_annotation(param: Node) -> bool:
    """Check if a required_parameter/optional_parameter has a type_annotation child."""
    for child in param.named_children:
        if child.type == "type_annotation":
            return True
    return False


# ---------------------------------------------------------------------------
# Lint orchestrator
# ---------------------------------------------------------------------------


def lint_path(
    target: Path,
    extensions: tuple[str, ...],
    excludes: tuple[str, ...] = (),
) -> list[Finding]:
    findings: list[Finding] = []
    function_infos: list[FunctionInfo] = []

    for path in sorted(iter_files(target, extensions, excludes)):
        try:
            source = path.read_bytes()
        except OSError:
            findings.append(
                Finding(path=path, line=1, col=1, level="WARN", message="unreadable source file")
            )
            continue

        # Decode for text-based checks
        try:
            source_text = source.decode("utf-8")
        except UnicodeDecodeError:
            findings.append(
                Finding(path=path, line=1, col=1, level="WARN", message="unreadable utf-8 source")
            )
            continue

        findings.extend(file_too_long(path, source))

        ext = path.suffix
        lang = language_for_extension(ext)
        parser = Parser(lang)

        try:
            tree = parser.parse(source)
        except Exception as e:
            findings.append(
                Finding(
                    path=path,
                    line=1,
                    col=1,
                    level="WARN",
                    message=f"parse-error: {e}",
                )
            )
            continue

        if tree.root_node.has_error:
            error_line, error_col = _find_error_location(tree.root_node, source)
            findings.append(
                Finding(
                    path=path,
                    line=error_line,
                    col=error_col,
                    level="WARN",
                    message="parse-error: syntax error in source",
                )
            )

        # --- shared checks ---
        findings.extend(long_function_findings(tree, path))
        findings.extend(nesting_depth_findings(tree, path))
        findings.extend(parameter_count_findings(tree, path))
        findings.extend(var_usage_findings(tree, path))
        findings.extend(eqeqeq_findings(tree, path, source))
        findings.extend(console_log_findings(tree, path, source))
        findings.extend(module_mutable_state_findings(tree, path, source))
        findings.extend(fail_soft_input_findings(tree, path, source))
        findings.extend(context_manager_candidate_findings(tree, path, source))
        findings.extend(file_scope_findings(tree, path, source))

        # --- function infos for near-duplicate ---
        for node in walk_named(tree.root_node):
            if node.type in _FN_TYPES:
                body_hash = hash_normalized_body(node, source)
                line, col = node_line_col(node)
                function_infos.append(
                    FunctionInfo(
                        path=path,
                        name=function_name(node),
                        line=line,
                        col=col,
                        normalized_hash=body_hash,
                    )
                )

        # --- TS-specific checks ---
        if is_ts_extension(ext):
            findings.extend(ts_any_usage_findings(tree, path, source))
            findings.extend(ts_as_cast_findings(tree, path, source))
            findings.extend(ts_ignore_findings(tree, path, source))
            findings.extend(ts_type_coverage_findings(tree, path, source))

    findings.extend(near_duplicate_findings(function_infos))
    findings.sort(key=lambda f: (str(f.path), f.line, f.col, f.level, f.message))
    return findings


def _find_error_location(node: Node, source: bytes) -> tuple[int, int]:
    """Find the first ERROR or MISSING node and return its line/col."""
    for child in node.children:
        if child.has_error or child.is_missing:
            return node_line_col(child)
        result = _find_error_location(child, source)
        if result != (1, 1):
            return result
    return 1, 1


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    args = parse_args()
    target = Path(args.path)
    if not target.exists():
        print(f"{target}:1:1 [WARN] target-path-not-found")
        return 2

    if args.extensions is not None:
        extensions = tuple(
            e if e.startswith(".") else f".{e}"
            for e in args.extensions.split(",")
        )
    else:
        extensions = ALL_EXTENSIONS

    findings = lint_path(target, extensions, normalize_excludes(args.exclude))
    for finding in findings:
        print(finding.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
