"""T1 tool-loop spike: OpenAI-shape request builder + parser + error map + fake.

Mirrors the Kotlin LlmProvider contract (see LlmProvider.kt in this dir).
One HTTP client shape covers Gemini / DeepSeek / Meta: only base_url,
key and model change (ADR-0004, research 0001).
"""
import json


def build_tools_request(base_url, api_key, model, user_text, tools):
    """Build a POST chat/completions request carrying a tools schema."""
    return {
        "method": "POST",
        "url": base_url.rstrip("/") + "/chat/completions",
        "headers": {
            "Authorization": "Bearer " + api_key,
            "Content-Type": "application/json",
        },
        "json": {
            "model": model,
            "messages": [{"role": "user", "content": user_text}],
            "tools": tools,
            "tool_choice": "auto",
        },
    }


def parse_tool_calls(response):
    """Parse a chat/completions response into tool calls + text content."""
    message = response["choices"][0]["message"]
    calls = []
    for raw in message.get("tool_calls") or []:
        calls.append(
            {
                "id": raw.get("id"),
                "name": raw["function"]["name"],
                "arguments": json.loads(raw["function"]["arguments"] or "{}"),
            }
        )
    return {"tool_calls": calls, "content": message.get("content")}


def map_http_error(status, headers, body):
    """Map an HTTP failure to kind + retry_after + fallback signal.

    429 carries retry-after (retry-after + x-ratelimit-* headers);
    401 means the key is bad (no retry); anything else is a generic error
    that the planner may route to the fallback provider.
    """
    lowered = {str(k).lower(): v for k, v in (headers or {}).items()}
    retry_after = None
    if status == 429:
        try:
            retry_after = int(lowered.get("retry-after", "0"))
        except ValueError:
            retry_after = 0
        return {"kind": "rate_limited", "retry_after": retry_after, "fallback": True}
    if status == 401:
        return {"kind": "auth", "retry_after": None, "fallback": False}
    return {"kind": "error", "retry_after": None, "fallback": True}


class FakeLlmProvider:
    """Canned provider for planner tests: no network, fixed tool call."""

    def plan(self, user_text):
        return {
            "tool_calls": [
                {
                    "id": "call_fake001",
                    "name": "getImportantEmails",
                    "arguments": {"max_results": 5},
                }
            ],
            "content": None,
        }
