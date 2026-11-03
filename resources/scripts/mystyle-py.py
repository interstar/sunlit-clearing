#!/usr/bin/env python3
"""Lightweight style linter for Python source trees.

Output format:
path:line:col [LEVEL] message
"""

from __future__ import annotations

import argparse
import ast
import copy
import hashlib
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

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
    "build",
    "dist",
    "target",
    "resources",
    ".sunlit",
    "out",
    "compiled",
    "vendor",
    "coverage",
    "htmlcov",
}

LONG_FN_WARN = 100
LONG_FN_HARD = 200
FILE_TOO_LONG = 1000
MAX_NESTING_DEPTH = 6
MAX_PARAMETERS = 7
MIN_SCOPE_WORDS = 6
MIN_TYPED_FUNCTION_PERCENT = 10.0


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


class NormalizeBody(ast.NodeTransformer):
    """Normalize volatile identifiers/constants to detect structural duplicates."""

    def visit_Name(self, node: ast.Name) -> ast.AST:
        return ast.copy_location(ast.Name(id="_", ctx=node.ctx), node)

    def visit_arg(self, node: ast.arg) -> ast.AST:
        return ast.copy_location(ast.arg(arg="_", annotation=None, type_comment=None), node)

    def visit_Constant(self, node: ast.Constant) -> ast.AST:
        value = node.value
        if isinstance(value, (int, float, complex)):
            value = 0
        elif isinstance(value, str):
            value = ""
        elif isinstance(value, bytes):
            value = b""
        return ast.copy_location(ast.Constant(value=value, kind=None), node)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Lint Python code for style risks.")
    parser.add_argument("path", nargs="?", default=".", help="Target file or directory")
    parser.add_argument(
        "--extensions",
        default=".py",
        help="Comma-separated file extensions to lint (for example: .py,.py.j2)",
    )
    return parser.parse_args()


def normalize_extensions(raw: str) -> tuple[str, ...]:
    values = [part.strip() for part in str(raw or "").split(",") if part.strip()]
    if not values:
        return (".py",)
    normalized: list[str] = []
    for value in values:
        ext = value if value.startswith(".") else f".{value}"
        if ext not in normalized:
            normalized.append(ext)
    return tuple(normalized)


def iter_python_files(target: Path, extensions: tuple[str, ...]) -> Iterable[Path]:
    if target.is_file() and any(target.name.endswith(ext) for ext in extensions):
        yield target
        return

    for root, dirs, files in os.walk(target):
        dirs[:] = [d for d in dirs if d not in IGNORED_DIRS]
        for name in files:
            if any(name.endswith(ext) for ext in extensions):
                yield Path(root) / name


def function_infos_and_findings(tree: ast.AST, path: Path) -> tuple[list[FunctionInfo], list[Finding]]:
    infos: list[FunctionInfo] = []
    findings: list[Finding] = []

    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue

        line = getattr(node, "lineno", 1)
        col = getattr(node, "col_offset", 0) + 1

        end_line = getattr(node, "end_lineno", line)
        span = max(1, end_line - line + 1)
        if span > LONG_FN_HARD:
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="HARD",
                    message=f"long-function: {node.name} spans {span} lines (> {LONG_FN_HARD})",
                )
            )
        elif span > LONG_FN_WARN:
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message=f"long-function: {node.name} spans {span} lines (> {LONG_FN_WARN})",
                )
            )

        parameter_count = count_parameters(node)
        if parameter_count > MAX_PARAMETERS:
            findings.append(
                Finding(
                    path=path,
                    line=line,
                    col=col,
                    level="WARN",
                    message=f"parameter-count: {node.name} has {parameter_count} parameters (> {MAX_PARAMETERS})",
                )
            )

        for default in mutable_default_nodes(node):
            findings.append(
                Finding(
                    path=path,
                    line=getattr(default, "lineno", line),
                    col=getattr(default, "col_offset", 0) + 1,
                    level="WARN",
                    message=f"mutable-default-arg: {node.name} defines mutable default value",
                )
            )

        body_hash = hash_normalized_body(node)
        infos.append(
            FunctionInfo(
                path=path,
                name=node.name,
                line=line,
                col=col,
                normalized_hash=body_hash,
            )
        )

    return infos, findings


def type_hint_coverage_findings(tree: ast.AST, path: Path) -> list[Finding]:
    functions = [
        node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    ]
    if not functions:
        return []

    typed_count = sum(1 for node in functions if is_fully_annotated_function(node))
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
                "type-hint-coverage: "
                f"{typed_count}/{total_count} functions fully annotated "
                f"({typed_percent:.1f}% < {MIN_TYPED_FUNCTION_PERCENT:.1f}%)"
            ),
        )
    ]


def count_parameters(node: ast.FunctionDef | ast.AsyncFunctionDef) -> int:
    args = node.args
    positional = list(args.posonlyargs) + list(args.args)
    if positional and positional[0].arg in {"self", "cls"}:
        positional = positional[1:]
    count = len(positional) + len(args.kwonlyargs)
    if args.vararg is not None:
        count += 1
    if args.kwarg is not None:
        count += 1
    return count


def is_fully_annotated_function(node: ast.FunctionDef | ast.AsyncFunctionDef) -> bool:
    args = node.args
    positional = list(args.posonlyargs) + list(args.args)
    if positional and positional[0].arg in {"self", "cls"}:
        positional = positional[1:]

    parameters = positional + list(args.kwonlyargs)
    if args.vararg is not None:
        parameters.append(args.vararg)
    if args.kwarg is not None:
        parameters.append(args.kwarg)

    if any(arg.annotation is None for arg in parameters):
        return False
    return node.returns is not None


def mutable_default_nodes(node: ast.FunctionDef | ast.AsyncFunctionDef) -> list[ast.AST]:
    defaults: list[ast.AST] = list(node.args.defaults)
    defaults.extend(d for d in node.args.kw_defaults if d is not None)
    return [d for d in defaults if is_mutable_default_expr(d)]


def is_mutable_default_expr(node: ast.AST) -> bool:
    if isinstance(node, (ast.List, ast.Dict, ast.Set)):
        return True
    if isinstance(node, ast.Call):
        func = node.func
        if isinstance(func, ast.Name) and func.id in {"list", "dict", "set", "defaultdict", "deque"}:
            return True
    return False


def global_usage_findings(tree: ast.AST, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Global):
            findings.append(
                Finding(
                    path=path,
                    line=getattr(node, "lineno", 1),
                    col=getattr(node, "col_offset", 0) + 1,
                    level="WARN",
                    message="global-usage: explicit global statement",
                )
            )
    return findings


def fail_soft_input_findings(tree: ast.AST, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and is_get_with_default(node):
            findings.append(
                Finding(
                    path=path,
                    line=getattr(node, "lineno", 1),
                    col=getattr(node, "col_offset", 0) + 1,
                    level="WARN",
                    message="fail-soft-input: lookup supplies a default value; prefer explicit validation and a clear failure for missing/bad input",
                )
            )
        elif isinstance(node, ast.BoolOp) and isinstance(node.op, ast.Or) and node.values:
            first = node.values[0]
            if is_lookup_expr(first) and len(node.values) >= 2:
                findings.append(
                    Finding(
                        path=path,
                        line=getattr(node, "lineno", 1),
                        col=getattr(node, "col_offset", 0) + 1,
                        level="WARN",
                        message="fail-soft-input: fallback around lookup may mask missing/bad input; prefer explicit validation and a clear failure",
                    )
                )
    return findings


def context_manager_candidate_findings(tree: ast.AST, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Try):
            continue

        nested_try_count = count_nested_try_wrappers(node)
        distinct_exception_count = count_distinct_exception_types(node)
        if nested_try_count < 2 and distinct_exception_count < 2:
            continue

        reasons: list[str] = []
        if nested_try_count >= 2:
            reasons.append(f"{nested_try_count} nested try/except wrappers")
        if distinct_exception_count >= 2:
            reasons.append(f"{distinct_exception_count} exception types handled")

        findings.append(
            Finding(
                path=path,
                line=getattr(node, "lineno", 1),
                col=getattr(node, "col_offset", 0) + 1,
                level="WARN",
                message=(
                    "context-manager-candidate: "
                    + "; ".join(reasons)
                    + "; consider whether a ContextManager would simplify setup/cleanup and exception handling"
                ),
            )
        )
    return findings


def count_nested_try_wrappers(node: ast.Try) -> int:
    count = 1
    current = node
    while len(current.body) == 1 and isinstance(current.body[0], ast.Try):
        count += 1
        current = current.body[0]
    return count


def count_distinct_exception_types(node: ast.Try) -> int:
    names: set[str] = set()
    catches_bare_exception = False
    for handler in node.handlers:
        if handler.type is None:
            catches_bare_exception = True
            continue
        for exc in flatten_exception_types(handler.type):
            name = exception_type_name(exc)
            if name is not None:
                names.add(name)

    if catches_bare_exception and names:
        names.add("BaseException")
    return len(names)


def flatten_exception_types(node: ast.AST) -> list[ast.AST]:
    if isinstance(node, ast.Tuple):
        return list(node.elts)
    return [node]


def exception_type_name(node: ast.AST) -> str | None:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        parts: list[str] = []
        current: ast.AST | None = node
        while isinstance(current, ast.Attribute):
            parts.append(current.attr)
            current = current.value
        if isinstance(current, ast.Name):
            parts.append(current.id)
            return ".".join(reversed(parts))
    return None


def is_get_with_default(node: ast.Call) -> bool:
    if not isinstance(node.func, ast.Attribute) or node.func.attr != "get":
        return False
    positional = len(node.args)
    keywords = {kw.arg for kw in node.keywords if kw.arg is not None}
    return positional >= 2 or "default" in keywords


def is_lookup_expr(node: ast.AST) -> bool:
    if isinstance(node, ast.Subscript):
        return True
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "get":
        return True
    return False


def nesting_depth_findings(tree: ast.AST, path: Path) -> list[Finding]:
    findings: list[Finding] = []
    match_case_type = getattr(ast, "match_case", ())
    suite_nodes = (
        ast.ClassDef,
        ast.FunctionDef,
        ast.AsyncFunctionDef,
        ast.If,
        ast.For,
        ast.AsyncFor,
        ast.While,
        ast.With,
        ast.AsyncWith,
        ast.Try,
        ast.ExceptHandler,
        ast.Match,
    )
    if match_case_type:
        suite_nodes = suite_nodes + (match_case_type,)

    def walk(node: ast.AST, depth: int) -> None:
        for child in ast.iter_child_nodes(node):
            child_depth = depth + 1 if isinstance(child, suite_nodes) else depth
            if isinstance(child, suite_nodes) and child_depth > MAX_NESTING_DEPTH:
                findings.append(
                    Finding(
                        path=path,
                        line=getattr(child, "lineno", 1),
                        col=getattr(child, "col_offset", 0) + 1,
                        level="WARN",
                        message=f"nesting-depth: depth {child_depth} (> {MAX_NESTING_DEPTH})",
                    )
                )
            walk(child, child_depth)

    walk(tree, 0)
    return findings


def file_scope_findings(tree: ast.Module, path: Path, text: str) -> list[Finding]:
    findings: list[Finding] = []
    scope_text, line = module_scope_text(tree, text)
    if scope_text is None:
        findings.append(
            Finding(
                path=path,
                line=1,
                col=1,
                level="WARN",
                message="file-scope-comment: missing module purpose/scope comment near top of file",
            )
        )
        return findings

    scope_words = text_keywords(scope_text)
    if len(scope_words) < MIN_SCOPE_WORDS:
        findings.append(
            Finding(
                path=path,
                line=line,
                col=1,
                level="WARN",
                message=f"file-scope-comment: purpose/scope text is too brief ({len(scope_words)} keywords)",
            )
        )

    symbol_keywords: dict[str, set[str]] = {}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and not node.name.startswith("_"):
            words = identifier_keywords(node.name)
            if words:
                symbol_keywords[node.name] = words

    if not symbol_keywords or not scope_words:
        return findings

    uncovered = [name for name, words in symbol_keywords.items() if not words.issubset(scope_words)]
    if uncovered:
        peers = ", ".join(uncovered[:4])
        suffix = "" if len(uncovered) <= 4 else f" (+{len(uncovered)-4} more)"
        findings.append(
            Finding(
                path=path,
                line=line,
                col=1,
                level="WARN",
                message=f"file-scope-mismatch: header may not cover module scope; uncovered symbols: {peers}{suffix}",
            )
        )

    return findings


def module_scope_text(tree: ast.Module, text: str) -> tuple[str | None, int]:
    if tree.body:
        first = tree.body[0]
        if (
            isinstance(first, ast.Expr)
            and isinstance(first.value, ast.Constant)
            and isinstance(first.value.value, str)
        ):
            return first.value.value, getattr(first, "lineno", 1)
    return leading_comment_block(text)


def leading_comment_block(text: str) -> tuple[str | None, int]:
    lines = text.splitlines()
    index = 0

    if index < len(lines) and lines[index].startswith("#!"):
        index += 1
    if index < len(lines) and re.match(r"^#.*coding[:=]\\s*[-\\w.]+", lines[index]):
        index += 1
    while index < len(lines) and not lines[index].strip():
        index += 1

    start = index
    collected: list[str] = []
    while index < len(lines):
        stripped = lines[index].lstrip()
        if not stripped.startswith("#"):
            break
        content = stripped[1:].strip()
        if content:
            collected.append(content)
        index += 1

    if not collected:
        return None, 1
    return " ".join(collected), start + 1


def identifier_keywords(name: str) -> set[str]:
    words = re.findall(r"[A-Z]+(?=[A-Z][a-z]|$)|[A-Z]?[a-z]+|\\d+", name)
    lowered = {w.lower() for w in words if len(w) >= 3}
    return {w for w in lowered if w not in STOPWORDS}


def text_keywords(text: str) -> set[str]:
    raw = re.findall(r"[A-Za-z][A-Za-z0-9_]*", text)
    keywords: set[str] = set()
    for token in raw:
        keywords |= identifier_keywords(token)
    return keywords


STOPWORDS = {
    "and",
    "are",
    "for",
    "from",
    "into",
    "module",
    "that",
    "the",
    "this",
    "with",
}


def hash_normalized_body(node: ast.FunctionDef | ast.AsyncFunctionDef) -> str:
    clone = ast.FunctionDef(
        name="_",
        args=copy.deepcopy(node.args),
        body=copy.deepcopy(node.body),
        decorator_list=[],
        returns=None,
        type_comment=None,
        type_params=getattr(node, "type_params", []),
    )
    normalized = NormalizeBody().visit(clone)
    ast.fix_missing_locations(normalized)
    dump = ast.dump(normalized, annotate_fields=False, include_attributes=False)
    return hashlib.sha1(dump.encode("utf-8")).hexdigest()


def file_too_long(path: Path, text: str) -> list[Finding]:
    line_count = text.count("\n") + (0 if not text else 1)
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


def lint_path(target: Path, extensions: tuple[str, ...]) -> list[Finding]:
    findings: list[Finding] = []
    function_infos: list[FunctionInfo] = []

    for path in sorted(iter_python_files(target, extensions)):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            findings.append(Finding(path=path, line=1, col=1, level="WARN", message="unreadable utf-8 source"))
            continue

        findings.extend(file_too_long(path, text))

        try:
            tree = ast.parse(text, filename=str(path))
        except SyntaxError as e:
            findings.append(
                Finding(
                    path=path,
                    line=e.lineno or 1,
                    col=e.offset or 1,
                    level="WARN",
                    message=f"parse-error: {e.msg}",
                )
            )
            continue

        infos, fn_findings = function_infos_and_findings(tree, path)
        function_infos.extend(infos)
        findings.extend(fn_findings)
        findings.extend(file_scope_findings(tree, path, text))
        findings.extend(type_hint_coverage_findings(tree, path))
        findings.extend(global_usage_findings(tree, path))
        findings.extend(fail_soft_input_findings(tree, path))
        findings.extend(context_manager_candidate_findings(tree, path))
        findings.extend(nesting_depth_findings(tree, path))

    findings.extend(near_duplicates(function_infos))
    findings.sort(key=lambda f: (str(f.path), f.line, f.col, f.level, f.message))
    return findings


def near_duplicates(infos: list[FunctionInfo]) -> list[Finding]:
    by_hash: dict[str, list[FunctionInfo]] = {}
    for info in infos:
        by_hash.setdefault(info.normalized_hash, []).append(info)

    findings: list[Finding] = []
    for group in by_hash.values():
        if len(group) < 2:
            continue
        peers = ", ".join(f"{g.path}:{g.line}" for g in group[1:4])
        suffix = "" if len(group) <= 4 else f" (+{len(group)-4} more)"
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


def main() -> int:
    args = parse_args()
    target = Path(args.path)
    if not target.exists():
        print(f"{target}:1:1 [WARN] target-path-not-found")
        return 2

    extensions = normalize_extensions(args.extensions)
    findings = lint_path(target, extensions)
    for finding in findings:
        print(finding.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
