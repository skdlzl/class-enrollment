"""Read-only academic service double. Fault injection is configured at process startup."""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_GET(self):
        if self.path.startswith('/eligibility?'):
            time.sleep(int(os.getenv('EXTERNAL_DELAY_MS', '20')) / 1000)
            self.send_response(int(os.getenv('EXTERNAL_STATUS', '204')))
            self.send_header('Content-Length', '0')
            self.end_headers()
        else:
            self.send_response(404)
            self.send_header('Content-Length', '0')
            self.end_headers()
    def log_message(self, *args):
        pass

class Server(ThreadingHTTPServer):
    request_queue_size = 512
    daemon_threads = True

if __name__ == '__main__':
    Server(('0.0.0.0', 8090), Handler).serve_forever()
