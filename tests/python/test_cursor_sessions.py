"""Unit tests for cursor_sessions helpers (no Cursor install required)."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import cursor_sessions as cs


def test_flatten_text_and_parts():
    assert cs._flatten_content("hello") == "hello"
    assert "Hi" in cs._flatten_content([{"type": "text", "text": "Hi"}])
    out = cs._flatten_content(
        [
            {"type": "tool-call", "name": "Shell", "arguments": {"command": "ls"}},
            {"type": "tool-result", "content": "ok"},
        ]
    )
    assert "<tool_call>" in out
    assert "<tool_result>" in out
    assert "Shell" in out


def test_load_jsonl(tmp_path: Path):
    p = tmp_path / "chat.jsonl"
    p.write_text(
        json.dumps(
            {
                "role": "user",
                "message": {
                    "content": [
                        {
                            "type": "text",
                            "text": "<timestamp>t</timestamp>\n<user_query>\nHello\n</user_query>",
                        }
                    ]
                },
            }
        )
        + "\n"
        + json.dumps(
            {
                "role": "assistant",
                "message": {"content": [{"type": "text", "text": "Hi there"}]},
            }
        )
        + "\n",
        encoding="utf-8",
    )
    msgs = cs.load_jsonl_messages(p)
    assert len(msgs) == 2
    assert msgs[0]["role"] == "user"
    assert "user_query" in msgs[0]["text"]
    assert msgs[1]["text"] == "Hi there"


def test_load_sqlite(tmp_path: Path):
    db = tmp_path / "store.db"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE blobs (id TEXT, data BLOB)")
    con.execute(
        "INSERT INTO blobs VALUES (?, ?)",
        (
            "a",
            json.dumps({"role": "user", "content": "hello from sqlite"}).encode(),
        ),
    )
    con.execute(
        "INSERT INTO blobs VALUES (?, ?)",
        ("b", b"\x00\x01encrypted"),
    )
    con.execute(
        "INSERT INTO blobs VALUES (?, ?)",
        (
            "c",
            json.dumps(
                {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "reply"}],
                }
            ).encode(),
        ),
    )
    con.commit()
    con.close()
    msgs = cs.load_sqlite_messages(db)
    assert len(msgs) == 2
    assert msgs[0]["text"] == "hello from sqlite"
    assert msgs[1]["text"] == "reply"


def test_cwd_matches(tmp_path: Path):
    repo = tmp_path / "proj"
    repo.mkdir()
    assert cs._cwd_matches(str(repo), repo)
    assert cs._cwd_matches(str(repo / "src"), repo)
    assert not cs._cwd_matches(str(tmp_path / "other"), repo)
