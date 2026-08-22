"""Unified API errors. Messages never carry bodies, absolute paths or secrets."""
from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def bad_request(msg: str = "Invalid request", code: str = "bad_request") -> ApiError:
    return ApiError(400, code, msg)


def not_found(code: str = "not_found", msg: str = "Not found") -> ApiError:
    return ApiError(404, code, msg)


def conflict(code: str, msg: str) -> ApiError:
    return ApiError(409, code, msg)
