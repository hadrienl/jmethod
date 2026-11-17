#!/usr/bin/env python3
"""Loopback-only companion for the local J-Method POC. Sessions stay in RAM."""
import datetime as dt
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import secrets
import threading
import time
from urllib.parse import urlparse
import uuid

import pronotepy
import requests
from pronote_readonly import normalize_lessons, readonly_client_class, validate_qr

COOKIE = 'jmethod_pronote'
TTL = 8 * 60 * 60
SESSIONS = {}
SESSIONS_LOCK = threading.Lock()
ALLOWED_ORIGINS = {'http://localhost:5173', 'http://127.0.0.1:5173', 'http://[::1]:5173'}
ALLOWED_HOSTS = {'localhost:5173', '127.0.0.1:5173', '[::1]:5173', 'localhost:8766', '127.0.0.1:8766'}
logging.disable(logging.CRITICAL)


def validate_remote_url(url):
    parsed = urlparse(url)
    host = parsed.hostname or ''
    if parsed.scheme != 'https' or parsed.port not in (None, 443) or parsed.username or parsed.password:
        raise ValueError('Invalid Pronote host')
    if not host.endswith('.index-education.net'):
        raise ValueError('This POC supports Index Education hosted instances')
    return url


# Keep all network calls bounded and on the hosted Pronote service, including redirects.
_original_request = requests.Session.request
_original_send = requests.Session.send

def bounded_request(self, method, url, **kwargs):
    validate_remote_url(url)
    kwargs.setdefault('timeout', (10, 25))
    return _original_request(self, method, url, **kwargs)

def restricted_send(self, request, **kwargs):
    validate_remote_url(request.url)
    return _original_send(self, request, **kwargs)

requests.Session.request = bounded_request
requests.Session.send = restricted_send


def dispose(entry):
    try:
        entry['client'].communication.session.close()
    except Exception:
        pass


def prune():
    now = time.monotonic()
    with SESSIONS_LOCK:
        expired = [key for key, entry in SESSIONS.items() if now - entry['created'] >= TTL]
        for key in expired:
            dispose(SESSIONS.pop(key))


def snapshot(entry, events=None):
    client = entry['client']
    children = getattr(client, 'children', [])
    selected = getattr(client, '_selected_child', None)
    result = {
        'connected': True,
        'accountName': client.info.name,
        'children': [{'id': str(child.id), 'name': child.name} for child in children],
        'selectedChildId': str(selected.id) if selected else None,
        'lastSync': entry.get('lastSync'),
        'eventCount': entry.get('eventCount', 0),
    }
    if events is not None:
        result['events'] = events
    return result


def fetch_timetable(entry, child_id=None):
    client = entry['client']
    if child_id is not None:
        children = getattr(client, 'children', [])
        child = next((c for c in children if str(c.id) == str(child_id)), None)
        if child is None:
            raise ValueError('Unknown child')
        client.set_child(child)
    today = dt.date.today()
    start = dt.datetime.combine(today, dt.time.min)
    end = dt.datetime.combine(today + dt.timedelta(days=13), dt.time.max)
    events = normalize_lessons(client.lessons(start, end))
    entry['lastSync'] = dt.datetime.now(dt.timezone.utc).isoformat()
    entry['eventCount'] = len(events)
    return snapshot(entry, events)


def connect(qr, pin):
    validate_qr(qr)
    validate_remote_url(qr['url'])
    if not isinstance(pin, str) or len(pin) != 4 or not pin.isascii() or not pin.isdigit():
        raise ValueError('Invalid PIN')
    base = pronotepy.ParentClient if 'parent.html' in urlparse(qr['url']).path else pronotepy.Client
    client = readonly_client_class(base).qrcode_login(qr, pin, str(uuid.uuid4()))
    if not client.logged_in:
        raise PermissionError('Authentication refused')
    entry = {'client': client, 'created': time.monotonic(), 'lock': threading.Lock()}
    try:
        result = fetch_timetable(entry)
    except Exception:
        dispose(entry)
        raise
    return entry, result


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_args):
        pass  # Never log QR payloads, cookies, PINs or account details.

    def respond(self, status, payload, cookie=None):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if cookie is not None:
            value = f'{COOKIE}={cookie}; HttpOnly; SameSite=Strict; Path=/api/pronote; Max-Age={TTL if cookie else 0}'
            self.send_header('Set-Cookie', value)
        self.end_headers()
        self.wfile.write(body)

    def authorized_origin(self):
        return (self.headers.get('Host') in ALLOWED_HOSTS
                and self.headers.get('X-JMethod-Client') == 'local-poc'
                and self.headers.get('Origin') in ALLOWED_ORIGINS | {None}
                and self.headers.get('Sec-Fetch-Site') not in ('cross-site',))

    def session_id(self):
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get('Cookie', ''))
            return cookie[COOKIE].value if COOKIE in cookie else None
        except Exception:
            return None

    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        if not self.authorized_origin():
            self.respond(403, {'error': 'Accès limité à l’application locale.'})
            return
        prune()
        sid = self.session_id()
        with SESSIONS_LOCK:
            entry = SESSIONS.get(sid)
        if self.command == 'GET' and self.path == '/api/pronote/status':
            self.respond(200, snapshot(entry) if entry else {'connected': False})
            return
        if self.command != 'POST' or self.path not in ('/api/pronote/connect', '/api/pronote/sync', '/api/pronote/disconnect'):
            self.respond(404, {'error': 'Opération inconnue.'})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 16384 or 'application/json' not in self.headers.get('Content-Type', ''):
                raise ValueError('Invalid request')
            payload = json.loads(self.rfile.read(size))
            if not isinstance(payload, dict):
                raise ValueError('Invalid request')
            if self.path.endswith('/disconnect'):
                with SESSIONS_LOCK:
                    removed = SESSIONS.pop(sid, None)
                if removed:
                    with removed['lock']:
                        dispose(removed)
                self.respond(200, {'connected': False}, cookie='')
            elif self.path.endswith('/connect'):
                new_entry, result = connect(payload.get('qr'), payload.get('pin'))
                new_sid = secrets.token_urlsafe(32)
                with SESSIONS_LOCK:
                    SESSIONS[new_sid] = new_entry
                    old_entry = SESSIONS.pop(sid, None)
                if old_entry:
                    with old_entry['lock']:
                        dispose(old_entry)
                self.respond(200, result, cookie=new_sid)
            else:
                if not entry:
                    self.respond(401, {'error': 'La session a expiré. Reconnecte Pronote avec un nouveau QR code.'}, cookie='')
                    return
                with entry['lock']:
                    result = fetch_timetable(entry, payload.get('childId'))
                self.respond(200, result)
        except (ValueError, TypeError, KeyError):
            self.respond(400, {'error': 'Vérifie le QR code et son code temporaire à quatre chiffres. Seuls les comptes hébergés sur index-education.net sont pris en charge dans ce POC.'})
        except requests.RequestException:
            self.respond(503, {'error': 'Pronote est injoignable. Réessaie dans un instant ; les cours déjà importés sont conservés.'})
        except Exception as error:
            kind = type(error).__name__
            if kind in ('QRCodeDecryptError', 'CryptoError'):
                self.respond(401, {'error': 'Le QR code est expiré ou son code temporaire est incorrect. Génère un nouveau QR et réessaie.'})
            elif kind in ('MFAError', 'PermissionError'):
                self.respond(409, {'error': 'Pronote demande une vérification de sécurité supplémentaire. Aucun réglage de sécurité n’a été modifié.'})
            else:
                self.respond(502, {'error': 'La connexion ou la lecture Pronote a échoué. Réessaie avec un QR récent ; les cours déjà importés sont conservés.'})


def main():
    server = ThreadingHTTPServer(('127.0.0.1', 8766), Handler)
    print('Connecteur Pronote local prêt sur 127.0.0.1:8766 (lecture seule, sessions en mémoire).', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        for entry in list(SESSIONS.values()):
            dispose(entry)
        SESSIONS.clear()


if __name__ == '__main__':
    main()
