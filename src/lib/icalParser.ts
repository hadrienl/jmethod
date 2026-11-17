import { addDays, format, parseISO } from 'date-fns';
import { PronoteEvent, Subject } from '../types';

/**
 * Matches a raw event summary string from Pronote with configured app subjects
 */
export function matchSubject(rawSummary: string, subjects: Subject[]): Subject | undefined {
  const normalizedSummary = rawSummary
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');

  return subjects.find((subj) => {
    const normalizedSubj = subj.name
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '');

    // Direct include or aliases
    if (normalizedSummary.includes(normalizedSubj)) return true;
    if (subj.id === 'maths' && (normalizedSummary.includes('math') || normalizedSummary.includes('mathematiques'))) return true;
    if (subj.id === 'svt' && (normalizedSummary.includes('s.v.t') || normalizedSummary.includes('svt') || normalizedSummary.includes('sciences de la vie'))) return true;
    if (subj.id === 'physique-chimie' && (normalizedSummary.includes('physique') || normalizedSummary.includes('chimie') || normalizedSummary.includes('spc'))) return true;
    if (subj.id === 'histoire-geo' && (normalizedSummary.includes('histoire') || normalizedSummary.includes('geo') || normalizedSummary.includes('emc') || normalizedSummary.includes('h-g'))) return true;
    if (subj.id === 'francais' && normalizedSummary.includes('francais')) return true;
    if (subj.id === 'anglais' && (normalizedSummary.includes('anglais') || normalizedSummary.includes('lv1'))) return true;
    if (subj.id === 'espagnol' && (normalizedSummary.includes('espagnol') || normalizedSummary.includes('lv2'))) return true;
    if (subj.id === 'techno' && (normalizedSummary.includes('techno') || normalizedSummary.includes('technologie'))) return true;
    if (subj.id === 'arts-plastiques' && (normalizedSummary.includes('arts') || normalizedSummary.includes('plastique'))) return true;
    if (subj.id === 'musique' && (normalizedSummary.includes('musique') || normalizedSummary.includes('chant'))) return true;

    return false;
  });
}

/**
 * Parses raw iCal / ICS content into structured PronoteEvent items
 */
export function parseICalFeed(icalContent: string, subjects: Subject[]): PronoteEvent[] {
  const events: PronoteEvent[] = [];
  const lines = icalContent.replace(/\r\n/g, '\n').split('\n');

  let inEvent = false;
  let currentEvent: Partial<{
    uid: string;
    summary: string;
    dtstart: string;
    dtend: string;
    location: string;
    description: string;
    status: string;
  }> = {};

  for (let i = 0; i < lines.length; i++) {
    let line = lines[i];

    // Handle multiline folded lines (iCal RFC 5545 allows lines starting with space or tab)
    while (i + 1 < lines.length && (lines[i + 1].startsWith(' ') || lines[i + 1].startsWith('\t'))) {
      i++;
      line += lines[i].substring(1);
    }

    if (line.startsWith('BEGIN:VEVENT')) {
      inEvent = true;
      currentEvent = {};
      continue;
    }

    if (line.startsWith('END:VEVENT')) {
      inEvent = false;
      if (currentEvent.summary && currentEvent.dtstart) {
        const parsed = parseVEvent(currentEvent, subjects);
        if (parsed) events.push(parsed);
      }
      currentEvent = {};
      continue;
    }

    if (!inEvent) continue;

    if (line.startsWith('UID:')) currentEvent.uid = line.substring(4).trim();
    else if (line.startsWith('SUMMARY:')) currentEvent.summary = line.substring(8).trim();
    else if (line.startsWith('DTSTART')) {
      const parts = line.split(':');
      currentEvent.dtstart = parts.slice(1).join(':').trim();
    } else if (line.startsWith('DTEND')) {
      const parts = line.split(':');
      currentEvent.dtend = parts.slice(1).join(':').trim();
    } else if (line.startsWith('LOCATION:')) currentEvent.location = line.substring(9).trim();
    else if (line.startsWith('DESCRIPTION:')) currentEvent.description = line.substring(12).trim();
    else if (line.startsWith('STATUS:')) currentEvent.status = line.substring(7).trim();
  }

  // Sort chronologically
  return events.sort((a, b) => {
    const dtA = `${a.date}T${a.startTime}`;
    const dtB = `${b.date}T${b.startTime}`;
    return dtA.localeCompare(dtB);
  });
}

function parseVEvent(
  raw: Partial<{
    uid: string;
    summary: string;
    dtstart: string;
    dtend: string;
    location: string;
    description: string;
    status: string;
  }>,
  subjects: Subject[]
): PronoteEvent | null {
  try {
    const summary = raw.summary || 'Cours';
    const isCancelled =
      summary.toLowerCase().includes('[annulé]') ||
      summary.toLowerCase().includes('annulé') ||
      raw.status?.toUpperCase() === 'CANCELLED';

    // Parse DTSTART format (e.g. 20261117T083000Z or 20261117T083000)
    const startDate = parseICalDateTime(raw.dtstart || '');
    if (!startDate) return null;

    const endDate = (raw.dtend ? parseICalDateTime(raw.dtend) : null) || startDate;

    const dateStr = format(startDate, 'yyyy-MM-dd');
    const startTimeStr = format(startDate, 'HH:mm');
    const endTimeStr = format(endDate, 'HH:mm');

    const matched = matchSubject(summary, subjects);

    return {
      id: raw.uid || `pronote-${dateStr}-${startTimeStr}-${summary}`,
      subjectName: matched ? matched.name : summary.replace(/\[annulé\]/i, '').trim(),
      matchedSubjectId: matched?.id,
      date: dateStr,
      startTime: startTimeStr,
      endTime: endTimeStr,
      room: raw.location,
      teacher: raw.description,
      isCancelled,
      isCourseCreated: false,
    };
  } catch (err) {
    console.warn('Erreur de parsing VEVENT', err);
    return null;
  }
}

function parseICalDateTime(str: string): Date | null {
  // Strip TZID prefix if present e.g. "TZID=Europe/Paris:20261117T080000"
  const clean = str.replace(/^.*:/, '').trim();

  // Pattern: YYYYMMDDTHHMMSS (optional Z)
  const match = clean.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z)?$/);
  if (match) {
    const [, year, month, day, hours, minutes, seconds] = match;
    const isUTC = match[7] === 'Z';

    if (isUTC) {
      return new Date(
        Date.UTC(
          parseInt(year, 10),
          parseInt(month, 10) - 1,
          parseInt(day, 10),
          parseInt(hours, 10),
          parseInt(minutes, 10),
          seconds ? parseInt(seconds, 10) : 0
        )
      );
    }

    return new Date(
      parseInt(year, 10),
      parseInt(month, 10) - 1,
      parseInt(day, 10),
      parseInt(hours, 10),
      parseInt(minutes, 10),
      seconds ? parseInt(seconds, 10) : 0
    );
  }

  // Fallback ISO attempt
  try {
    return parseISO(str);
  } catch {
    return null;
  }
}

/**
 * Realistic Pronote timetable sample for quick demo testing without waiting for ENT login
 */
export function generateSamplePronoteFeed(): string {
  // The demo follows the evening workflow and provides tomorrow’s timetable.
  const tomorrow = addDays(new Date(), 1);
  const yyyy = tomorrow.getFullYear();
  const mm = String(tomorrow.getMonth() + 1).padStart(2, '0');
  const dd = String(tomorrow.getDate()).padStart(2, '0');
  const dateBase = `${yyyy}${mm}${dd}`;

  return `BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Index Éducation//PRONOTE 2026//FR
CALSCALE:GREGORIAN
METHOD:PUBLISH
X-WR-CALNAME:Emploi du temps Pronote - 3ème
BEGIN:VEVENT
UID:pronote-demo-1
DTSTART:${dateBase}T083000
DTEND:${dateBase}T093000
SUMMARY:MATHEMATIQUES
LOCATION:Salle 204
DESCRIPTION:M. Dupont - Arithmétique & calcul littéral
STATUS:CONFIRMED
END:VEVENT
BEGIN:VEVENT
UID:pronote-demo-2
DTSTART:${dateBase}T093000
DTEND:${dateBase}T103000
SUMMARY:PHYSIQUE-CHIMIE
LOCATION:Labo Sciences 2
DESCRIPTION:Mme Martin - Poids et Masse
STATUS:CONFIRMED
END:VEVENT
BEGIN:VEVENT
UID:pronote-demo-3
DTSTART:${dateBase}T104500
DTEND:${dateBase}T114500
SUMMARY:FRANCAIS
LOCATION:Salle 105
DESCRIPTION:Mme Bernard - Grammaire & Analyse
STATUS:CONFIRMED
END:VEVENT
BEGIN:VEVENT
UID:pronote-demo-4
DTSTART:${dateBase}T133000
DTEND:${dateBase}T143000
SUMMARY:HISTOIRE-GEOGRAPHIE
LOCATION:Salle 301
DESCRIPTION:M. Lambert - La Première Guerre mondiale
STATUS:CONFIRMED
END:VEVENT
BEGIN:VEVENT
UID:pronote-demo-5
DTSTART:${dateBase}T143000
DTEND:${dateBase}T153000
SUMMARY:[ANNULÉ] ANGLAIS
LOCATION:Salle 108
DESCRIPTION:Professeur absent
STATUS:CANCELLED
END:VEVENT
END:VCALENDAR`;
}
