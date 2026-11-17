"""Read-only notebook preview. Attachment session URLs never reach the browser."""
import datetime as dt
import secrets
from urllib.parse import urlparse
import pronotepy


def attachments_metadata(files, registry):
    result = []
    for file in files:
        if file.type == 0:
            url = urlparse(file.url)
            if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password:
                continue
            result.append({'name': file.name or 'Lien', 'kind': 'link', 'url': file.url})
        else:
            attachment_id = secrets.token_urlsafe(18)
            registry[attachment_id] = file
            result.append({'id': attachment_id, 'name': file.name or 'Document', 'kind': 'file'})
    return result


def fetch_notebook(entry):
    client = entry['client']
    today = dt.date.today()
    first = today - dt.timedelta(days=14)
    last = today + dt.timedelta(days=13)
    lessons = client.lessons(dt.datetime.combine(first, dt.time.min), dt.datetime.combine(last, dt.time.max))
    lessons_by_id = {str(lesson.id): lesson for lesson in lessons}
    first_week, last_week = client.get_week(first), client.get_week(last)
    raw = client.post('PageCahierDeTexte', 89, {'domaine': {'_T': 8, 'V': f'[{first_week}..{last_week}]'}})
    rows = raw['dataSec']['data']['ListeCahierDeTextes']['V']
    files = {}
    contents = []
    skipped = 0
    for row in rows:
        lesson_id = str(row.get('cours', {}).get('V', {}).get('N', ''))
        lesson = lessons_by_id.get(lesson_id)
        # A recurring course keeps its ID across weeks. The notebook row's own
        # date is authoritative, not the last timetable occurrence with that ID.
        try:
            published_date = pronotepy.dataClasses.Util.datetime_parse(row['Date']['V']).date()
        except (KeyError, TypeError, ValueError):
            skipped += 1
            continue
        if not first <= published_date <= last:
            continue
        subject_raw = row.get('Matiere', {}).get('V', {})
        subject_name = subject_raw.get('L') or (lesson.subject.name if lesson and lesson.subject else 'Cours')
        for index, content_raw in enumerate(row.get('listeContenus', {}).get('V', [])):
            content = pronotepy.dataClasses.LessonContent(client, content_raw)
            attachments = attachments_metadata(content.files, files)
            if not (content.title or content.description or attachments):
                continue
            contents.append({
                'id': f'{row.get("N", lesson_id)}-{published_date.isoformat()}-{index}-{len(contents)}',
                'subjectName': subject_name,
                'date': published_date.isoformat(),
                'title': content.title or '',
                'description': content.description or '',
                'category': content.category or '',
                'attachments': attachments,
            })
    homework = []
    for item in client.homework(first, last):
        homework.append({
            'id': str(item.id),
            'subjectName': item.subject.name,
            'dueDate': item.date.isoformat(),
            'description': item.description,
            'done': bool(item.done),
            'attachments': attachments_metadata(item.files, files),
        })
    contents.sort(key=lambda item: (item['date'], item['subjectName']), reverse=True)
    homework.sort(key=lambda item: (item['dueDate'], item['subjectName']))
    entry['attachments'] = files
    return {
        'from': first.isoformat(), 'to': last.isoformat(),
        'lessonCount': len(lessons), 'contents': contents, 'homework': homework, 'skippedEntries': skipped,
        'attachmentCount': sum(len(item['attachments']) for item in contents + homework),
        'fetchedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
    }
