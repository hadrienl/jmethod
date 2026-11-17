#!/usr/bin/env python3
"""Generate native/shared/notebook-vectors.json (milestone 5, read-only notebook preview).

Run with the project venv (pronotepy 2.15.7 is required):

    .venv/bin/python native/shared/notebook-vectors.py           # write the JSON
    .venv/bin/python native/shared/notebook-vectors.py --check   # compare, exit 1 if stale

Everything is synthetic: host school.index-education.net, fabricated responses,
fixed key/IV, fixed "today". No network access, no randomness, no clock.

The script executes the real code of the repository and of pronotepy:
- scripts/pronote_notebook.py (fetch_notebook, attachments_metadata) for web parity;
- pronotepy Client.post / ParentClient.post / Client.homework / get_week for requests;
- pronotepy LessonContent, Homework, Attachment, Util for parsing and attachment URLs.
The native target model (safe text, links, grouping by session) is produced by the
reference functions below, which are the executable form of notebook-reference.md.
"""
import argparse
import copy
import datetime as real_dt
import hashlib
import html.entities
import json
import logging
from pathlib import Path
import re
import sys
from types import SimpleNamespace as Obj
from unittest.mock import patch

ROOT_DIR = Path(__file__).resolve().parents[2]
OUTPUT = Path(__file__).resolve().with_name('notebook-vectors.json')
sys.path.insert(0, str(ROOT_DIR / 'scripts'))

logging.disable(logging.CRITICAL)
import pronotepy  # noqa: E402
from pronotepy import dataClasses  # noqa: E402
from pronotepy.pronoteAPI import _Encryption  # noqa: E402
from Crypto.Cipher import AES  # noqa: E402
from Crypto.Util import Padding  # noqa: E402
import pronote_notebook as web_notebook  # noqa: E402

# ---------------------------------------------------------------------------
# Synthetic environment
# ---------------------------------------------------------------------------
HOST = 'school.index-education.net'
PRONOTE_ROOT = f'https://{HOST}/pronote'
PAGE_URL = f'{PRONOTE_ROOT}/mobile.eleve.html'
SESSION_H = 424242
SESSION_KEY_HEX = '0f0e0d0c0b0a09080706050403020100'
SESSION_IV_HEX = '1ac1ef01e96caf1be0d329331a4fc2a8'  # = md5TemporaryIVHex of protocol-vectors.json
TODAY = real_dt.date(2026, 11, 18)
PREMIER_LUNDI = real_dt.date(2026, 8, 31)
WINDOW_BEFORE, WINDOW_AFTER = 14, 13
MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
AUTHORIZED_ONGLETS = [7, 16, 49, 88, 89]

# ---------------------------------------------------------------------------
# Native reference rules (executable specification of notebook-reference.md)
# ---------------------------------------------------------------------------
RAW_TEXT_ELEMENTS = ['head', 'iframe', 'math', 'noembed', 'noframes', 'noscript', 'object',
                     'script', 'style', 'svg', 'template', 'textarea', 'title', 'xmp']
BLOCK_ELEMENTS = ['address', 'article', 'aside', 'blockquote', 'dd', 'div', 'dl', 'dt', 'figcaption',
                  'figure', 'footer', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hr', 'li', 'main',
                  'nav', 'ol', 'p', 'pre', 'section', 'table', 'tbody', 'tfoot', 'thead', 'tr', 'ul']
CELL_ELEMENTS = ['td', 'th']
WHITESPACE = '\t\n\x0b\x0c\r \u00a0'
BULLET = '\u2022 '
ENTITY_TABLE = dict(sorted({**html.entities.name2codepoint, 'apos': 39}.items()))
ENTITY_RE = re.compile(r'&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]{0,31}));')


def ascii_lower(text):
    return ''.join(chr(ord(c) + 32) if 'A' <= c <= 'Z' else c for c in text)


def is_ascii_letter(c):
    return ('a' <= c <= 'z') or ('A' <= c <= 'Z')


def is_name_char(c):
    return is_ascii_letter(c) or '0' <= c <= '9' or c in '-:'


def is_removed(cp):
    return (cp <= 0x1F or 0x7F <= cp <= 0x9F or 0x202A <= cp <= 0x202E
            or 0x2066 <= cp <= 0x2069 or cp == 0xFEFF)


def decode_entities(text):
    def repl(match):
        dec, hexa, name = match.groups()
        if name is not None:
            cp = ENTITY_TABLE.get(name)
            return match.group(0) if cp is None else chr(cp)
        cp = int(dec) if dec is not None else int(hexa, 16)
        if cp == 0 or 0xD800 <= cp <= 0xDFFF or cp > 0x10FFFF:
            return '\ufffd'
        return chr(cp)
    return ENTITY_RE.sub(repl, text)


def clean_chars(text):
    """Whitespace -> U+0020, controls/bidi overrides/BOM removed."""
    out = []
    for c in text:
        if c in WHITESPACE:
            out.append(' ')
        elif not is_removed(ord(c)):
            out.append(c)
    return ''.join(out)


def clean_plain(value):
    """Plain (non-HTML) Pronote string: title, subject, category, attachment name."""
    if not isinstance(value, str):
        return ''
    return re.sub(' +', ' ', clean_chars(value)).strip(' ')


def validate_http_url(value):
    """Return the trimmed URL if it is an acceptable http(s) link, else None."""
    if not isinstance(value, str):
        return None
    url = value.strip('\t\n\x0c\r ')
    lower = ascii_lower(url)
    if lower.startswith('http://'):
        rest = url[7:]
    elif lower.startswith('https://'):
        rest = url[8:]
    else:
        return None
    if any(ord(c) <= 0x20 or 0x7F <= ord(c) <= 0x9F for c in url):
        return None
    end = len(rest)
    for delim in '/?#':
        pos = rest.find(delim)
        if pos >= 0:
            end = min(end, pos)
    authority = rest[:end]
    if not authority or '@' in authority or '\\' in authority:
        return None
    match = re.fullmatch(r'(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?', authority)
    if not match or match.group(1).strip('.') == '':
        return None
    if match.group(2) is not None and int(match.group(2)) > 65535:
        return None
    return url


def parse_attributes(src):
    """Attributes of a start tag (text between the tag name and '>')."""
    attrs = []
    i, n = 0, len(src)
    while i < n:
        while i < n and (src[i] in '\t\n\x0c\r /'):
            i += 1
        if i >= n:
            break
        start = i
        while i < n and src[i] not in '\t\n\x0c\r />=':
            i += 1
        name = ascii_lower(src[start:i])
        while i < n and src[i] in '\t\n\x0c\r ':
            i += 1
        value = ''
        if i < n and src[i] == '=':
            i += 1
            while i < n and src[i] in '\t\n\x0c\r ':
                i += 1
            if i < n and src[i] in '"\'':
                quote = src[i]
                close = src.find(quote, i + 1)
                close = n if close < 0 else close
                value = src[i + 1:close]
                i = close + 1
            else:
                start = i
                while i < n and src[i] not in '\t\n\x0c\r >':
                    i += 1
                value = src[start:i]
        if name:
            attrs.append((name, value))
    return attrs


def find_tag_end(s, k):
    """Index of the '>' closing a tag; quotes only count right after '='. -1 if unterminated."""
    n = len(s)
    while k < n:
        ch = s[k]
        if ch == '>':
            return k
        if ch == '=':
            k += 1
            while k < n and s[k] in '\t\n\x0c\r ':
                k += 1
            if k < n and s[k] in '"\'':
                close = s.find(s[k], k + 1)
                if close < 0:
                    return -1
                k = close + 1
            continue
        k += 1
    return -1


def find_raw_text_end(s, start, name):
    lower = ascii_lower(s)
    needle = '</' + name
    pos = start
    while True:
        pos = lower.find(needle, pos)
        if pos < 0:
            return -1
        after = pos + len(needle)
        if after >= len(s) or s[after] in '\t\n\x0c\r />':
            gt = s.find('>', after)
            return -1 if gt < 0 else gt + 1
        pos = after


def tokenize(s):
    tokens, links = [], []
    anchor = None

    def close_anchor():
        nonlocal anchor
        if anchor is not None:
            url = validate_http_url(decode_entities(anchor['href'])) if anchor['href'] is not None else None
            if url is not None:
                label = render(tokens[anchor['start']:], inline=True)
                links.append({'label': label or url, 'url': url})
            anchor = None

    i, n, text_start = 0, len(s), 0

    def flush(end):
        if end > text_start:
            tokens.append(('text', s[text_start:end]))

    while i < n:
        if s[i] != '<':
            i += 1
            continue
        if s.startswith('<!--', i):
            flush(i)
            end = s.find('-->', i + 4)
            i = n if end < 0 else end + 3
            text_start = i
            continue
        nxt = s[i + 1] if i + 1 < n else ''
        if nxt in ('!', '?'):
            flush(i)
            end = s.find('>', i + 2)
            i = n if end < 0 else end + 1
            text_start = i
            continue
        is_end = nxt == '/'
        name_start = i + 2 if is_end else i + 1
        if name_start >= n or not is_ascii_letter(s[name_start]):
            i += 1  # literal '<'
            continue
        flush(i)
        j = name_start
        while j < n and is_name_char(s[j]):
            j += 1
        name = ascii_lower(s[name_start:j])
        k = find_tag_end(s, j)
        if k < 0:  # unterminated tag: drop the rest
            i = text_start = n
            break
        attr_src = s[j:k]
        i = text_start = k + 1
        if name == 'br':
            tokens.append(('hard',))
        elif name in BLOCK_ELEMENTS:
            tokens.append(('soft',))
            if name == 'li' and not is_end:
                tokens.append(('bullet',))
        elif name in CELL_ELEMENTS:
            tokens.append(('space',))
        elif name == 'a':
            close_anchor()
            if not is_end:
                href = next((v for a, v in parse_attributes(attr_src) if a == 'href'), None)
                anchor = {'href': href, 'start': len(tokens)}
        if not is_end and name in RAW_TEXT_ELEMENTS:
            end = find_raw_text_end(s, i, name)
            i = text_start = n if end < 0 else end
    flush(n)
    close_anchor()
    return tokens, links


def render(tokens, inline=False):
    out = ''
    for tok in tokens:
        kind = tok[0]
        if kind == 'text':
            out += clean_chars(decode_entities(tok[1]))
        elif kind == 'space':
            out += ' '
        elif kind == 'bullet':
            out += BULLET
        elif kind == 'hard':
            out += '\n'
        elif kind == 'soft':
            trimmed = out.rstrip(' ')
            if trimmed and not trimmed.endswith('\n'):
                out += '\n'
    if inline:
        out = out.replace('\n', ' ')
    out = re.sub(' +', ' ', out)
    out = '\n'.join(line.strip(' ') for line in out.split('\n'))
    out = re.sub('\n{3,}', '\n\n', out)
    return out.strip('\n ')


def safe_text(value):
    """Pronote HTML (descriptif.V) -> {'text', 'links'}; nothing executable survives."""
    if not isinstance(value, str) or not value:
        return {'text': '', 'links': []}
    tokens, links = tokenize(value)
    return {'text': render(tokens), 'links': links}


DATE_PATTERNS = [
    (re.compile(r'(\d{2})/(\d{2})/(\d{4})'), False),
    (re.compile(r'(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})'), False),
    (re.compile(r'(\d{2})/(\d{2})/(\d{2})'), True),
    (re.compile(r'(\d{2})/(\d{2})/(\d{2}) (\d{2})h(\d{2})'), True),
]


def parse_local_date(value):
    """Exploitable Pronote date -> ISO calendar date string (school wall time), else None."""
    if not isinstance(value, str):
        return None
    for pattern, short_year in DATE_PATTERNS:
        match = pattern.fullmatch(value)
        if not match:
            continue
        parts = [int(p) for p in match.groups()]
        day, month, year = parts[0], parts[1], parts[2] + (2000 if short_year else 0)
        hour, minute, second = (parts[3:] + [0, 0, 0])[:3]
        if hour > 23 or minute > 59 or second > 59:
            return None
        try:
            return real_dt.date(year, month, day).isoformat()
        except ValueError:
            return None
    return None


def week_number(premier_lundi, day):
    """pronotepy ClientBase.get_week: 1 + trunc(days / 7) (truncation toward zero)."""
    return 1 + int((day - premier_lundi).days / 7)


def native_window(today, premier_lundi):
    first = today - real_dt.timedelta(days=WINDOW_BEFORE)
    last = today + real_dt.timedelta(days=WINDOW_AFTER)
    first_week = max(1, week_number(premier_lundi, first))
    last_week = max(first_week, week_number(premier_lundi, last))
    return {
        'today': today.isoformat(), 'from': first.isoformat(), 'to': last.isoformat(),
        'days': WINDOW_BEFORE + 1 + WINDOW_AFTER,
        'firstWeek': first_week, 'lastWeek': last_week,
        'domaine': {'_T': 8, 'V': f'[{first_week}..{last_week}]'},
    }


def native_attachment(raw):
    if not isinstance(raw, dict):
        return None
    ident, kind = raw.get('N'), raw.get('G')
    if not isinstance(ident, str) or not ident or type(kind) is not int:
        return None
    name = clean_plain(raw.get('L'))
    if kind == 0:
        url = validate_http_url(raw.get('url') if isinstance(raw.get('url'), str) else raw.get('L'))
        if url is None:
            return None
        return {'kind': 'link', 'id': ident, 'name': name or 'Lien', 'url': url}
    if kind == 1:
        return {'kind': 'file', 'id': ident, 'name': name or 'Document'}
    return None


def attachments_of(container):
    raw = dig(container, 'ListePieceJointe', 'V')
    items = raw if isinstance(raw, list) else []
    return [a for a in (native_attachment(item) for item in items) if a is not None]


def dig(value, *path):
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def native_model(contents_data, homework_data, window, timetable_subjects):
    first, last = window['from'], window['to']
    skipped = 0
    sessions = []
    for row in dig(contents_data, 'ListeCahierDeTextes', 'V') or []:
        date = parse_local_date(dig(row, 'Date', 'V'))
        if date is None:
            skipped += 1
            continue
        if not first <= date <= last:
            continue
        lesson_id = dig(row, 'cours', 'V', 'N')
        lesson_id = lesson_id if isinstance(lesson_id, str) else ''
        subject = (clean_plain(dig(row, 'Matiere', 'V', 'L'))
                   or clean_plain(timetable_subjects.get(lesson_id)) or 'Cours')
        contents = []
        for raw in dig(row, 'listeContenus', 'V') or []:
            title = clean_plain(dig(raw, 'L'))
            body = safe_text(dig(raw, 'descriptif', 'V'))
            files = attachments_of(raw)
            if not (title or body['text'] or files):
                continue
            contents.append({'title': title, 'description': body['text'], 'links': body['links'],
                             'category': clean_plain(dig(raw, 'categorie', 'V', 'L')), 'attachments': files})
        if not contents:
            continue
        row_id = row.get('N') if isinstance(row.get('N'), str) and row.get('N') else f'cours:{lesson_id}@{date}'
        sessions.append({'id': row_id, 'lessonId': lesson_id, 'date': date, 'subject': subject, 'contents': contents})
    homework = []
    for item in dig(homework_data, 'ListeTravauxAFaire', 'V') or []:
        due = parse_local_date(dig(item, 'PourLe', 'V'))
        if due is None:
            skipped += 1
            continue
        if not first <= due <= last:
            continue
        body = safe_text(dig(item, 'descriptif', 'V'))
        homework.append({'id': str(item.get('N', '')), 'dueDate': due,
                         'subject': clean_plain(dig(item, 'Matiere', 'V', 'L')) or 'Cours',
                         'description': body['text'], 'links': body['links'],
                         'done': item.get('TAFFait') is True, 'attachments': attachments_of(item)})
    sessions.sort(key=lambda s: s['date'], reverse=True)  # stable: response order inside a day
    homework.sort(key=lambda h: h['dueDate'])              # stable: response order inside a day
    attachment_count = (sum(len(c['attachments']) for s in sessions for c in s['contents'])
                        + sum(len(h['attachments']) for h in homework))
    return {'from': first, 'to': last, 'sessions': sessions, 'homework': homework,
            'skippedEntries': skipped, 'attachmentCount': attachment_count}


def json_string(value):
    return json.dumps(value)


def attachment_plaintext(ident):
    if not re.fullmatch(r'[!#-\[\]-~]+', ident or ''):
        raise ValueError('unsafe attachment id')
    return '{"N":' + json_string(ident) + ',"Actif":true}'


def encode_uri_component(text):
    safe = set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*\'()')
    return ''.join(c if c in safe else ''.join(f'%{b:02X}' for b in c.encode('utf-8')) for c in text)


def save_as_name(name):
    base = re.split(r'[/\\]', name)[-1]
    base = ''.join('_' if (c in '<>:"|?*' or is_removed(ord(c))) else c for c in base)
    base = base.strip(' .')
    return base or 'document'


def aes_cbc(key_hex, iv_hex, plaintext):
    return AES.new(bytes.fromhex(key_hex), AES.MODE_CBC, bytes.fromhex(iv_hex)).encrypt(plaintext)


# ---------------------------------------------------------------------------
# Stubs that let the real repository / pronotepy code run offline
# ---------------------------------------------------------------------------
class StubCommunication:
    def __init__(self, responses):
        self.root_site = PRONOTE_ROOT
        self.encryption = _Encryption()
        self.encryption.aes_key = bytes.fromhex(SESSION_KEY_HEX)
        self.encryption.aes_iv = bytes.fromhex(SESSION_IV_HEX)
        self.authorized_onglets = list(AUTHORIZED_ONGLETS)
        self.responses = responses
        self.calls = []

    def post(self, function_name, data):
        # Same guard as pronotepy _Communication.post.
        if 'Signature' in data and data['Signature'].get('onglet') not in self.authorized_onglets:
            raise PermissionError('onglet not authorized')
        self.calls.append({'id': function_name, 'inner': copy.deepcopy(data)})
        return {'dataSec': {'data': copy.deepcopy(self.responses[(function_name, data['Signature']['onglet'])])}}


class StubClient:
    post = pronotepy.Client.post
    homework = pronotepy.Client.homework
    get_week = pronotepy.Client.get_week

    def __init__(self, responses, lessons=()):
        self.communication = StubCommunication(responses)
        self.attributes = {'a': 3, 'h': SESSION_H}
        self.start_day = PREMIER_LUNDI
        self._refreshing = False
        self._lessons = list(lessons)

    def lessons(self, *_args):
        return list(self._lessons)


class StubParentClient(StubClient):
    post = pronotepy.ParentClient.post

    def __init__(self, responses, child_id):
        super().__init__(responses)
        self._selected_child = Obj(id=child_id)


class FixedDate(real_dt.date):
    @classmethod
    def today(cls):
        return cls(TODAY.year, TODAY.month, TODAY.day)


class FixedDateTime(real_dt.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(TODAY.year, TODAY.month, TODAY.day, 12, 0, 0, tzinfo=tz)


def frozen_dt():
    return Obj(date=FixedDate, datetime=FixedDateTime, timedelta=real_dt.timedelta,
               time=real_dt.time, timezone=real_dt.timezone)


# ---------------------------------------------------------------------------
# Synthetic fixtures
# ---------------------------------------------------------------------------
def subject(ident, label):
    return {'N': ident, 'L': label}


def content(ident, title, html_text, category, attachments):
    raw = {'N': ident, 'L': title, 'descriptif': {'_T': 21, 'V': html_text},
           'ListePieceJointe': {'_T': 24, 'V': attachments}}
    if category is not None:
        raw['categorie'] = {'_T': 24, 'V': {'N': f'cat-{category}', 'L': category, 'G': 1}}
    return raw


def row(ident, date, lesson_id, matiere, contents):
    raw = {'N': ident, 'cours': {'_T': 24, 'V': {'N': lesson_id}}}
    if date is not None:
        raw['Date'] = {'_T': 7, 'V': date}
    if matiere is not None:
        raw['Matiere'] = {'_T': 24, 'V': matiere}
    raw['listeContenus'] = {'_T': 24, 'V': contents}
    return raw


def homework_item(ident, due, matiere, html_text, done, attachments, color='#B4D2E6', given='16/11/2026'):
    raw = {'N': ident, 'descriptif': {'_T': 21, 'V': html_text}}
    if due is not None:
        raw['PourLe'] = {'_T': 7, 'V': due}
    raw['DonneLe'] = {'_T': 7, 'V': given}
    raw['TAFFait'] = done
    if matiere is not None:
        raw['Matiere'] = {'_T': 24, 'V': matiere}
    if color is not None:
        raw['CouleurFond'] = color
    if attachments is not None:
        raw['ListePieceJointe'] = {'_T': 24, 'V': attachments}
    return raw


MATHS = subject('mat-maths', 'MATHÉMATIQUES')
HISTGEO = subject('mat-hg', 'HISTOIRE-GÉOGRAPHIE')
ANGLAIS = subject('mat-ang', 'ANGLAIS LV1')
FRANCAIS = subject('mat-fr', 'FRANÇAIS')

CONTENTS_DATA = {'ListeCahierDeTextes': {'_T': 24, 'V': [
    row('cdt-01', '16/11/2026 08:30:00', 'cours-maths', MATHS, [
        content('cnt-01a', 'Fonctions affines',
                '<div>Définition, coefficient directeur et ordonnée à l&#39;origine.</div>'
                '<ul><li>Représentation graphique</li><li>Lecture du coefficient&nbsp;<i>a</i></li></ul>',
                'Cours', [{'N': 'pj-file-01', 'L': 'Fiche d’exercices (1) – été.pdf', 'G': 1}]),
        content('cnt-01b', 'Exercices', '<p>Ex. 12 &amp; 13 p.&nbsp;45</p>', 'Exercices',
                [{'N': 'pj-link-01', 'L': 'Vidéo du cours', 'G': 0,
                  'url': 'https://video.exemple.fr/watch?v=abc&t=10'}]),
    ]),
    row('cdt-02', '13/11/2026 10:00:00', 'cours-hg', HISTGEO, [
        content('cnt-02a', 'La Révolution française',
                '<p onclick="alert(1)">Lire le document&nbsp;2.</p><script>alert("cdt")</script>'
                '<p><a href="javascript:alert(2)">Ressource piégée</a> puis '
                '<a href="https://manuel.exemple.fr/chapitre-3?page=12&amp;ligne=4">manuel en ligne</a>.</p>'
                '<img src="https://tracker.exemple.org/pixel.png" onerror="alert(3)">',
                None, [{'N': 'pj-link-js', 'L': 'Ouvrir', 'G': 0, 'url': 'javascript:alert(4)'},
                       {'N': 'pj-link-data', 'L': 'Données', 'G': 0, 'url': 'data:text/html,<b>x</b>'}]),
    ]),
    row('cdt-03', None, 'cours-fr', FRANCAIS, [content('cnt-03a', 'Sans date', '<p>Texte</p>', None, [])]),
    row('cdt-04', '31/02/2026 08:00:00', 'cours-fr', FRANCAIS,
        [content('cnt-04a', 'Date impossible', '<p>Texte</p>', None, [])]),
    row('cdt-05', '01/10/2026 08:00:00', 'cours-fr', FRANCAIS,
        [content('cnt-05a', 'Trop ancien', '<p>Hors fenêtre</p>', None, [])]),
    row('cdt-06', '20/11/2026', 'cours-svt', None,
        [content('cnt-06a', '', '', None, [{'N': 'pj-file-02', 'G': 1}])]),
    row('cdt-07', '18/11/2026 14:00:00', 'cours-ang', ANGLAIS,
        [content('cnt-07a', '', '<p>&nbsp;</p>', None, [])]),
    row('cdt-08', '09/11/2026 08:30:00', 'cours-maths', MATHS,
        [content('cnt-08a', 'Équations', 'R&eacute;solution d&rsquo;&eacute;quations &laquo;&nbsp;produit nul&nbsp;&raquo;'
                 '<br>Cas&nbsp;: &lt;script&gt; reste du texte &#x2192; &#8364;', None, [])]),
    row('cdt-09', '04/11/2026 08:00:00', 'cours-fr', FRANCAIS,
        [content('cnt-09a', 'Premier jour de la fenêtre', '<p>Inclus</p>', 'Cours',
                 [{'N': 'pj-unknown', 'L': 'Type inconnu', 'G': 2}])]),
    row('cdt-10', '02/12/2026 08:00:00', 'cours-fr', FRANCAIS,
        [content('cnt-10a', 'Lendemain de la fenêtre', '<p>Exclu</p>', None, [])]),
]}}

HOMEWORK_DATA = {'ListeTravauxAFaire': {'_T': 24, 'V': [
    homework_item('taf-01', '19/11/2026', MATHS, '<p>Exercices 12 à 15 p.&nbsp;45</p>', False,
                  [{'N': 'pj-file-03', 'L': 'Énoncé.pdf', 'G': 1}]),
    homework_item('taf-02', '25/11/2026', ANGLAIS,
                  '<div>Écouter le <a href="https://podcast.exemple.fr/episode-7" onclick="track()">podcast</a>'
                  '<br/>et noter 5&nbsp;mots.</div>', True,
                  [{'N': 'pj-link-02', 'L': 'https://podcast.exemple.fr/episode-7', 'G': 0}]),
    homework_item('taf-03', '10/11/2026', HISTGEO, '<p>Apprendre la frise</p>', True, []),
    homework_item('taf-04', '15/12/2026', MATHS, '<p>Hors fenêtre</p>', False, []),
    homework_item('taf-05', 'pas de date', MATHS, '<p>Date illisible</p>', False, []),
    homework_item('taf-06', '19/11/2026', FRANCAIS, '<p>Relire la scène&nbsp;3</p>', False, []),
    homework_item('taf-07', '20/11/2026', None, '<p>Sans matière ni couleur</p>', False, None, color=None),
]}}

TIMETABLE_SUBJECTS = {'cours-svt': 'SVT'}

# Items the web connector cannot share with the native model (documented deviations).
WEB_PARITY_EXCLUDED = {
    'rows': ['cdt-07'],      # web keeps a description made of U+00A0 only; native drops it
    'attachments': ['pj-unknown'],  # web treats G=2 as a file; native ignores unknown G
    'homework': ['taf-05', 'taf-07'],  # pronotepy raises ParsingError (whole call fails)
}

HTML_CASES = [
    ('accents_utf8', '<p>Révision : fonctions affines, côté «&nbsp;élève&nbsp;» — 𝑥 ∈ ℝ</p>'),
    ('blocks_and_breaks', '<div>Ligne 1<br/>Ligne 2</div>\n<div>Ligne 3</div><p></p><p>Ligne 4<br><br>Ligne 6</p>'),
    ('named_numeric_entities', 'R&eacute;sum&eacute; &amp; synth&egrave;se &lt;important&gt; &quot;cit&eacute;&quot; '
                               '&apos;ok&apos; &#39;a&#39; &#x20AC; 5&nbsp;&euro; &OElig;uvre'),
    ('unknown_or_unterminated_entities', 'A &unknown; B &amp C &ampx; D &#; E &#xZZ;'),
    ('invalid_numeric_entities', 'a&#0;b&#xD800;c&#1114112;d&#128;e&#7;f'),
    ('script_removed', '<p>Avant</p><script type="text/javascript">alert("x");</script><p>Après</p>'),
    ('script_uppercase_unterminated', '<p>Début</p><SCRIPT>alert(1)</scriptx> encore caché'),
    ('style_comment_removed', '<style>p{color:red}</style><!-- note privée --><p>Visible</p>'),
    ('event_handlers_and_images', '<p onclick="alert(1)" style="color:red">Clique</p>'
                                  '<img src="https://tracker.exemple.org/x.png" onerror="alert(2)">'),
    ('links_filtered', '<a href="javascript:alert(1)">piège</a> <a href="https://exemple.fr/cours?id=1&amp;p=2">'
                       'Cours <b>en ligne</b></a> <a href="HTTP://Exemple.fr/">majuscules</a> '
                       '<a href="data:text/html,x">data</a> <a href="https://user:pw@exemple.fr/">identifiants</a> '
                       '<a href=//exemple.fr>relatif</a> <a href=" https://espace.exemple.fr ">espaces</a> '
                       '<a href="jav&#x09;ascript:alert(1)">tab</a> <a href="https://exemple.fr"></a>'),
    ('multiline_tag', '<a\nhref="javascript:alert(1)"\nonclick="x()">Lien</a> et\n<b\nclass="g">gras</b>'),
    ('quoted_gt_in_attribute', '<a href="https://exemple.fr/?q=a>b" title=\'x>y\'>Lien</a> fin'),
    ('escaped_markup_is_text', '&lt;script&gt;alert(1)&lt;/script&gt; &lt;a href=&quot;javascript:x&quot;&gt;'),
    ('literal_angle_brackets', '3 < 5 et 5 > 3, <3, a<b'),
    ('svg_iframe_removed', '<svg><script>alert(1)</script><text>T</text></svg>OK'
                           '<iframe src="https://exemple.fr">fallback</iframe> fin'),
    ('list_and_table', '<ul><li>Exercice 1</li><li> Exercice 2 p.&nbsp;45 </li></ul>'
                       '<table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>'),
    ('controls_and_bidi', 'a&#8238;b\u0007c\u202ed\ufeffe'),
    ('whitespace_only', '<p>&nbsp;</p><p> </p>\n\t'),
    ('unterminated_tag', 'Texte <a href="https://exemple.fr" visible'),
    ('nested_anchor_closes_previous', '<a href="https://a.exemple.fr">un <a href="https://b.exemple.fr">deux</a> trois'),
    ('empty', ''),
]

LINK_URL_CASES = [
    'https://exemple.fr/cours', 'http://exemple.fr', 'HTTPS://Exemple.FR/Page', 'https://exemple.fr:8443/x',
    'https://[2001:db8::1]/x', 'javascript:alert(1)', 'data:text/html,x', 'file:///etc/passwd',
    'https://user:pw@exemple.fr/', 'https://exemple.fr\\@evil.exemple/', '//exemple.fr/x', 'https://',
    'https:///chemin', 'ftp://exemple.fr/f', 'https://exemple.fr:99999/', 'https://exemple .fr/',
    ' https://exemple.fr/ ', 'https://exemple.fr/a b', 'https://éducation.exemple.fr/',
]

DATE_CASES = ['18/11/2026 08:30:00', '18/11/2026', '18/11/26 08h30', '18/11/26', '31/02/2026',
              '18/11/2026 24:00:00', '2026-11-18', '18/11', '0830', '1/11/2026', '', None]

WEEK_TODAYS = [real_dt.date(2026, 9, 2), real_dt.date(2026, 9, 14), TODAY, real_dt.date(2026, 11, 23),
               real_dt.date(2027, 1, 4)]

DOWNLOAD_CASES = [
    ('scenario_lesson_file', 'pj-file-01', 'Fiche d’exercices (1) – été.pdf'),
    ('scenario_unnamed_file', 'pj-file-02', ''),
    ('scenario_homework_file', 'pj-file-03', 'Énoncé.pdf'),
    ('reserved_characters', '27#AbCdEfGh_-.1', 'cours/partie 2?#&%.docx'),
    ('long_id_two_blocks', 'X' * 40, 'a.txt'),
]


# ---------------------------------------------------------------------------
# Vector builders
# ---------------------------------------------------------------------------
def build_requests():
    responses = {('PageCahierDeTexte', 89): CONTENTS_DATA, ('PageCahierDeTexte', 88): HOMEWORK_DATA}
    window = native_window(TODAY, PREMIER_LUNDI)
    data = {'domaine': window['domaine']}
    student, parent = StubClient(responses), StubParentClient(responses, 'synthetic-child-b')
    for client in (student, parent):
        client.post('PageCahierDeTexte', 89, copy.deepcopy(data))
        client.post('PageCahierDeTexte', 88, copy.deepcopy(data))
    # Direct pronotepy Client.homework produces the same 88 request.
    probe = StubClient({('PageCahierDeTexte', 88): {'ListeTravauxAFaire': {'_T': 24, 'V': []}}})
    first = TODAY - real_dt.timedelta(days=WINDOW_BEFORE)
    probe.homework(first, TODAY + real_dt.timedelta(days=WINDOW_AFTER))
    assert probe.communication.calls[0]['inner'] == student.communication.calls[1]['inner']
    blocked = StubClient(responses)
    blocked.communication.authorized_onglets = [7, 16, 49]
    try:
        blocked.post('PageCahierDeTexte', 89, data)
        raise AssertionError('unauthorized onglet accepted')
    except PermissionError:
        pass
    return {
        'note': 'Inner payload before compression/encryption (see protocol-reference.md envelopes). '
                'Send 89 then 88, serialized, after ParametresUtilisateur; one HTTP request each.',
        'student': student.communication.calls,
        'parent': {'selectedChildId': 'synthetic-child-b', 'calls': parent.communication.calls},
        'unauthorizedOngletIsRefusedLocally': True,
    }


def build_dates():
    cases = []
    # Util.date_parse completes loose values ("18/11", "0830") with today's date: freeze it.
    frozen = Obj(datetime=real_dt.datetime, date=FixedDate, time=real_dt.time, timedelta=real_dt.timedelta)
    for raw in DATE_CASES:
        def attempt(func):
            try:
                with patch.object(dataClasses, 'datetime', frozen):
                    return func(raw).isoformat()[:10]
            except Exception:
                return None
        cases.append({'raw': raw, 'expectedDate': parse_local_date(raw),
                      'pronotepyDatetimeParse': attempt(dataClasses.Util.datetime_parse) if raw is not None else None,
                      'pronotepyDateParse': attempt(dataClasses.Util.date_parse) if raw is not None else None})
    for case in cases:
        # Where pronotepy's row-date parser succeeds, the native parser must agree.
        if case['pronotepyDatetimeParse'] is not None:
            assert case['expectedDate'] == case['pronotepyDatetimeParse'], case
    return cases


def build_html():
    cases = []
    for name, source in HTML_CASES:
        expected = safe_text(source)
        assert '<script' not in ascii_lower(expected['text']) or name == 'escaped_markup_is_text', name
        assert all(l['url'].lower().startswith(('http://', 'https://')) for l in expected['links'])
        cases.append({'name': name, 'html': source, 'expected': expected,
                      'pronotepyHtmlParse': dataClasses.Util.html_parse(source)})
    return {
        'rules': {
            'rawTextElementsDroppedWithContent': RAW_TEXT_ELEMENTS,
            'blockElementsSoftBreak': BLOCK_ELEMENTS,
            'cellElementsSpace': CELL_ELEMENTS,
            'hardBreak': ['br'],
            'listItemPrefix': BULLET,
            'whitespaceMappedToSpace': [f'U+{ord(c):04X}' for c in WHITESPACE],
            'removedCodePoints': ['U+0000..U+001F (after whitespace mapping)', 'U+007F..U+009F',
                                  'U+202A..U+202E', 'U+2066..U+2069', 'U+FEFF'],
            'entityPattern': ENTITY_RE.pattern,
        },
        'entityTable': ENTITY_TABLE,
        'cases': cases,
    }


def build_link_urls():
    cases = []
    for url in LINK_URL_CASES:
        web = web_notebook.attachments_metadata([Obj(type=0, name='x', url=url)], {})
        cases.append({'url': url, 'accepted': validate_http_url(url), 'webConnectorAccepted': bool(web)})
    return cases


def build_weeks():
    out = []
    for today in WEEK_TODAYS:
        first = today - real_dt.timedelta(days=WINDOW_BEFORE)
        last = today + real_dt.timedelta(days=WINDOW_AFTER)
        probe = StubClient({})
        out.append({'today': today.isoformat(), 'premierLundi': PREMIER_LUNDI.isoformat(),
                    'pronotepyFirstWeek': probe.get_week(first), 'pronotepyLastWeek': probe.get_week(last),
                    'expected': native_window(today, PREMIER_LUNDI)})
    return out


def web_view(contents, homework):
    """Comparable projection shared by the web output and the native model."""
    def atts(items):
        return sorted([(a['kind'], a['name'], a.get('url')) for a in items])
    c = sorted((x['date'], x['subjectName'], x['title'], x['category'], atts(x['attachments'])) for x in contents)
    h = sorted((x['id'], x['dueDate'], x['subjectName'], x['done'], atts(x['attachments'])) for x in homework)
    return c, h


def build_notebook(window):
    expected = native_model(CONTENTS_DATA, HOMEWORK_DATA, window, TIMETABLE_SUBJECTS)
    # Web parity: run scripts/pronote_notebook.py on the same responses minus documented deviations.
    contents = copy.deepcopy(CONTENTS_DATA)
    contents['ListeCahierDeTextes']['V'] = [r for r in contents['ListeCahierDeTextes']['V']
                                            if r['N'] not in WEB_PARITY_EXCLUDED['rows']]
    for r in contents['ListeCahierDeTextes']['V']:
        for c in r['listeContenus']['V']:
            c['ListePieceJointe']['V'] = [a for a in c['ListePieceJointe']['V']
                                          if a['N'] not in WEB_PARITY_EXCLUDED['attachments']]
    homework = copy.deepcopy(HOMEWORK_DATA)
    homework['ListeTravauxAFaire']['V'] = [h for h in homework['ListeTravauxAFaire']['V']
                                           if h['N'] not in WEB_PARITY_EXCLUDED['homework']]
    lessons = [Obj(id='cours-svt', subject=Obj(name='SVT'))]
    client = StubClient({('PageCahierDeTexte', 89): contents, ('PageCahierDeTexte', 88): homework}, lessons)
    entry = {'client': client}
    counter = iter(range(1, 1000))
    with patch.object(web_notebook, 'dt', frozen_dt()), \
            patch.object(web_notebook.secrets, 'token_urlsafe', side_effect=lambda _n: f'opaque-{next(counter)}'):
        web = web_notebook.fetch_notebook(entry)
    web.pop('fetchedAt')
    registry = entry['attachments']
    native_contents = [{'date': s['date'], 'subjectName': s['subject'], 'title': c['title'],
                        'category': c['category'], 'attachments': c['attachments']}
                       for s in native_model(contents, homework, window, TIMETABLE_SUBJECTS)['sessions']
                       for c in s['contents']]
    native_homework = [{'id': h['id'], 'dueDate': h['dueDate'], 'subjectName': h['subject'], 'done': h['done'],
                        'attachments': h['attachments']}
                       for h in native_model(contents, homework, window, TIMETABLE_SUBJECTS)['homework']]
    assert web_view(web['contents'], web['homework']) == web_view(native_contents, native_homework)
    parity_model = native_model(contents, homework, window, TIMETABLE_SUBJECTS)
    assert web['skippedEntries'] == parity_model['skippedEntries']
    assert web['attachmentCount'] == parity_model['attachmentCount']
    assert (web['from'], web['to']) == (window['from'], window['to'])
    assert [c['inner'] for c in client.communication.calls] == [
        {'Signature': {'onglet': 89}, 'data': {'domaine': window['domaine']}},
        {'Signature': {'onglet': 88}, 'data': {'domaine': window['domaine']}}]
    return {
        'today': TODAY.isoformat(),
        'timetableSubjects': TIMETABLE_SUBJECTS,
        'responses': {
            'PageCahierDeTexte89': {'dataSec': {'data': CONTENTS_DATA}},
            'PageCahierDeTexte88': {'dataSec': {'data': HOMEWORK_DATA}},
        },
        'expected': expected,
        'webParity': {
            'note': 'scripts/pronote_notebook.py executed on the same responses minus the excluded items; '
                    'dates, subjects, titles, categories, attachments, done, skippedEntries and attachmentCount '
                    'are asserted equal (order-insensitive: the web sorts by (date, subject) descending). '
                    'Descriptions differ by design: the web uses pronotepy Util.html_parse.',
            'excluded': WEB_PARITY_EXCLUDED,
            'webOutput': web,
        },
    }, registry


def build_download(registry):
    cases = []
    web_urls = {f.id: f.url for f in registry.values() if f.type == 1}
    for name, ident, file_name in DOWNLOAD_CASES:
        plaintext = attachment_plaintext(ident).encode()
        single = Padding.pad(plaintext, 16)
        double = Padding.pad(single, 16)
        cipher = aes_cbc(SESSION_KEY_HEX, SESSION_IV_HEX, double).hex()
        url = f'{PRONOTE_ROOT}/FichiersExternes/{cipher}/{encode_uri_component(file_name)}?Session={SESSION_H}'
        attachment = dataClasses.Attachment(StubClient({}), {'N': ident, 'L': file_name, 'G': 1})
        assert attachment.url == url, name
        if ident in web_urls:
            assert web_urls[ident] == url, name
        cases.append({
            'name': name, 'attachment': {'N': ident, 'L': file_name, 'G': 1},
            'plaintextUtf8': plaintext.decode(),
            'padded1Hex': single.hex(), 'padded2Hex': double.hex(),
            'ciphertextHex': cipher,
            'singlePaddingVariantCiphertextHex': aes_cbc(SESSION_KEY_HEX, SESSION_IV_HEX, single).hex(),
            'encodedName': encode_uri_component(file_name),
            'expectedURL': url,
            'saveAsName': save_as_name(clean_plain(file_name) or 'Document'),
        })
    rejected = []
    for ident in ['', 'a b', 'a"b', 'a\\b', 'é1', '\u00002']:
        try:
            attachment_plaintext(ident)
            raise AssertionError(ident)
        except ValueError:
            rejected.append(ident)
    return {
        'sessionKeyHex': SESSION_KEY_HEX, 'ivHex': SESSION_IV_HEX,
        'pronoteRoot': PRONOTE_ROOT, 'pageURL': PAGE_URL, 'sessionH': SESSION_H,
        'note': 'pronotepy pads the JSON with PKCS#7 itself, then aes_encrypt pads again: ciphertext = '
                'AES-CBC(sessionKey, sessionIV, PKCS7(PKCS7(plaintext))). Lowercase hex. The URL embeds the '
                'session: keep it inside the transport layer, never log, display, persist or share it.',
        'cases': cases,
        'rejectedIds': rejected,
        'maxBytes': MAX_DOWNLOAD_BYTES,
        'sizeCases': [{'bytes': MAX_DOWNLOAD_BYTES, 'accepted': True},
                      {'bytes': MAX_DOWNLOAD_BYTES + 1, 'accepted': False}],
        'saveAsNames': [{'name': n, 'expected': save_as_name(clean_plain(n) or 'Document')}
                        for n in ['../../secret.pdf', 'C:\\Users\\x\\note.txt', 'a<b>:c"d|e?f*.pdf',
                                  '  .cache. ', 'Énoncé\u202egnp.exe', '']],
    }


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build():
    window = native_window(TODAY, PREMIER_LUNDI)
    notebook, registry = build_notebook(window)
    pronotepy_dir = Path(pronotepy.__file__).resolve().parent
    return {
        'schemaVersion': 1,
        'provenance': {
            'generator': 'native/shared/notebook-vectors.py (executes scripts/pronote_notebook.py and pronotepy)',
            'pronotepyVersion': '2.15.7',
            'host': HOST,
            'containsRealCredentials': False,
            'containsPersonalData': False,
            'sources': {
                'scripts/pronote_notebook.py': sha256(ROOT_DIR / 'scripts' / 'pronote_notebook.py'),
                'pronotepy/clients.py': sha256(pronotepy_dir / 'clients.py'),
                'pronotepy/dataClasses.py': sha256(pronotepy_dir / 'dataClasses.py'),
            },
        },
        'encodings': {
            'dates': 'ISO calendar dates (YYYY-MM-DD) in school wall time, no time zone conversion',
            'Hex': 'lowercase hexadecimal of raw bytes',
            'text': 'strings compare exactly, as Unicode code point sequences',
        },
        'window': window,
        'weeks': build_weeks(),
        'requests': build_requests(),
        'safety': {
            'readOnlyFunctionIds': ['FonctionParametres', 'Identification', 'Authentification',
                                    'ParametresUtilisateur', 'PageInfosPerso', 'Navigation',
                                    'PageEmploiDuTemps', 'PageCahierDeTexte'],
            'pageCahierDeTexte': {'allowedOnglets': [88, 89], 'allowedDataKeys': ['domaine'],
                                  'domaineType': 8, 'maxWeekSpan': 5},
            'rejectedFunctionExamples': ['SaisieTAFFaitEleve', 'SaisieActualites', 'SaisieMessage',
                                         'SaisiePublicMessage', 'SecurisationCompteDoubleAuth', 'JetonAppliMobile'],
            'rejectedFunctionPrefixes': ['Saisie'],
            'download': {'method': 'GET', 'scheme': 'https', 'sameHostAsSession': True,
                         'pathPrefix': '/FichiersExternes/', 'maxBytes': MAX_DOWNLOAD_BYTES},
        },
        'dates': build_dates(),
        'html': build_html(),
        'linkUrls': build_link_urls(),
        'notebook': notebook,
        'download': build_download(registry),
    }


def serialize(data):
    return json.dumps(data, ensure_ascii=False, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true', help='fail if notebook-vectors.json is stale')
    args = parser.parse_args()
    text = serialize(build())
    assert text == serialize(build()), 'generator is not deterministic'
    if args.check:
        current = OUTPUT.read_text(encoding='utf-8') if OUTPUT.exists() else ''
        if current != text:
            print('notebook-vectors.json is stale: regenerate it.', file=sys.stderr)
            sys.exit(1)
        print('notebook-vectors.json is up to date.')
        return
    OUTPUT.write_text(text, encoding='utf-8')
    print(f'Wrote {OUTPUT.relative_to(ROOT_DIR)} ({len(text)} bytes).')


if __name__ == '__main__':
    main()
