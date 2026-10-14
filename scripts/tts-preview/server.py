"""Local-only OpenRouter TTS tester, using Python's standard library."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
MODELS = {
    "elevenlabs/eleven-v4-turbo",
    "elevenlabs/eleven-v4",
    "elevenlabs/eleven-flash-v2.5",
    "elevenlabs/eleven-multilingual-v2",
}
ENDPOINT = "https://openrouter.ai/api/v1/audio/speech"


class TtsServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port, api_key, static_root):
        self.api_key = api_key
        self.static_root = Path(static_root)
        self.generation_lock = threading.Lock()
        super().__init__(("127.0.0.1", port), TtsHandler)


class TtsHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        # Request bodies, provider responses and credentials are never logged.
        pass

    def reply(self, status, body, content_type="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def json_reply(self, status, value):
        self.reply(status, json.dumps(value, ensure_ascii=False).encode("utf-8"))

    def local_request(self):
        port = self.server.server_port
        authorities = {f"127.0.0.1:{port}", f"localhost:{port}"}
        if self.headers.get("Host", "") not in authorities:
            return False
        origin = self.headers.get("Origin")
        return origin is None or origin in {"http://" + authority for authority in authorities}

    def do_GET(self):
        if not self.local_request():
            self.json_reply(403, {"error": "Эта страница доступна только локально."})
            return
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/status":
            self.json_reply(200, {"configured": bool(self.server.api_key)})
            return
        assets = {"/": ("index.html", "text/html; charset=utf-8"), "/index.html": ("index.html", "text/html; charset=utf-8"), "/app.js": ("app.js", "application/javascript; charset=utf-8")}
        if path not in assets:
            self.json_reply(404, {"error": "Страница не найдена."})
            return
        name, content_type = assets[path]
        try:
            self.reply(200, (self.server.static_root / name).read_bytes(), content_type)
        except OSError:
            self.json_reply(500, {"error": "Не удалось открыть файлы страницы."})

    def do_POST(self):
        if not self.local_request():
            self.json_reply(403, {"error": "Запрос должен идти с локальной страницы проверки."})
            return
        if self.path != "/api/speech":
            self.json_reply(404, {"error": "Команда не найдена."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 16384:
                raise ValueError()
            payload = json.loads(self.rfile.read(length))
            voice = payload.get("voice_id", "").strip()
            text = payload.get("text", "").strip()
            model = payload.get("model", "elevenlabs/eleven-v4-turbo")
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", voice):
                self.json_reply(400, {"error": "Укажи voice_id или имя стандартного голоса."})
                return
            if not 1 <= len(text) <= 1000:
                self.json_reply(400, {"error": "Введи текст от 1 до 1000 символов."})
                return
            if model not in MODELS:
                self.json_reply(400, {"error": "Выбери одну из доступных моделей ElevenLabs."})
                return
        except (ValueError, AttributeError, TypeError):
            self.json_reply(400, {"error": "Не удалось прочитать voice_id и текст запроса."})
            return
        if not self.server.api_key:
            self.json_reply(503, {"error": "Ключ OpenRouter не настроен. Запусти scripts/tts-preview.ps1 -SetKey."})
            return
        if not self.server.generation_lock.acquire(blocking=False):
            self.json_reply(409, {"error": "Предыдущая озвучка ещё готовится. Подожди её завершения."})
            return
        try:
            body = {"model": model, "input": text, "voice": voice, "response_format": "mp3"}
            request = urllib.request.Request(ENDPOINT, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), headers={"Authorization": "Bearer " + self.server.api_key, "Content-Type": "application/json", "Accept": "audio/mpeg"}, method="POST")
            with urllib.request.urlopen(request, timeout=60) as response:
                audio = response.read(8 * 1024 * 1024 + 1)
                content_type = response.headers.get("Content-Type", "").split(";")[0]
            if content_type not in {"audio/mpeg", "audio/mp3", "application/octet-stream"} or not audio or len(audio) > 8 * 1024 * 1024:
                self.json_reply(502, {"error": "OpenRouter не вернул пригодный MP3. Попробуй другой голос."})
                return
            self.reply(200, audio, "audio/mpeg")
        except urllib.error.HTTPError as error:
            message = f"OpenRouter вернул ошибку {error.code}."
            try:
                details = json.loads(error.read(16384))
                provider_error = details.get("error", {})
                if isinstance(provider_error, dict):
                    provider_error = provider_error.get("message", message)
                if isinstance(provider_error, str):
                    message = provider_error
            except (ValueError, AttributeError):
                pass
            message = message.replace(self.server.api_key, "[ключ скрыт]")
            message = re.sub(r"sk-or-v1-[A-Za-z0-9_-]+", "[ключ скрыт]", message)[:800]
            status = error.code if 400 <= error.code < 500 else 502
            self.json_reply(status, {"error": message})
        except (urllib.error.URLError, TimeoutError, OSError):
            self.json_reply(504, {"error": "Не удалось дождаться OpenRouter. Проверь интернет и попробуй ещё раз."})
        except Exception:
            self.json_reply(500, {"error": "Не удалось выполнить озвучку. Попробуй ещё раз."})
        finally:
            self.server.generation_lock.release()


def create_server(api_key="", port=0, static_root=ROOT):
    return TtsServer(port, api_key, static_root)


def load_key(path, pwsh):
    """Decrypt the current Windows user's DPAPI secret into server memory."""
    try:
        encoded = Path(path).read_text(encoding="utf-8-sig").strip()
        command = "$secret = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); ConvertFrom-SecureString -SecureString $secret -AsPlainText"
        result = subprocess.run([pwsh, "-NoProfile", "-NonInteractive", "-Command", command], input=encoded, text=True, encoding="utf-8", capture_output=True, timeout=10, check=True)
        return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--key-file", default=str(Path(os.environ.get("LOCALAPPDATA", Path.home())) / "ViewerParrots" / "openrouter-key.dpapi"))
    parser.add_argument("--pwsh", default=shutil.which("pwsh") or "pwsh")
    parser.add_argument("--info-file", default=str(ROOT.parents[1] / "artifacts" / "tts-preview" / "server-info.json"))
    args = parser.parse_args()
    server = create_server(load_key(args.key_file, args.pwsh), args.port)
    url = f"http://127.0.0.1:{server.server_port}/"
    info_file = Path(args.info_file)
    info_file.parent.mkdir(parents=True, exist_ok=True)
    info_file.write_text(json.dumps({"url": url, "pid": os.getpid()}), encoding="utf-8")
    print(url, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
