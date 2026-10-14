"""Focused HTTP checks for the local voice tester; no paid requests."""

import http.client
import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

SOURCE = Path(__file__).resolve().parents[1] / "tts-preview" / "server.py"
app = None
if SOURCE.exists():
    spec = importlib.util.spec_from_file_location("tts_preview", SOURCE)
    app = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app)


class AudioReply:
    headers = {"Content-Type": "audio/mpeg"}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, limit=-1):
        return b"ID3example-audio"


class TtsPreviewTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(app, "The local TTS service has not been implemented")
        self.key = "unit-test-private-credential"
        self.server = app.create_server(api_key=self.key, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        if hasattr(self, "server"):
            self.server.shutdown()
            self.server.server_close()
            self.thread.join()

    def request(self, method="POST", path="/api/speech", payload=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port)
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request_headers = {"Content-Type": "application/json"}
        request_headers.update(headers or {})
        connection.request(method, path, body, request_headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def valid_input(self):
        return {"voice_id": "9yzdeviXkFddZ4Oz8Mok", "model": "elevenlabs/eleven-v4-turbo", "text": "Кеша хочет семечек!"}

    def test_custom_voice_and_russian_text_reach_provider_and_mp3_returns(self):
        captured = []

        def provider(request, timeout):
            captured.append((request.full_url, json.loads(request.data), request.get_header("Authorization")))
            return AudioReply()

        with patch.object(app.urllib.request, "urlopen", side_effect=provider):
            status, headers, audio = self.request(payload=self.valid_input())
        self.assertEqual(200, status)
        self.assertEqual("audio/mpeg", headers["Content-Type"])
        self.assertEqual(b"ID3example-audio", audio)
        url, body, authorization = captured[0]
        self.assertEqual("https://openrouter.ai/api/v1/audio/speech", url)
        self.assertEqual("9yzdeviXkFddZ4Oz8Mok", body["voice"])
        self.assertEqual("Кеша хочет семечек!", body["input"])
        self.assertEqual("mp3", body["response_format"])
        self.assertEqual("Bearer " + self.key, authorization)

    def test_browser_cannot_read_the_credential(self):
        status, _, body = self.request("GET", "/api/status")
        self.assertEqual(200, status)
        self.assertTrue(json.loads(body)["configured"])
        self.assertNotIn(self.key.encode(), body)
        status, _, _ = self.request("GET", "/../../openrouter-key.dpapi")
        self.assertEqual(404, status)

    def test_blank_text_is_rejected_before_any_paid_call(self):
        payload = self.valid_input()
        payload["text"] = "  "
        with patch.object(app.urllib.request, "urlopen", side_effect=AssertionError("Must not call provider")):
            status, _, body = self.request(payload=payload)
        self.assertEqual(400, status)
        self.assertIn("error", json.loads(body))

    def test_other_websites_cannot_trigger_paid_generation(self):
        with patch.object(app.urllib.request, "urlopen", side_effect=AssertionError("Must not call provider")):
            status, _, _ = self.request(payload=self.valid_input(), headers={"Origin": "https://unrelated.example"})
        self.assertEqual(403, status)

    def test_provider_error_is_readable_without_leaking_credential(self):
        error_body = json.dumps({"error": {"message": "Voice unavailable; debug " + self.key}}).encode()
        failure = HTTPError("https://openrouter.ai/api/v1/audio/speech", 403, "Forbidden", {}, io.BytesIO(error_body))
        with patch.object(app.urllib.request, "urlopen", side_effect=failure):
            status, _, body = self.request(payload=self.valid_input())
        self.assertEqual(403, status)
        self.assertIn("Voice unavailable", json.loads(body)["error"])
        self.assertNotIn(self.key.encode(), body)

    def test_busy_request_is_not_queued_or_billed(self):
        self.server.generation_lock.acquire()
        try:
            with patch.object(app.urllib.request, "urlopen", side_effect=AssertionError("Must not call provider")):
                status, _, body = self.request(payload=self.valid_input())
            self.assertEqual(409, status)
            self.assertIn("error", json.loads(body))
        finally:
            self.server.generation_lock.release()


if __name__ == "__main__":
    unittest.main()
