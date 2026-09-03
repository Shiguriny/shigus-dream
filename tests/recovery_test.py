import asyncio, json, sys, urllib.request, urllib.parse
import websockets

BASE = "http://127.0.0.1:8090"
WS = "ws://127.0.0.1:8090/ws"

def post_json(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

def post_form(path, fields):
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

async def recv_until(ws, wanted, timeout=5):
    while True:
        e = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
        if e["message_type"] in wanted:
            return e

async def main():
    ok = True
    def check(name, cond, detail=""):
        nonlocal ok
        print(("  PASS  " if cond else "  FAIL  ") + name + ("" if cond else "  " + str(detail)))
        if not cond: ok = False

    # 1. обычная привязка (uuid1 -> admin_boss, первый аккаунт = admin)
    s, b = post_json("/auth/link", {"mc_uuid": "dddd5555-1111-1111-1111-111111111111", "mc_name": "P1"})
    code = b["link_code"]
    async with websockets.connect(WS) as ws:
        await ws.send(json.dumps({"protocol_version":1,"message_type":"auth","payload":{"link_code":code}}))
        await recv_until(ws, {"auth.pending"})
        post_form("/link", {"code": code, "username": "admin_boss"})
        e = await recv_until(ws, {"auth.success"})
        check("первичная привязка -> owner", e["payload"]["user"]["role"] == "owner")
    # WS закрыт, токены "потеряны" (не сохранены)

    # 2. повторный /auth/link без секрета -> 409
    s, b = post_json("/auth/link", {"mc_uuid": "dddd5555-1111-1111-1111-111111111111", "mc_name": "P1"})
    check("привязанный UUID без секрета -> 409", s == 409, (s, b))

    # 3. force-код с верным секретом -> перепривязка к существующему аккаунту (роль admin сохранена)
    s, b = post_json("/auth/link", {"mc_uuid": "dddd5555-1111-1111-1111-111111111111", "mc_name": "P1",
                                    "recovery_secret": "test-recovery-123"})
    check("force /auth/link -> 200", s == 200 and b.get("force") is True, (s, b))
    code2 = b["link_code"]
    async with websockets.connect(WS) as ws:
        await ws.send(json.dumps({"protocol_version":1,"message_type":"auth","payload":{"link_code":code2}}))
        await recv_until(ws, {"auth.pending"})
        post_form("/link", {"code": code2, "username": "whatever_name"})
        e = await recv_until(ws, {"auth.success"})
        check("восстановление -> существующий аккаунт, роль owner сохранена",
              e["payload"]["user"]["username"] == "admin_boss" and e["payload"]["user"]["role"] == "owner",
              e["payload"]["user"])

    # 4. force-код с неверным секретом -> 409
    s, b = post_json("/auth/link", {"mc_uuid": "dddd5555-1111-1111-1111-111111111111", "mc_name": "P1",
                                    "recovery_secret": "wrong"})
    check("неверный секрет -> 409", s == 409, (s, b))

    print("RECOVERY TEST " + ("OK" if ok else "FAILED"))
    sys.exit(0 if ok else 1)

asyncio.run(main())
