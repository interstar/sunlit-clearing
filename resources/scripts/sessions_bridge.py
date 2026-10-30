#!/usr/bin/env python3
"""JSON bridge from Codestory to eyeball_kid + Cursor IDE transcripts."""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

# Allow importing sibling modules when run as a script
_SCRIPTS = Path(__file__).resolve().parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

import cursor_sessions  # noqa: E402


def _dt(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.isoformat()


def _engine():
    try:
        from eyeball_shared.session_engine import SessionEngine
    except ImportError:
        # editable install path fallback
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eyeball_kid"))
        try:
            from eyeball_shared.session_engine import SessionEngine
        except ImportError as exc:
            raise SystemExit(json.dumps({"ok": False, "error": f"eyeball_shared not importable: {exc}"}))

    home = Path.home()
    engine = SessionEngine(
        root=home / ".codex" / "sessions",
        cursor_root=home / ".config" / "Cursor",
        pi_root=home / ".pi" / "agent" / "sessions",
        source="all",
    )
    engine.rebuild()
    return engine


def _session_entry(source: str, rec) -> dict:
    return {
        "session_id": rec.session_id,
        "source": source,
        "cwd": rec.cwd,
        "slug": rec.slug,
        "message_count": rec.message_count,
        "started_at": _dt(rec.started_at),
    }


def _session_detail(engine, source: str, rec) -> dict:
    messages = []
    for msg in engine.session_messages(rec.session_id):
        messages.append(
            {
                "role": msg.role,
                "timestamp": _dt(msg.timestamp),
                "text": msg.text,
                "source": msg.source,
            }
        )
    return {
        "ok": True,
        "session": _session_entry(source, rec),
        "messages": messages,
    }


def _merge_cursor(repo: str, sessions: list, details: dict) -> None:
    try:
        cur_sessions, cur_details = cursor_sessions.collect_for_repo(repo)
    except Exception as exc:  # noqa: BLE001
        # Non-fatal: still return eyeball results
        sessions.append(
            {
                "session_id": "cursor-error",
                "source": "cursor",
                "cwd": repo,
                "slug": f"Cursor scan error: {exc}",
                "message_count": 0,
                "started_at": None,
            }
        )
        return
    sessions.extend(cur_sessions)
    details.update(cur_details)


def cmd_list(repo: str) -> dict:
    target = Path(repo).expanduser().resolve()
    sessions: list = []
    try:
        engine = _engine()
        entries = engine.sessions_for_directory(target)
        sessions.extend(_session_entry(source, rec) for source, rec in entries)
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        # Continue with Cursor-only if eyeball fails
        err = str(exc)
        sessions.append(
            {
                "session_id": "eyeball-error",
                "source": "eyeball",
                "cwd": str(target),
                "slug": f"eyeball unavailable: {err}",
                "message_count": 0,
                "started_at": None,
            }
        )
    details: dict = {}
    _merge_cursor(str(target), sessions, details)
    # list endpoint does not need details; drop error stubs with 0 msgs? keep them
    sessions = [s for s in sessions if not str(s.get("session_id", "")).endswith("-error")]
    return {"ok": True, "repo": str(target), "sessions": sessions}


def cmd_show(session_id: str) -> dict:
    if session_id.startswith("cursor-sqlite:") or session_id.startswith("cursor-jsonl:"):
        # Resolve from a dump against cwd unknown — scan all cursor stores for this id
        agent = session_id.split(":", 1)[1]
        # Prefer direct loaders
        if session_id.startswith("cursor-jsonl:"):
            path = cursor_sessions.find_jsonl_for_agent(agent)
            msgs = cursor_sessions.load_jsonl_messages(path) if path else []
            source = "cursor-jsonl"
        else:
            msgs = []
            source = "cursor-sqlite"
            chats = Path.home() / ".cursor" / "chats"
            for store in chats.glob(f"*/{agent}/store.db"):
                msgs = cursor_sessions.load_sqlite_messages(store)
                if msgs:
                    break
            if not msgs:
                # agent folder name may differ from meta agentId
                for store in chats.glob("*/*/store.db"):
                    if store.parent.name == agent:
                        msgs = cursor_sessions.load_sqlite_messages(store)
                        break
        if not msgs:
            return {"ok": False, "error": f"No Cursor transcript for {session_id}"}
        entry = {
            "session_id": session_id,
            "source": source,
            "cwd": None,
            "slug": agent,
            "message_count": len(msgs),
            "started_at": msgs[0].get("timestamp"),
        }
        return {"ok": True, "session": entry, "messages": msgs}

    engine = _engine()
    resolved = session_id
    if session_id not in engine.sessions:
        matches = [sid for sid in engine.sessions if session_id in sid]
        if len(matches) == 1:
            resolved = matches[0]
        elif not matches:
            return {"ok": False, "error": f"No session matching {session_id}"}
        else:
            return {"ok": False, "error": f"Ambiguous session id: {session_id}", "matches": matches}

    rec = engine.sessions[resolved]
    return _session_detail(engine, rec.source, rec)


def cmd_dump(repo: str) -> dict:
    """One rebuild: eyeball sessions for repo + Cursor SQLite/JSONL."""
    target = Path(repo).expanduser().resolve()
    sessions: list = []
    details: dict = {}
    eyeball_error = None
    try:
        engine = _engine()
        entries = engine.sessions_for_directory(target)
        for source, rec in entries:
            entry = _session_entry(source, rec)
            sessions.append(entry)
            details[rec.session_id] = _session_detail(engine, source, rec)
    except SystemExit as e:
        if isinstance(e.code, str):
            try:
                payload = json.loads(e.code)
                eyeball_error = payload.get("error") or e.code
            except json.JSONDecodeError:
                eyeball_error = e.code
        else:
            eyeball_error = "eyeball engine failed"
    except Exception as exc:  # noqa: BLE001
        eyeball_error = str(exc)

    _merge_cursor(str(target), sessions, details)
    sessions = [s for s in sessions if not str(s.get("session_id", "")).endswith("-error")]

    out = {
        "ok": True,
        "repo": str(target),
        "sessions": sessions,
        "details": details,
    }
    if eyeball_error:
        out["eyeball_warning"] = eyeball_error
    return out


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(
            json.dumps(
                {
                    "ok": False,
                    "error": "usage: sessions_bridge.py list <repo>|show <id>|dump <repo>",
                }
            )
        )
        return 1
    cmd = argv[1]
    try:
        if cmd == "list" and len(argv) >= 3:
            print(json.dumps(cmd_list(argv[2])))
            return 0
        if cmd == "show" and len(argv) >= 3:
            print(json.dumps(cmd_show(argv[2])))
            return 0
        if cmd == "dump" and len(argv) >= 3:
            print(json.dumps(cmd_dump(argv[2])))
            return 0
        print(json.dumps({"ok": False, "error": f"unknown command {cmd}"}))
        return 1
    except SystemExit as e:
        if isinstance(e.code, str):
            print(e.code)
        return 1
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"ok": False, "error": str(exc)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
