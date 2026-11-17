import datetime as dt
import http.client
import json
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace as Obj
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import pronote_server as server


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()

    def setUp(self):
        server.SESSIONS.clear()

    def request(self, method, path, body=None, cookie=None, origin='http://localhost:5173'):
        connection = http.client.HTTPConnection('127.0.0.1', self.http.server_port)
        headers = {'Host':'localhost:5173', 'X-JMethod-Client':'local-poc', 'Origin':origin}
        if cookie:
            headers['Cookie'] = cookie
        if body is not None:
            headers['Content-Type'] = 'application/json'
        connection.request(method, '/api/pronote/'+path, json.dumps(body) if body is not None else None, headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read()), response.getheader('Set-Cookie'), response.getheader('Cache-Control')
        connection.close()
        return result

    def fake_entry(self):
        lesson = Obj(id='1', subject=Obj(name='Mathématiques'), start=dt.datetime.now().replace(hour=8,minute=30), end=dt.datetime.now().replace(hour=9,minute=25), classroom='Salle', teacher_name='Professeur', canceled=False)
        client = Obj(info=Obj(name='Compte de test'), lessons=lambda *_: [lesson], communication=Obj(session=Obj(close=lambda:None)))
        return {'client':client, 'created':time.monotonic(), 'lock':threading.Lock()}

    def test_cross_origin_is_denied(self):
        self.assertEqual(self.request('POST','connect',{},origin='https://evil.invalid')[0],403)
        self.assertEqual(self.request('GET','status')[1],{'connected':False})

    def test_invalid_qr_never_logs_in(self):
        self.assertEqual(self.request('POST','connect',{'qr':{},'pin':'1234'})[0],400)
        for url in ('http://127.0.0.1/', 'https://127.0.0.1/', 'https://index-education.net.evil.invalid/pronote/eleve.html', 'https://example.com/'):
            with self.assertRaises(ValueError): server.validate_remote_url(url)

    def test_session_cookie_sync_disconnect_and_isolation(self):
        entry = self.fake_entry()
        with patch.object(server,'connect',return_value=(entry,server.fetch_timetable(entry))):
            status,data,cookie,cache = self.request('POST','connect',{'qr':{},'pin':'1234'})
        self.assertEqual(status,200)
        self.assertEqual(cache,'no-store')
        self.assertIn('HttpOnly',cookie)
        self.assertIn('SameSite=Strict',cookie)
        self.assertNotIn('password',json.dumps(data))
        self.assertNotIn('jeton',json.dumps(data))
        self.assertFalse(self.request('GET','status')[1]['connected'])
        self.assertTrue(self.request('GET','status',cookie=cookie)[1]['connected'])
        self.assertEqual(len(self.request('POST','sync',{},cookie=cookie)[1]['events']),1)
        self.assertFalse(self.request('POST','disconnect',{},cookie=cookie)[1]['connected'])
        self.assertEqual(self.request('POST','sync',{},cookie=cookie)[0],401)

    def test_expired_session_removed_and_unavailable_sync_keeps_session(self):
        entry=self.fake_entry()
        server.SESSIONS['test']=entry
        with patch.object(server,'fetch_timetable',side_effect=server.requests.Timeout('sensitive-message')):
            status,data,_,_=self.request('POST','sync',{},cookie='jmethod_pronote=test')
        self.assertEqual(status,503)
        self.assertNotIn('sensitive-message',json.dumps(data))
        self.assertIn('test',server.SESSIONS)
        entry['created']-=server.TTL+1
        self.assertFalse(self.request('GET','status',cookie='jmethod_pronote=test')[1]['connected'])


if __name__=='__main__': unittest.main()
