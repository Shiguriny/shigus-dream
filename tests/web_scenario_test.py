import json, sys, urllib.request, urllib.error, urllib.parse, asyncio
import websockets

BASE = 'http://127.0.0.1:8090'
WS = 'ws://127.0.0.1:8090/ws'

def post_json(path, body, token=None):
    h = {'Content-Type': 'application/json'}
    if token: h['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=h, method='POST')
    with urllib.request.urlopen(req) as r: return json.loads(r.read().decode())

def form(path, fields):
    data = urllib.parse.urlencode(fields).encode()
    urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, method='POST'))

def get(path, token):
    req = urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(req) as r: return json.loads(r.read().decode())

async def recv_until(ws, wanted, timeout=6):
    while True:
        e = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
        if e['message_type'] in wanted: return e

async def autoresponder(ws):
    while True:
        try:
            e = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            if e['message_type'] == 'action.execute':
                await ws.send(json.dumps({'protocol_version': 1, 'message_type': 'action.result',
                    'request_id': e['request_id'],
                    'payload': {'action': e['payload']['action'], 'status': 'executed'}}))
        except (asyncio.TimeoutError, Exception):
            return

async def main():
    ok = True
    def check(name, cond, detail=''):
        nonlocal ok
        print(('  PASS  ' if cond else '  FAIL  ') + name, detail if not cond else '')
        if not cond: ok = False

    b = post_json('/auth/link', {'mc_uuid': '88888888-1111-1111-1111-111111111111', 'mc_name': 'W'})
    code = b['link_code']
    async with websockets.connect(WS) as ws:
        await ws.send(json.dumps({'protocol_version': 1, 'message_type': 'auth', 'payload': {'link_code': code}}))
        await recv_until(ws, {'auth.pending'})
        form('/link', {'code': code, 'username': 'webboss'})
        e = await recv_until(ws, {'auth.success'})
        token = e['payload']['access_token']

        responder = asyncio.create_task(autoresponder(ws))

        scen = {'name': 'webtest', 'loops': 1, 'scheduled_minutes': 0, 'steps': [
            {'target': 'webboss', 'action': 'shigusdream:show_message', 'args': {'text': 'привет'},
             'delay_ms': 200, 'repeat': 1, 'wait_for_result': True, 'stop_on_error': True},
        ]}
        req = urllib.request.Request(BASE + '/web/scenarios', data=json.dumps(scen).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token}, method='POST')
        with urllib.request.urlopen(req) as r:
            check('POST /web/scenarios', r.status == 200)

        lst = get('/web/scenarios', token)
        check('GET /web/scenarios содержит webtest', any(x['name'] == 'webtest' for x in lst['scenarios']))

        req = urllib.request.Request(BASE + '/web/scenarios/webtest/run',
            headers={'Authorization': 'Bearer ' + token}, method='POST')
        with urllib.request.urlopen(req) as r:
            run = json.loads(r.read())
            check('run вернул run_id', 'run_id' in run, run)
            run_id = run['run_id']

        await asyncio.sleep(8)
        runs = get('/web/runs', token)['runs']
        st = next(x for x in runs if x['run_id'] == run_id)
        check('сценарий выполнен на backend', st['status'] == 'finished', st)

        hist = get('/web/history', token)['history']
        check('история содержит шаг сценария',
              any(h['action'] == 'shigusdream:show_message' and h['sender'] == 'webboss' for h in hist))

        req = urllib.request.Request(BASE + '/web/scenarios/webtest',
            headers={'Authorization': 'Bearer ' + token}, method='DELETE')
        with urllib.request.urlopen(req) as r:
            check('DELETE сценария', r.status == 200)

        responder.cancel()

    print('WEB SCENARIO TEST ' + ('OK' if ok else 'FAILED'))
    sys.exit(0 if ok else 1)

asyncio.run(main())
