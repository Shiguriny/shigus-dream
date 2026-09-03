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
    with urllib.request.urlopen(req) as r:
        return r.status, r.read().decode()

def get(path, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    req = urllib.request.Request(BASE + path, headers=headers)
    with urllib.request.urlopen(req) as r:
        return r.status, json.loads(r.read().decode())

async def recv_until(ws, wanted, timeout=5):
    while True:
        e = json.loads(await asyncio.wait_for(ws.recv(), timeout=timeout))
        if e["message_type"] in wanted:
            return e

async def link(ws, mc_uuid, mc_name, username):
    s, b = post_json("/auth/link", {"mc_uuid": mc_uuid, "mc_name": mc_name})
    assert s == 200
    await ws.send(json.dumps({"protocol_version":1,"message_type":"auth","payload":{"link_code":b["link_code"]}}))
    await recv_until(ws, {"auth.pending"})
    post_form("/link", {"code": b["link_code"], "username": username})
    e = await recv_until(ws, {"auth.success"})
    return e["payload"]

async def main():
    ok = True
    def check(name, cond, detail=""):
        nonlocal ok
        print(("  PASS  " if cond else "  FAIL  ") + name + ("" if cond else "  " + str(detail)))
        if not cond: ok = False

    async with websockets.connect(WS) as ws_owner:
        p_owner = await link(ws_owner, "eeee1111-1111-1111-1111-111111111111", "O", "boss")
        check("владелец = owner", p_owner["user"]["role"] == "owner", p_owner["user"])
        access_owner = p_owner["access_token"]

        async with websockets.connect(WS) as ws_target:
            p_t = await link(ws_target, "eeee2222-2222-2222-2222-222222222222", "T", "peer")
            check("второй = user", p_t["user"]["role"] == "user")

            # владелец выдаёт админку
            s, users = get("/users", access_owner)
            tid = next(u["id"] for u in users["users"] if u["username"] == "peer")
            req = urllib.request.Request(BASE + f"/users/{tid}/role",
                data=json.dumps({"role": "admin"}).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {access_owner}"}, method="POST")
            with urllib.request.urlopen(req) as r:
                check("role endpoint 200", r.status == 200)

            # push role.update на целевой WS
            e = await recv_until(ws_target, {"role.update"})
            check("role.update получен on-line", e["payload"]["role"] == "admin", e)

            # админ видит список, но не может менять роли
            s2, _ = get("/users", p_t["access_token"])
            check("admin видит /users", s2 == 200)
            req2 = urllib.request.Request(BASE + f"/users/{tid}/role",
                data=json.dumps({"role": "user"}).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {p_t['access_token']}"}, method="POST")
            try:
                urllib.request.urlopen(req2)
                check("admin НЕ может менять роли", False)
            except urllib.error.HTTPError as ex:
                check("admin НЕ может менять роли", ex.code == 403, ex.code)

    print("ROLE PUSH TEST " + ("OK" if ok else "FAILED"))
    sys.exit(0 if ok else 1)

asyncio.run(main())
