import { addDays, format, parseISO } from 'date-fns';
import { Course, Revision } from '../types';

/** Tonight prepares the next calendar day; completion history keeps the real date. */
export function getEveningProgramme(revisions: Revision[], courses: Course[], today: string) {
  const preparationDate = format(addDays(parseISO(today), 1), 'yyyy-MM-dd');
  const courseIds = new Set(courses.map(course => course.id));
  const valid = revisions.filter(revision => courseIds.has(revision.courseId));
  const due = valid.filter(revision => revision.status !== 'completed' && revision.scheduledDate <= preparationDate)
    .sort((a, b) => a.scheduledDate.localeCompare(b.scheduledDate));
  const completed = valid.filter(revision => revision.status === 'completed' && revision.completedDate === today);
  return {
    preparationDate,
    due,
    completed,
    overdue: due.filter(revision => revision.scheduledDate < preparationDate),
    visible: [...due, ...completed],
  };
}
