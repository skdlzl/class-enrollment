"""Smoke test JMeter 5.6.3 plan execution; stub HTTP, NOT a performance result."""
import csv
import http.server
import pathlib
import subprocess
import sys
import tempfile
import threading

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"items":[]}')
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        self.send_response(201); self.end_headers(); self.wfile.write(b'{"id":1}')
    def log_message(self, *args): pass

servers = [http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler) for port in (8080,8081)]
try:
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    with tempfile.TemporaryDirectory() as directory:
        folder = pathlib.Path(directory)
        requests = folder/'requests.csv'
        requests.write_text('studentId,courseId,port\n10000,1,8080\n10001,1,8081\n')
        result = folder/'result.jtl'
        subprocess.run([sys.argv[1], '-n', '-t', 'jmeter/catalog-mixed-load.jmx', '-l', str(result),
            '-j', str(folder/'jmeter.log'), '-Jusers=2', '-Jramp=1', '-JenrollmentDelay=0',
            '-JreadRps=5', '-JreadSeconds=2', '-Jcsv='+str(requests)], check=True, timeout=90)
        rows = list(csv.DictReader(result.open()))
        assert len([r for r in rows if r['label']=='enrollment'])==2, rows
        assert len([r for r in rows if r['label']=='run-anchor'])==1, rows
        for port in (8080,8081):
            assert any(r['label']==f'catalog-{port}' for r in rows), rows
        assert all(r['responseCode'] in ('200','201') for r in rows), rows
        print('Open-model arrivals, CSV enrollment, and time anchor executed successfully')
finally:
    for server in servers: server.shutdown(); server.server_close()
