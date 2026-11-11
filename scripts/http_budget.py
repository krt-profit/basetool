# Copyright (C) 2026 Lucas Greuloch
# SPDX-License-Identifier: GPL-3.0-only
"""Bound script HTTP connection waits and check a total deadline between response reads."""

from __future__ import annotations

import time

CONNECTION_SECONDS = 5.0
READ_CHUNK_BYTES = 64 * 1024


def connection_timeout(total_seconds: float) -> float:
    """Return a short I/O wait within a positive total budget."""
    if total_seconds <= 0:
        raise ValueError("HTTP time budget must be positive")
    return min(CONNECTION_SECONDS, total_seconds)


def read_before_deadline(response, deadline: float) -> bytes:
    """Read one transport chunk at a time and stop a slow stream after its total deadline."""
    pieces: list[bytes] = []
    while True:
        if time.monotonic() >= deadline:
            raise TimeoutError("HTTP response exceeded its total time budget")
        part = response.read1(READ_CHUNK_BYTES)
        if time.monotonic() >= deadline:
            raise TimeoutError("HTTP response exceeded its total time budget")
        if not part:
            return b"".join(pieces)
        pieces.append(part)
