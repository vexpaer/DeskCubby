"""Small helpers for byte-compatible Android ``JSONObject`` payloads.

Android's platform ``org.json`` differs from Python's encoder in two details
that matter to record-sync hashes:

* integral ``Double`` values are emitted without a trailing ``.0``;
* the platform encoder escapes solidus characters as ``\\/``.

Kotlin settings models also store many values as ``Float`` before explicitly
passing ``toDouble()`` to ``JSONObject``.  ``android_float32`` reproduces that
rounding step so semantically identical Android/Web aggregate records converge
to the same bytes instead of alternately uploading forever.
"""
from __future__ import annotations

import json
import math
import struct
from typing import Any


def android_float32(value: Any) -> float:
    """Round a finite numeric value exactly as Kotlin ``Float.toDouble()``."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("Android Float value must be numeric")
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("Android Float value must be finite")
    try:
        return struct.unpack(">f", struct.pack(">f", number))[0]
    except (OverflowError, struct.error) as exc:
        raise ValueError("Android Float value is out of range") from exc


def _normalize_android_numbers(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _normalize_android_numbers(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_normalize_android_numbers(item) for item in value]
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("Android JSON numbers must be finite")
        # JSONObject.doubleToString removes a trailing fractional zero.  The
        # synchronized settings ranges cannot produce a meaningful negative
        # zero, so normalizing it to integer zero is canonical here as well.
        if value.is_integer():
            return int(value)
    return value


def android_json_dumps(value: Any) -> str:
    normalized = _normalize_android_numbers(value)
    encoded = json.dumps(
        normalized,
        ensure_ascii=False,
        separators=(",", ":"),
        allow_nan=False,
    )
    # Python never emits an unquoted structural solidus, so replacing every
    # occurrence is equivalent to Android JSONStringer escaping string data.
    return encoded.replace("/", "\\/")


def android_json_bytes(value: Any) -> bytes:
    return android_json_dumps(value).encode("utf-8")
