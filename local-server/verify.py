#!/usr/bin/env python3
"""Integration checks against the running local simulator. Resets its test records."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import http.client
import argparse
import json
from urllib.request import Request, urlopen, ProxyHandler, build_opener
from urllib.error import HTTPError

BASE='http://127.0.0.1:18765'
PARTNER='champion-local-partner'
opener=build_opener(ProxyHandler({}))


def call(method,path,body=None,token=None,partner=PARTNER):
    headers={'Authorization':'Bearer '+partner,'Content-Type':'application/json'}
    if token:headers['Authorization']+=', User '+token
    req=Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:
        with opener.open(req,timeout=5) as response:return response.status,json.load(response)
    except HTTPError as e:return e.code,json.load(e)


def require(status,result,expected):
    assert status==expected,(status,result,expected)
    return result


def main():
    global BASE
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--base',default=BASE);args=parser.parse_args()
    if not args.base.startswith('http://127.0.0.1:'):raise ValueError('Only loopback test servers are allowed')
    BASE=args.base.rstrip('/')
    assert require(*call('GET','/health'),200)['local_only']
    require(*call('POST','/__sandbox/reset',{}),200)
    try:
        require(*call('GET','/api/v1/user/records',partner='fake'),401)
        require(*call('POST','/api/v1/book_code/1538880',{'phone':'79999999999'}),422)
        tokens=[]
        for phone in ('70000000000','70000000001'):
            require(*call('POST','/api/v1/book_code/1538880',{'phone':phone}),201)
            require(*call('POST','/api/v1/book_code/1538880',{'phone':phone}),429)
            require(*call('POST','/api/v1/user/auth',{'phone':phone,'code':'9999'}),422)
            user=require(*call('POST','/api/v1/user/auth',{'phone':phone,'code':'1234'}),201)
            tokens.append(user['user_token'])
            require(*call('POST','/api/v1/user/auth',{'phone':phone,'code':'1234'}),422)
        histories=[require(*call('GET','/api/v1/user/records',token=t),200)['data'] for t in tokens]
        assert all(len(r)>150 for r in histories)
        baseline=len(histories[0])
        assert not ({r['id'] for r in histories[0]}&{r['id'] for r in histories[1]})
        tomorrow=(datetime.now(timezone(timedelta(hours=4)))+timedelta(days=1)).date().isoformat()
        items=require(*call('GET','/api/v1/activity/1538880/search?from='+tomorrow+'&page=1'),200)['data']
        def book(t,account=0):
            return call('POST','/api/v1/activity/1538880/%d/book'%t['id'],{'phone':('70000000000','70000000001')[account],'fullname':'Тестовый игрок','is_personal_data_processing_allowed':True,
              'appointments':[{'activityId':t['id'],'staff_id':t['staff_id'],'services':[t['service_id']],'datetime':t['date'].replace(' ','T')}]},tokens[account])
        receipt=require(*book(items[3]),200)['data']
        require(*book(items[3]),409)
        assert len(require(*call('GET','/api/v1/user/records',token=tokens[0]),200)['data'])==baseline+1
        require(*call('POST','/__sandbox/mode',{'mode':'booking_rejected'}),200)
        require(*book(items[5]),422)
        require(*call('POST','/__sandbox/mode',{'mode':'full'}),200)
        full=require(*call('GET','/api/v1/activity/1538880/%d'%items[5]['id']),200)['data']
        assert full['records_count']>=full['capacity'];require(*book(full),422)
        require(*call('POST','/__sandbox/mode',{'mode':'auth_expired'}),200)
        require(*call('GET','/api/v1/user/records',token=tokens[0]),401)
        require(*call('POST','/__sandbox/mode',{'mode':'booking_lost_response'}),200)
        try:book(items[6]);raise AssertionError('Expected disconnected response')
        except (http.client.RemoteDisconnected,ConnectionError):pass
        assert any(r['activity_id']==items[6]['id'] for r in require(*call('GET','/api/v1/user/records',token=tokens[0]),200)['data'])
        # The 18:00 activity has one remaining place. Exactly one concurrent request wins.
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses=sorted(x[0] for x in pool.map(lambda account:book(items[4],account),[0,1]))
        assert statuses==[200,422],statuses
        # Cancellation must be scoped to the caller, preserve history, and release the place.
        path='/api/v1/user/records/%d/'%receipt['id']
        require(*call('GET',path,token=tokens[1]),404)
        require(*call('DELETE',path,token=tokens[1]),404)
        require(*call('DELETE',path),401)
        require(*call('GET',path+receipt['hash']),200)
        activity_path='/api/v1/activity/1538880/%d'%items[3]['id']
        occupied=require(*call('GET',activity_path),200)['data']['records_count']
        require(*call('POST','/__sandbox/mode',{'mode':'cancel_rejected'}),200)
        require(*call('DELETE',path,token=tokens[0]),403)
        require(*call('POST','/__sandbox/mode',{'mode':'cancel_lost_response'}),200)
        try:call('DELETE',path,token=tokens[0]);raise AssertionError('Expected disconnected cancellation response')
        except (http.client.RemoteDisconnected,ConnectionError):pass
        assert require(*call('GET',path,token=tokens[0]),200)['data'][0]['deleted']
        assert require(*call('GET',activity_path),200)['data']['records_count']==occupied-1
        require(*call('DELETE',path,token=tokens[0]),200) # Idempotent, never releases two places.
        assert require(*call('GET',activity_path),200)['data']['records_count']==occupied-1
        require(*book(items[3]),200)
        require(*call('DELETE',path+receipt['hash']),200)
        past=next(r for r in histories[0] if not r['deleted'] and r['visit_attendance']==1)
        require(*call('DELETE','/api/v1/user/records/%d/'%past['id'],token=tokens[0]),422)
        print('PASS: record details; ownership and hash access; cancellation denial, lost response, idempotency, history and capacity; rebooking and past-visit guard.')
        print('PASS: real local HTTP; code validation/reuse and cooldown; invalid key/number; account isolation; booking and duplicates; capacity race; errors 401/422; saved booking with lost response.')
    finally:require(*call('POST','/__sandbox/reset',{}),200)


if __name__=='__main__':main()
