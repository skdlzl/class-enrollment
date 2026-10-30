"""Academic eligibility baseline and durable partner-university seat reservation simulator."""
import json
import os
import time
from urllib.parse import urlparse, parse_qs
from partner_store import Store
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

store = Store(os.getenv('PARTNER_DB', '/tmp/partner.sqlite'), int(os.getenv('PARTNER_CAPACITY', '120')))

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def do_GET(self):
        if self.path.startswith('/reservations/'):
            return self.reservation('')
        if self.path.startswith('/eligibility?'):
            time.sleep(int(os.getenv('EXTERNAL_DELAY_MS', '20')) / 1000)
            self.send_response(int(os.getenv('EXTERNAL_STATUS', '204')))
            self.send_header('Content-Length', '0')
            self.end_headers()
        else:
            self.send_response(404)
            self.send_header('Content-Length', '0')
            self.end_headers()
    def do_PUT(self):
        self.reservation(None)
    def reservation(self, action):
        try:
            parsed=urlparse(self.path)
            if not parsed.path.startswith('/reservations/'):
                raise ValueError('PATH')
            id_=parsed.path.removeprefix('/reservations/')
            import uuid
            uuid.UUID(id_)
            q=parse_qs(parsed.query)
            student,course=int(q['student'][0]),int(q['course'][0])
            if student<=0 or course<=0: raise ValueError('ID')
            if action is None: action=q.get('action',[''])[0]
            if action not in ('','reserve','confirm','cancel'): raise ValueError('ACTION')
            time.sleep(int(os.getenv('EXTERNAL_DELAY_MS','20'))/1000)
            state=store.apply(id_,student,course,action)
            # Inject a response timeout AFTER the mutation has committed.
            if action == os.getenv('PARTNER_DELAY_AFTER_ACTION'):
                time.sleep(int(os.getenv('PARTNER_AFTER_DELAY_MS','1000'))/1000)
            self.send_response(200)
            self.send_header('Reservation-State',state)
        except (ValueError, KeyError):
            self.send_response(409)
        self.send_header('Content-Length','0')
        self.end_headers()
    def log_message(self, *args):
        pass

class Server(ThreadingHTTPServer):
    request_queue_size = 512
    daemon_threads = True

if __name__ == '__main__':
    Server(('0.0.0.0', 8090), Handler).serve_forever()
