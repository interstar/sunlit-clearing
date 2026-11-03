"""Unit tests for sessions_bridge helpers (no eyeball required)."""

from __future__ import annotations

from datetime import datetime, timezone

import sessions_bridge as bridge


def test_dt_none():
    assert bridge._dt(None) is None


def test_dt_iso():
    value = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)
    assert bridge._dt(value) == "2026-09-23T12:00:00+00:00"


def test_main_usage_error():
    assert bridge.main(["sessions_bridge.py"]) == 1


def test_main_unknown_command(capsys):
    code = bridge.main(["sessions_bridge.py", "nope"])
    assert code == 1
    out = capsys.readouterr().out
    assert "unknown command" in out
