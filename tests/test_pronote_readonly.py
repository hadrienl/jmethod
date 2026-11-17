import datetime as dt
import importlib.util
from pathlib import Path
from types import SimpleNamespace as Obj
import unittest

spec = importlib.util.spec_from_file_location('connector', Path(__file__).resolve().parents[1] / 'scripts/pronote_readonly.py')
connector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connector)


class ReadOnlyTests(unittest.TestCase):
    def test_school_writes_are_rejected_before_network(self):
        class Base:
            def post(self, name, *args, **kwargs):
                return name
        client = connector.readonly_client_class(Base)()
        for name in ('SaisieMessage', 'SaisieTravailAFaire', 'SecurisationCompteDoubleAuth', 'JetonAppliMobile'):
            with self.assertRaises(PermissionError):
                client.post(name)
        self.assertEqual(client.post('PageEmploiDuTemps'), 'PageEmploiDuTemps')
        with self.assertRaises(PermissionError):
            client._do_2fa(doRegisterDevice=True)

    def test_export_preserves_changes_and_excludes_credentials(self):
        lesson = Obj(id='one', subject=Obj(name='Mathématiques'), start=dt.datetime(2026,11,18,8,30), end=dt.datetime(2026,11,18,9,25), classroom='108', teacher_name='Professeur', canceled=True, password='must-not-leak')
        changed = Obj(**{**vars(lesson), 'classroom':'109'})
        events = connector.normalize_lessons([lesson, lesson, changed])
        self.assertEqual(len(events), 2)
        self.assertTrue(events[0]['isCancelled'])
        self.assertEqual(events[0]['date'], '2026-11-18')
        self.assertEqual(events[0]['startTime'], '08:30')
        self.assertNotIn('must-not-leak', str(events))
        self.assertNotIn('password', str(events))


if __name__ == '__main__':
    unittest.main()
