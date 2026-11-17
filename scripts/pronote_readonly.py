#!/usr/bin/env python3
"""Local QR login experiment. Only timetable reads; no saved credentials."""
import argparse
import datetime as dt
import getpass
import json
import logging
import os
from pathlib import Path
import sys
from urllib.parse import urlparse
import uuid

READ_FUNCTIONS = frozenset({
    'Identification', 'Authentification', 'ParametresUtilisateur',
    'PageInfosPerso', 'PageEmploiDuTemps', 'PageCahierDeTexte', 'Navigation', 'Presence',
})


def readonly_client_class(base):
    class ReadOnlyClient(base):
        def post(self, function_name, *args, **kwargs):
            if function_name not in READ_FUNCTIONS:
                raise PermissionError('This experiment only permits authentication and timetable reads')
            return super().post(function_name, *args, **kwargs)

        def _do_2fa(self, *args, **kwargs):
            # Do not change account security or register a trusted device automatically.
            raise PermissionError('Additional account security requires a separate approved flow')
    return ReadOnlyClient


def normalize_lessons(lessons):
    events = []
    for lesson in lessons:
        events.append({
            'id': f'pronote-{lesson.id}-{lesson.start.isoformat()}',
            'subjectName': lesson.subject.name if lesson.subject else 'Cours',
            'date': lesson.start.date().isoformat(),
            'startTime': lesson.start.strftime('%H:%M'),
            'endTime': lesson.end.strftime('%H:%M'),
            'room': lesson.classroom or '',
            'teacher': lesson.teacher_name or '',
            'isCancelled': bool(lesson.canceled),
        })
    # Remove identical entries without hiding alternative/changed lessons.
    unique = {json.dumps(event, sort_keys=True): event for event in events}
    return sorted(unique.values(), key=lambda event: (event['date'], event['startTime'], event['id']))


def validate_qr(raw):
    if not isinstance(raw, dict) or not all(isinstance(raw.get(k), str) and raw[k] for k in ('url', 'login', 'jeton')):
        raise ValueError('Invalid QR JSON')
    url = urlparse(raw['url'])
    if url.scheme != 'https' or not url.hostname or url.username or url.password:
        raise ValueError('An HTTPS Pronote URL is required')
    if not url.path.endswith(('eleve.html', 'parent.html')):
        raise ValueError('Only student and parent accounts are supported')
    # Fail closed on malformed ciphertext before attempting authentication.
    for key in ('login', 'jeton'):
        decoded = bytes.fromhex(raw[key])
        if not decoded or len(decoded) % 16:
            raise ValueError('Invalid QR ciphertext')
    return raw


def load_qr(path):
    return validate_qr(json.loads(path.read_text()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--qr-json', type=Path, required=True, help='Private local file containing decoded QR JSON')
    parser.add_argument('--output', type=Path, required=True, help='New private local timetable export; never overwritten')
    parser.add_argument('--days', type=int, default=7, choices=range(1, 32), metavar='1..31')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new output path; existing files are not overwritten')
    qr = load_qr(args.qr_json)
    pin = getpass.getpass('Code temporaire du QR (4 chiffres, saisie masquée) : ')
    if len(pin) != 4 or not pin.isascii() or not pin.isdigit():
        parser.error('The confirmation code must contain four digits')
    # The library can log identifiers and response payloads. Keep them out of logs.
    logging.disable(logging.CRITICAL)
    import pronotepy
    base = pronotepy.ParentClient if 'parent.html' in urlparse(qr['url']).path else pronotepy.Client
    client = readonly_client_class(base).qrcode_login(qr, pin, str(uuid.uuid4()))
    if not client.logged_in:
        raise RuntimeError('Authentication failed')
    today = dt.date.today()
    start = dt.datetime.combine(today, dt.time.min)
    end = dt.datetime.combine(today + dt.timedelta(days=args.days - 1), dt.time.max)
    events = normalize_lessons(client.lessons(start, end))
    # Only normalized timetable data leaves the session. No credentials/tokens are exported.
    payload = {'version': 1, 'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'pronoteEvents': events}
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(payload, output, ensure_ascii=False, indent=2)
    print(f'Lecture réussie : {len(events)} créneaux sur {args.days} jours. Export local créé.')


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        # Deliberately exclude exception messages and tracebacks: they can contain secrets.
        print(f'Test interrompu ({type(error).__name__}). Aucun identifiant affiché ou sauvegardé.', file=sys.stderr)
        sys.exit(1)
