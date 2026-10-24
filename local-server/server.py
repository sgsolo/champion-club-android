#!/usr/bin/env python3
"""Local YCLIENTS-shaped simulator. Standard library only; no upstream connections."""
import argparse
import collections
import calendar
from datetime import datetime, date, time as daytime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import threading
import time
from urllib.parse import urlparse, parse_qs

COMPANY = 1538880
PARTNER = 'champion-local-partner'
CODE = '1234'
ZONE = timezone(timedelta(hours=4))
PHONES = ('70000000000', '70000000001')
MODES = ('normal', 'full', 'booking_rejected', 'booking_lost_response', 'auth_expired', 'cancel_rejected', 'cancel_lost_response')


class ApiError(Exception):
    def __init__(self, status, message, retry=None):
        self.status, self.message, self.retry = status, message, retry


class State:
    def __init__(self, database):
        self.database = str(database)
        Path(database).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.mode = 'normal'
        self.requests = collections.deque(maxlen=100)
        self.totals = collections.Counter()
        with self.db() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS accounts(id INTEGER PRIMARY KEY,phone TEXT UNIQUE,name TEXT,email TEXT);
              CREATE TABLE IF NOT EXISTS codes(phone TEXT PRIMARY KEY,sent REAL,used INTEGER DEFAULT 0);
              CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,account INTEGER);
              CREATE TABLE IF NOT EXISTS records(id INTEGER PRIMARY KEY AUTOINCREMENT,account INTEGER,activity INTEGER,body TEXT,UNIQUE(account,activity));
            ''')
            for i, phone in enumerate(PHONES):
                db.execute('INSERT OR IGNORE INTO accounts VALUES(?,?,?,?)', (i+1, phone, ('Тестовый игрок','Второй игрок')[i], 'player%d@example.invalid' % (i+1)))
            # Idempotent enrichment preserves existing bookings, IDs and saved sessions.
            self.seed(db)

    def db(self):
        db = sqlite3.connect(self.database, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    def activity(self, activity_id, db):
        try:
            day = datetime.strptime(str(activity_id // 10), '%Y%m%d').date()
            slot = activity_id % 10
            if not 0 <= slot <= 6 or abs((day-date.today()).days)>366*5:
                raise ValueError()
        except (ValueError, OverflowError):
            raise ApiError(404, 'Тестовое занятие не найдено')
        count = sum(not json.loads(row[0]).get('deleted',False) for row in db.execute('SELECT body FROM records WHERE activity=?', (activity_id,)))
        capacity = 8 if slot == 4 else 16
        baseline = capacity if slot == 2 or self.mode == 'full' else capacity-1 if slot==4 else 2
        service = 100 + slot
        staff = 200 + (slot % 2)
        return dict(id=activity_id, date=day.isoformat()+' %02d:00:00' % (14+slot), length=3600,
                    capacity=capacity, records_count=baseline+count, staff_id=staff,service_id=service,
                    staff=dict(id=staff,name='Корт(хард)' if slot%2 else 'Корт(трава)'),
                    service=dict(id=service,title=('НАЧИНАЮЩИЕ' if slot<2 else 'PRE-SATELLITE' if slot<5 else 'SATELLITE; CHALLENGER; TOUR'),
                                 price_min=900,price_max=900,is_online_booking_available=True))

    def record(self, account, activity, record_id=0, past=False):
        return dict(id=record_id,activity_id=activity['id'],services=[dict(id=activity['service_id'],title=activity['service']['title'],cost=900,price_min=900,price_max=900,amount=1)],
                    company=dict(id=COMPANY,title='Champion · тест',timezone=4,address='Вымышленный тестовый клуб'),staff=activity['staff'],
                    date=activity['date'],datetime=activity['date'].replace(' ','T')+'+0400',length=3600,clients_count=1,
                    deleted=False,attendance=1 if past else 0,visit_attendance=1 if past else 0,online=True)

    def insert_record(self, db, account, activity, past=False, attendance=None, deleted=False):
        existing=db.execute('SELECT body FROM records WHERE account=? AND activity=?',(account,activity['id'])).fetchone()
        if existing:return json.loads(existing['body'])
        body=self.record(account,activity,past=past)
        if attendance is not None:body['attendance']=body['visit_attendance']=attendance
        body['deleted']=deleted
        cursor=db.execute('INSERT INTO records(account,activity,body) VALUES(?,?,?)',(account,activity['id'],json.dumps(body,ensure_ascii=False)))
        body['id']=cursor.lastrowid
        db.execute('UPDATE records SET body=? WHERE id=?',(json.dumps(body,ensure_ascii=False),body['id']))
        return body

    def seed(self, db):
        today=datetime.now(ZONE).date()
        sample_days=(2,5,8,11,14,17,20,23,26,29)
        for account in (1,2):
            for year in range(today.year-2,today.year+1):
                for month in range(1,13):
                    count=4+(month*3+year+account)%7
                    for index,number in enumerate(sample_days[:count]):
                        if number>calendar.monthrange(year,month)[1]:continue
                        day=date(year,month,number)
                        if day>=today:continue
                        slot=(index+month+account)%7
                        aid=int(day.strftime('%Y%m%d'))*10+slot
                        # Most visits are attended; include cancellations and unmarked records.
                        marker=(index+month+account)%13
                        attendance=-1 if marker==0 else 0 if marker==1 else 2 if marker==2 else 1
                        self.insert_record(db,account,self.activity(aid,db),True,attendance,marker==3)
            for offset,slot in ((1,1),(2,3),(3,5),(5,0),(7,1)):
                day=today+timedelta(days=offset)
                aid=int(day.strftime('%Y%m%d'))*10+slot
                self.insert_record(db,account,self.activity(aid,db))

    def reset(self):
        with self.lock,self.db() as db:
            db.execute('DELETE FROM records');db.execute('DELETE FROM codes');self.mode='normal';self.seed(db)
        # Sessions remain valid so the installed app can keep using its saved login.


class Handler(BaseHTTPRequestHandler):
    server_version = 'ChampionLocal/1.0'
    def log_message(self, *args):
        pass  # Never print phone numbers, codes, payloads, or authorization headers.

    @property
    def state(self):
        return self.server.state

    def reply(self, status, data, headers=None):
        body=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store')
        for name,value in (headers or {}).items():self.send_header(name,str(value))
        self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass

    def do_GET(self):
        self.handle_request('GET')

    def do_POST(self):
        self.handle_request('POST')

    def do_DELETE(self):
        self.handle_request('DELETE')

    def handle_request(self, method):
        parsed=urlparse(self.path);path=parsed.path
        # Host checking also prevents DNS rebinding to the loopback-only server.
        port=self.server.server_address[1]
        # adb reverse may map the app's fixed port 18765 to an isolated test port.
        local_hosts={host+':'+str(p) for host in ('127.0.0.1','localhost') for p in (port,18765)}
        if self.headers.get('Host') not in local_hosts:
            self.reply(403,dict(success=False,meta=dict(message='Only local host is allowed')));return
        entry=dict(time=datetime.now(ZONE).strftime('%H:%M:%S'),method=method,path=path,status=500)
        self.state.requests.append(entry)
        self.state.totals[method+' '+path]+=1
        try:
            if method in ('POST','DELETE'):
                if self.headers.get('Content-Type','').split(';')[0]!='application/json':raise ApiError(415,'Нужен application/json')
                origin=self.headers.get('Origin')
                if origin and origin not in {'http://'+host for host in local_hosts}:raise ApiError(403,'Другой Origin запрещён')
            if method=='POST':
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=65536:raise ApiError(413,'Неверный размер запроса')
                payload=json.loads(self.rfile.read(size))
                if not isinstance(payload,dict):raise ApiError(400,'Ожидался JSON-объект')
            else:payload={}
            status,result=self.route(method,path,parse_qs(parsed.query),payload)
            entry['status']=status
            if result is None:
                entry['status']='обрыв ответа'
                self.close_connection=True
                try:self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                return
            if isinstance(result,str):
                body=result.encode();self.send_response(status);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(body)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
            else:self.reply(status,result)
        except ApiError as e:
            entry['status']=e.status;self.reply(e.status,dict(success=False,data=[],meta=dict(message=e.message)),{'Retry-After':e.retry} if e.retry else None)
        except (ValueError,KeyError,TypeError):
            entry['status']=400;self.reply(400,dict(success=False,meta=dict(message='Некорректный тестовый запрос')))
        except Exception:
            entry['status']=500;self.reply(500,dict(success=False,meta=dict(message='Ошибка локального сервера')))

    def require_partner(self):
        parts=self.headers.get('Authorization','').split(', ')
        if not parts or parts[0]!='Bearer '+PARTNER:raise ApiError(401,'Нужен локальный тестовый ключ')
        return parts[1][5:] if len(parts)==2 and parts[1].startswith('User ') else None

    def user(self,db,required=True):
        token=self.require_partner()
        found=db.execute('SELECT a.* FROM accounts a JOIN sessions s ON a.id=s.account WHERE s.token=?',(token,)).fetchone()
        if required and found is None:raise ApiError(401,'Тестовый сеанс не найден. Войдите заново.')
        return found

    def route(self,method,path,query,payload):
        if method=='GET' and path=='/':return 200,Path(__file__).with_name('dashboard.html').read_text()
        if method=='GET' and path=='/health':return 200,dict(service='champion-local',local_only=True,pid=os.getpid())
        if method=='GET' and path=='/__sandbox/state':
            with self.state.db() as db:
                count=db.execute('SELECT count(*) FROM records').fetchone()[0]
                records=[json.loads(row[0]) for row in db.execute('SELECT body FROM records ORDER BY id DESC LIMIT 20')]
            return 200,dict(mode=self.state.mode,records_count=count,records=records,requests=list(self.state.requests),totals=dict(self.state.totals),phones=PHONES,code=CODE)
        if method=='POST' and path=='/__sandbox/reset':self.state.reset();return 200,dict(success=True)
        if method=='POST' and path=='/__sandbox/mode':
            mode=payload['mode']
            if mode not in MODES:raise ApiError(400,'Неизвестный сценарий')
            self.state.mode=mode;return 200,dict(success=True,mode=mode)
        if method=='GET' and path=='/api/v1/settings':
            return 200,dict(phone_confirmation=False,booking_surname_required=False,booking_email_required=False,booking_patronymic_required=False,booking_comment_required=False,is_charge_active=False)
        self.require_partner()
        with self.state.lock,self.state.db() as db:
            if method=='GET' and path=='/api/v1/activity/1538880/search':
                day=date.fromisoformat(query['from'][0]);page=int(query.get('page',['1'])[0])
                items=[self.state.activity(int(day.strftime('%Y%m%d'))*10+i,db) for i in range(7)] if page==1 else []
                return 200,dict(success=True,data=items,meta=dict(count=7))
            match=re.fullmatch(r'/api/v1/activity/1538880/(\d+)(/book)?',path)
            if match:
                activity=self.state.activity(int(match[1]),db)
                if method=='GET' and not match[2]:return 200,dict(success=True,data=activity)
                if method=='POST' and match[2]:
                    account=self.user(db,required=False)
                    phone=payload['phone']
                    if phone not in PHONES:raise ApiError(422,'Используйте тестовый номер 70000000000 или 70000000001')
                    if account is None:account=db.execute('SELECT * FROM accounts WHERE phone=?',(phone,)).fetchone()
                    if account['phone']!=phone:raise ApiError(403,'Номер отличается от аккаунта')
                    if not payload.get('is_personal_data_processing_allowed') or not payload.get('fullname','').strip():raise ApiError(422,'Нужны имя и согласие')
                    appointment=payload['appointments'][0]
                    if appointment['activityId']!=activity['id'] or appointment['staff_id']!=activity['staff_id'] or appointment['datetime']!=activity['date'].replace(' ','T') or appointment['services']!=[activity['service_id']]:raise ApiError(422,'Параметры занятия изменились')
                    if self.state.mode=='booking_rejected':raise ApiError(422,'Тестовый отказ: запись отклонена')
                    if datetime.fromisoformat(activity['date']).replace(tzinfo=ZONE)<=datetime.now(ZONE):raise ApiError(422,'Занятие уже началось')
                    if activity['records_count']>=activity['capacity']:raise ApiError(422,'Свободных мест нет')
                    existing=db.execute('SELECT id,body FROM records WHERE account=? AND activity=?',(account['id'],activity['id'])).fetchone()
                    if existing and not json.loads(existing['body']).get('deleted'):raise ApiError(409,'Тестовая запись уже существует')
                    if existing:
                        record=self.state.record(account['id'],activity,existing['id'])
                        db.execute('UPDATE records SET body=? WHERE id=?',(json.dumps(record,ensure_ascii=False),existing['id']))
                    else:record=self.state.insert_record(db,account['id'],activity)
                    if self.state.mode=='booking_lost_response':
                        self.state.mode='normal';db.commit();return 200,None
                    return 200,dict(success=True,data=dict(id=record['id'],hash='local-record-'+str(record['id'])),meta=[])
            if method=='POST' and path=='/api/v1/book_code/1538880':
                phone=payload['phone']
                if phone not in PHONES:raise ApiError(422,'Используйте тестовый номер 70000000000 или 70000000001')
                old=db.execute('SELECT sent FROM codes WHERE phone=?',(phone,)).fetchone()
                if old and time.time()-old[0]<60:raise ApiError(429,'Подождите перед новым тестовым кодом',60)
                db.execute('INSERT OR REPLACE INTO codes(phone,sent,used) VALUES(?,?,0)',(phone,time.time()))
                return 201,dict(success=True,meta=dict(message='Тестовый код 1234. SMS не отправлена.'))
            if method=='POST' and path=='/api/v1/user/auth':
                phone=payload['phone'];code=db.execute('SELECT * FROM codes WHERE phone=?',(phone,)).fetchone()
                if not code or code['used'] or time.time()-code['sent']>300 or payload.get('code')!=CODE:raise ApiError(422,'Неверный или истёкший тестовый код. Запросите код и введите 1234.')
                account=db.execute('SELECT * FROM accounts WHERE phone=?',(phone,)).fetchone()
                token='local_'+secrets.token_hex(16)
                db.execute('UPDATE codes SET used=1 WHERE phone=?',(phone,));db.execute('INSERT INTO sessions VALUES(?,?)',(token,account['id']))
                return 201,dict(id=account['id'],name=account['name'],phone=phone,email=account['email'],user_token=token,login=phone)
            if method=='GET' and path=='/api/v1/user/records':
                account=self.user(db)
                if self.state.mode=='auth_expired':raise ApiError(401,'Тест: сеанс истёк')
                records=[json.loads(row[0]) for row in db.execute('SELECT body FROM records WHERE account=? ORDER BY id',(account['id'],))]
                return 200,dict(success=True,data=records,meta=[])
            match=re.fullmatch(r'/api/v1/user/records/(\d+)/(.*)',path)
            if match and method in ('GET','DELETE'):
                account=self.user(db,required=False)
                if self.state.mode=='auth_expired':raise ApiError(401,'Тест: сеанс истёк')
                row=db.execute('SELECT * FROM records WHERE id=?',(int(match[1]),)).fetchone()
                if row is None:raise ApiError(404,'Запись не найдена')
                if account is not None:
                    if row['account']!=account['id']:raise ApiError(404,'Запись не найдена')
                elif match[2]!='local-record-'+str(row['id']):raise ApiError(401,'Нужен сеанс или ключ записи')
                record=json.loads(row['body'])
                if method=='GET':return 200,dict(success=True,data=[record],meta=[])
                if self.state.mode=='cancel_rejected':raise ApiError(403,'Клуб запретил отмену этой записи')
                if not record['deleted']:
                    if datetime.fromisoformat(record['date']).replace(tzinfo=ZONE)<=datetime.now(ZONE) or record.get('visit_attendance') in (1,-1):raise ApiError(422,'Занятие уже началось или завершено')
                    record['deleted']=True
                    db.execute('UPDATE records SET body=? WHERE id=?',(json.dumps(record,ensure_ascii=False),row['id']))
                if self.state.mode=='cancel_lost_response':
                    self.state.mode='normal';db.commit();return 200,None
                return 200,dict(success=True,data=[],meta=[])
        raise ApiError(404,'Маршрут локального сервера не найден')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database',type=Path,default=Path(__file__).with_name('data')/'sandbox.sqlite3')
    parser.add_argument('--port',type=int,default=18765)
    args=parser.parse_args()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    server.state=State(args.database);server.daemon_threads=True
    print('Champion local server: http://127.0.0.1:'+str(args.port),flush=True)
    print('Test phone: 70000000000; code: 1234. No SMS or real bookings.',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()


if __name__=='__main__':main()
