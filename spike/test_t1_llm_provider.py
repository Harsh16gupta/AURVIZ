"""T1 tool-loop spike tests — slice 1: request builder.

Seam: build_tools_request(base_url, key, model, text) -> HTTP request dict.
Expected values are independent literals from the research doc, not recomputed.
"""
import unittest

from t1_llm_provider import (
    FakeLlmProvider,
    build_tools_request,
    map_http_error,
    parse_tool_calls,
)

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


class TestBuildToolsRequest(unittest.TestCase):
    def test_gemini_request_shape(self):
        req = build_tools_request(
            base_url="https://generativelanguage.googleapis.com/v1beta/openai",
            api_key="TEST_KEY",
            model="gemini-2.5-flash",
            user_text="Check important emails",
            tools=[DEMO_TOOL],
        )
        self.assertEqual(req["method"], "POST")
        self.assertEqual(
            req["url"],
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        )
        self.assertEqual(req["headers"]["Authorization"], "Bearer TEST_KEY")
        body = req["json"]
        self.assertEqual(body["model"], "gemini-2.5-flash")
        self.assertEqual(body["tool_choice"], "auto")
        self.assertEqual(
            body["tools"][0]["function"]["name"], "getImportantEmails"
        )
        self.assertEqual(
            body["messages"][-1], {"role": "user", "content": "Check important emails"}
        )


class TestParseToolCalls(unittest.TestCase):
    def test_parses_single_tool_call(self):
        response = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {
                                "id": "call_abc123",
                                "type": "function",
                                "function": {
                                    "name": "getImportantEmails",
                                    "arguments": '{"max_results": 5}',
                                },
                            }
                        ],
                    }
                }
            ]
        }
        result = parse_tool_calls(response)
        self.assertEqual(len(result["tool_calls"]), 1)
        call = result["tool_calls"][0]
        self.assertEqual(call["id"], "call_abc123")
        self.assertEqual(call["name"], "getImportantEmails")
        self.assertEqual(call["arguments"], {"max_results": 5})

    def test_text_reply_has_no_tool_calls(self):
        response = {
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "Which inbox should I check?",
                    }
                }
            ]
        }
        result = parse_tool_calls(response)
        self.assertEqual(result["tool_calls"], [])
        self.assertEqual(result["content"], "Which inbox should I check?")


class TestMapHttpError(unittest.TestCase):
    def test_429_surfaces_retry_after_for_fallback(self):
        result = map_http_error(429, {"retry-after": "20"}, "rate limit")
        self.assertTrue(result["fallback"])
        self.assertEqual(result["retry_after"], 20)
        self.assertEqual(result["kind"], "rate_limited")

    def test_401_is_auth_error_without_retry(self):
        result = map_http_error(401, {}, "invalid key")
        self.assertEqual(result["kind"], "auth")
        self.assertIsNone(result["retry_after"])


class TestFakeProvider(unittest.TestCase):
    def test_fake_returns_canned_tool_call(self):
        fake = FakeLlmProvider()
        result = fake.plan("Check important emails")
        self.assertEqual(len(result["tool_calls"]), 1)
        self.assertEqual(result["tool_calls"][0]["name"], "getImportantEmails")
        self.assertEqual(result["tool_calls"][0]["arguments"], {"max_results": 5})


if __name__ == "__main__":
    unittest.main()
