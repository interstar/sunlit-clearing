"""Discover and parse Cursor IDE chat stores (SQLite) and agent-transcript JSONL."""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _dt_ms(ms: int | float | None) -> str | None:
    if ms is None:
        return None
    try:
        return datetime.fromtimestamp(float(ms) / 1000.0, tz=timezone.utc).isoformat()
    except (OverflowError, OSError, ValueError):
        return None


def _project_slug(repo: Path) -> str:
    return str(repo.resolve()).lstrip("/").replace("/", "-")


def _cwd_matches(cwd: str | None, repo: Path) -> bool:
    if not cwd:
        return False
    try:
        c = Path(cwd).expanduser().resolve()
        r = repo.resolve()
    except OSError:
        return False
    return c == r or r in c.parents or c in r.parents


def _flatten_content(content: Any, *, max_tool: int = 8000) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for part in content:
            if isinstance(part, str):
                if part.strip():
                    parts.append(part)
                continue
            if not isinstance(part, dict):
                continue
            ptype = part.get("type")
            if ptype == "text" and part.get("text"):
                parts.append(str(part["text"]))
            elif ptype == "reasoning" and part.get("text"):
                parts.append(str(part["text"]))
            elif ptype in {"tool-call", "tool_use", "tool-call-start"}:
                name = part.get("name") or part.get("toolName") or "tool"
                args = part.get("arguments") or part.get("input") or part.get("args")
                block = f"<tool_call>\n{name}\n"
                if args is not None:
                    try:
                        block += json.dumps(args, indent=2, ensure_ascii=False)[:max_tool]
                    except TypeError:
                        block += str(args)[:max_tool]
                block += "\n</tool_call>"
                parts.append(block)
            elif ptype in {"tool-result", "tool_result"}:
                body = part.get("content") or part.get("output") or part.get("result") or part
                try:
                    rendered = body if isinstance(body, str) else json.dumps(body, indent=2, ensure_ascii=False)
                except TypeError:
                    rendered = str(body)
                if len(rendered) > max_tool:
                    rendered = rendered[:max_tool] + "\n…(truncated)"
                parts.append(f"<tool_result>\n{rendered}\n</tool_result>")
        return "\n\n".join(parts)
    try:
        return json.dumps(content, indent=2, ensure_ascii=False)
    except TypeError:
        return str(content)


_TS_RE = re.compile(
    r"<timestamp>\s*([^<]+?)\s*</timestamp>",
    re.IGNORECASE,
)


def _guess_timestamp(text: str) -> str | None:
    m = _TS_RE.search(text or "")
    if not m:
        return None
    raw = m.group(1).strip()
    # Already ISO-ish from Codex; Cursor uses e.g. "Wednesday, Sep 23, 2026, 4:40 PM (UTC+1)"
    return raw


def load_sqlite_messages(store_db: Path) -> list[dict[str, Any]]:
    """Read unencrypted role blobs from a Cursor chat store.db (rowid order)."""
    import sqlite3

    if not store_db.is_file():
        return []
    messages: list[dict[str, Any]] = []
    try:
        con = sqlite3.connect(f"file:{store_db}?mode=ro", uri=True)
    except sqlite3.Error:
        return []
    try:
        for data, in con.execute("SELECT data FROM blobs ORDER BY rowid"):
            if not isinstance(data, (bytes, bytearray)):
                continue
            try:
                obj = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if not isinstance(obj, dict) or "role" not in obj:
                continue
            role = str(obj.get("role") or "unknown")
            text = _flatten_content(obj.get("content"))
            if not text.strip():
                continue
            # Skip giant system prompts unless short
            if role == "system" and len(text) > 4000:
                text = text[:4000] + "\n…(truncated)"
            messages.append(
                {
                    "role": role,
                    "timestamp": _guess_timestamp(text),
                    "text": text,
                    "source": "cursor-sqlite",
                }
            )
    finally:
        con.close()
    return messages


def load_jsonl_messages(jsonl_path: Path) -> list[dict[str, Any]]:
    if not jsonl_path.is_file():
        return []
    messages: list[dict[str, Any]] = []
    try:
        with jsonl_path.open("r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                role = str(obj.get("role") or "unknown")
                msg = obj.get("message") or {}
                text = _flatten_content(msg.get("content") if isinstance(msg, dict) else msg)
                if not text.strip():
                    continue
                messages.append(
                    {
                        "role": role,
                        "timestamp": _guess_timestamp(text),
                        "text": text,
                        "source": "cursor-jsonl",
                    }
                )
    except OSError:
        return []
    return messages


def find_jsonl_for_agent(agent_id: str) -> Path | None:
    root = Path.home() / ".cursor" / "projects"
    if not root.is_dir():
        return None
    direct = list(root.glob(f"*/agent-transcripts/{agent_id}/{agent_id}.jsonl"))
    if direct:
        return direct[0]
    nested = list(root.glob(f"*/agent-transcripts/{agent_id}/*.jsonl"))
    return nested[0] if nested else None


def _entry(
    *,
    session_id: str,
    source: str,
    cwd: str | None,
    slug: str | None,
    message_count: int,
    started_at: str | None,
    title: str | None = None,
) -> dict[str, Any]:
    return {
        "session_id": session_id,
        "source": source,
        "cwd": cwd,
        "slug": slug or title or session_id,
        "title": title,
        "message_count": message_count,
        "started_at": started_at,
    }


def _detail(entry: dict[str, Any], messages: list[dict[str, Any]]) -> dict[str, Any]:
    return {"ok": True, "session": entry, "messages": messages}


def collect_for_repo(repo: str | Path) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    """Return (sessions, details) for Cursor SQLite + JSONL matching repo."""
    target = Path(repo).expanduser().resolve()
    sessions: list[dict[str, Any]] = []
    details: dict[str, dict[str, Any]] = {}
    seen_jsonl: set[str] = set()

    chats_root = Path.home() / ".cursor" / "chats"
    if chats_root.is_dir():
        for meta_path in sorted(chats_root.glob("*/*/meta.json")):
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if not _cwd_matches(meta.get("cwd"), target):
                continue
            agent_id = str(meta.get("agentId") or meta_path.parent.name)
            title = meta.get("title") or meta.get("name")
            cwd = meta.get("cwd")
            started = _dt_ms(meta.get("createdAtMs") or meta.get("createdAt"))

            store_db = meta_path.parent / "store.db"
            sqlite_msgs = load_sqlite_messages(store_db)
            if sqlite_msgs:
                sid = f"cursor-sqlite:{agent_id}"
                entry = _entry(
                    session_id=sid,
                    source="cursor-sqlite",
                    cwd=cwd,
                    slug=title or agent_id,
                    message_count=len(sqlite_msgs),
                    started_at=started or sqlite_msgs[0].get("timestamp"),
                    title=title,
                )
                sessions.append(entry)
                details[sid] = _detail(entry, sqlite_msgs)

            jsonl_path = find_jsonl_for_agent(agent_id)
            if jsonl_path is not None:
                jsonl_msgs = load_jsonl_messages(jsonl_path)
                if jsonl_msgs:
                    sid = f"cursor-jsonl:{agent_id}"
                    entry = _entry(
                        session_id=sid,
                        source="cursor-jsonl",
                        cwd=cwd,
                        slug=title or agent_id,
                        message_count=len(jsonl_msgs),
                        started_at=started or jsonl_msgs[0].get("timestamp"),
                        title=title,
                    )
                    sessions.append(entry)
                    details[sid] = _detail(entry, jsonl_msgs)
                    seen_jsonl.add(agent_id)

    # JSONL under this project's agent-transcripts even without a chats meta match
    proj = Path.home() / ".cursor" / "projects" / _project_slug(target) / "agent-transcripts"
    if proj.is_dir():
        for agent_dir in sorted(proj.iterdir()):
            if not agent_dir.is_dir():
                continue
            agent_id = agent_dir.name
            if agent_id in seen_jsonl:
                continue
            jsonl_path = agent_dir / f"{agent_id}.jsonl"
            if not jsonl_path.is_file():
                candidates = list(agent_dir.glob("*.jsonl"))
                jsonl_path = candidates[0] if candidates else jsonl_path
            jsonl_msgs = load_jsonl_messages(jsonl_path)
            if not jsonl_msgs:
                continue
            sid = f"cursor-jsonl:{agent_id}"
            entry = _entry(
                session_id=sid,
                source="cursor-jsonl",
                cwd=str(target),
                slug=agent_id,
                message_count=len(jsonl_msgs),
                started_at=jsonl_msgs[0].get("timestamp"),
                title=None,
            )
            sessions.append(entry)
            details[sid] = _detail(entry, jsonl_msgs)

    return sessions, details
