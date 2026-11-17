import datetime as dt
import json
from pathlib import Path
import sys
from types import SimpleNamespace as Obj
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import pronote_notebook as notebook


class NotebookTests(unittest.TestCase):
    def test_files_hide_session_urls_and_links_reject_active_schemes(self):
        registry={}
        files=[Obj(type=1,name='Cours.pdf',url='https://school.index-education.net/FichiersExternes/private?Session=secret'),Obj(type=0,name='Exercice',url='https://example.org/exercise'),Obj(type=0,name='Bad',url='javascript:alert(1)')]
        result=notebook.attachments_metadata(files,registry)
        self.assertEqual(len(result),2)
        self.assertEqual(len(registry),1)
        self.assertNotIn('Session',json.dumps(result))
        self.assertNotIn('javascript',json.dumps(result))

    def test_multiple_contents_and_homework_are_separate_without_marking_done(self):
        lesson=Obj(id='lesson',start=dt.datetime.now(),subject=Obj(name='Mathématiques'))
        file=Obj(type=1,name='Exercice.pdf',url='https://school.index-education.net/private?Session=secret')
        calls=[]
        def post(name,tab,data):
            calls.append((name,tab))
            return {'dataSec':{'data':{'ListeCahierDeTextes':{'V':[
              {'Date':{'V':dt.datetime.now().strftime('%d/%m/%Y %H:%M:%S')},'cours':{'V':{'N':'lesson'}},'listeContenus':{'V':[{'title':'Cours A'},{'title':'Cours B'}]}},
              {'Date':{'V':(dt.datetime.now()-dt.timedelta(days=100)).strftime('%d/%m/%Y %H:%M:%S')},'cours':{'V':{'N':'outside-period'}},'listeContenus':{'V':[{'title':'Excluded'}]}}
            ]}}}}
        client=Obj(lessons=lambda *_:[lesson],get_week=lambda _:1,post=post,homework=lambda *_:[Obj(id='homework',subject=lesson.subject,date=dt.date.today(),description='Exercices à faire',done=False,files=[file])])
        entry={'client':client}
        with patch.object(notebook.pronotepy.dataClasses,'LessonContent',side_effect=lambda _,raw:Obj(title=raw['title'],description='Description',category='Cours',files=[])):
            data=notebook.fetch_notebook(entry)
        self.assertEqual([item['title'] for item in data['contents']],['Cours A','Cours B'])
        self.assertEqual(len(data['homework']),1)
        self.assertFalse(data['homework'][0]['done'])
        self.assertEqual(data['attachmentCount'],1)
        self.assertEqual(calls,[('PageCahierDeTexte',89)])
        self.assertNotIn('Session',json.dumps(data))
        self.assertEqual(len(entry['attachments']),1)


    def test_recurring_course_keeps_each_notebook_date(self):
        today=dt.datetime.now()
        earlier=today-dt.timedelta(days=7)
        lesson=Obj(id='recurring',start=today+dt.timedelta(days=7),subject=Obj(name='Anglais'))
        rows=[{'N':str(i),'Date':{'V':when.strftime('%d/%m/%Y %H:%M:%S')},'Matiere':{'V':{'L':'ANGLAIS LV1'}},'cours':{'V':{'N':'recurring'}},'listeContenus':{'V':[{'title':str(i)}]}} for i,when in enumerate((earlier,today))]
        client=Obj(lessons=lambda *_:[lesson],get_week=lambda _:1,post=lambda *_:{'dataSec':{'data':{'ListeCahierDeTextes':{'V':rows}}}},homework=lambda *_:[])
        with patch.object(notebook.pronotepy.dataClasses,'LessonContent',side_effect=lambda _,raw:Obj(title=raw['title'],description='Published text',category='',files=[])):
            result=notebook.fetch_notebook({'client':client})
        self.assertEqual([c['date'] for c in result['contents']],[today.date().isoformat(),earlier.date().isoformat()])
        self.assertEqual(result['contents'][0]['subjectName'],'ANGLAIS LV1')


if __name__=='__main__': unittest.main()
