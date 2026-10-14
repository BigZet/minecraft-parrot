"""Serve the hat preview and its assets from this directory."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class PreviewHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if urlsplit(self.path).path == "/":
            self.path = "/preview.html"
        super().do_GET()


def main():
    studio_dir = Path(__file__).resolve().parent
    handler = partial(PreviewHandler, directory=str(studio_dir))
    with ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
        print(f"http://127.0.0.1:{server.server_port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
