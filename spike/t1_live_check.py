"""T1 live check: one OpenAI-shape tools call against Gemini.

Reads keys from local.properties (git-ignored). Never prints key values.
Exit 0 with a documented outcome even when keys are missing/invalid:
the acceptance bar is "verified live OR documented 401/key-missing".
"""
import json
import sys
import urllib.request
from pathlib import Path

from t1_llm_provider import build_tools_request, map_http_error, parse_tool_calls

DEMO_TOOL = {
    "type": "function",
    "function": {
        "name": "getImportantEmails",
        "description": "Fetch unread important emails.",
        "parameters": {
            "type": "object",
            "properties": {"max_results": {"type": "integer"}},
            "required": ["max_results"],
        },
    },
}


def read_props(path):
    props = {}
    text = Path(path).read_text()
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def try_provider(name, base_url, api_key, model):
    if not api_key:
        return f"{name}: SKIP (no key in local.properties)"
    req = build_tools_request(
        base_url=base_url,
        api_key=api_key,
        model=model,
        user_text="Show my 5 most important emails now. Call getImportantEmails with max_results set to 5.",
        tools=[DEMO_TOOL],
    )
    data = json.dumps(req["json"]).encode()
    http_req = urllib.request.Request(
        req["url"], data=data, headers=req["headers"], method="POST"
    )
    try:
        with urllib.request.urlopen(http_req, timeout=30) as resp:
            payload = json.loads(resp.read().decode())
    except Exception as exc:  # HTTPError carries status + headers
        status = getattr(exc, "code", None)
        headers = dict(getattr(exc, "headers", {}) or {})
        if status:
            mapped = map_http_error(status, headers, str(exc))
            return (
                f"{name}: HTTP {status} -> {mapped['kind']} "
                f"(retry_after={mapped['retry_after']}, "
                f"fallback={mapped['fallback']})"
            )
        return f"{name}: NETWORK ERROR ({type(exc).__name__})"
    try:
        result = parse_tool_calls(payload)
    except (KeyError, TypeError, ValueError) as exc:
        return f"{name}: UNPARSEABLE response ({type(exc).__name__})"
    calls = result["tool_calls"]
    if calls:
        names = [c["name"] for c in calls]
        return f"{name}: LIVE tool_calls round-trip OK -> {names}"
    content = (result["content"] or "")[:120]
    return f"{name}: live call OK but no tool_calls (text: {content!r})"


def main():
    props = read_props(Path(__file__).resolve().parent.parent / "local.properties")
    print(
        try_provider(
            "gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            props.get("GEMINI_API_KEY", ""),
            props.get("GEMINI_MODEL", "gemini-2.5-flash"),
        )
    )


if __name__ == "__main__":
    sys.exit(main())
