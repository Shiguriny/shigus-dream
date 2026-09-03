import asyncio, json, sys, urllib.request, urllib.error, urllib.parse, hashlib
import websockets

BASE = "http://127.0.0.1:8090"
WS = "ws://127.0.0.1:8090/ws"

def post_json(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    return json.loads(urllib.request.urlopen(req).read())

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

    # владелец
    b = post_json("/auth/link", {"mc_uuid": "77777777-7777-7777-7777-777777777777", "mc_name": "M"})
    code = b["link_code"]
    async with websockets.connect(WS) as ws:
        await ws.send(json.dumps({"protocol_version": 1, "message_type": "auth", "payload": {"link_code": code}}))
        await recv_until(ws, {"auth.pending"})
        data = urllib.parse.urlencode({"code": code, "username": "modowner"}).encode()
        urllib.request.urlopen(urllib.request.Request(BASE + "/link", data=data, method="POST"))
        e = await recv_until(ws, {"auth.success"})
        token = e["payload"]["access_token"]

    # upload без токена -> 401
    req = urllib.request.Request(BASE + "/mod/upload", data=b"jarbytes", method="POST")
    try:
        urllib.request.urlopen(req)
        check("upload без токена -> 401", False)
    except urllib.error.HTTPError as ex:
        check("upload без токена -> 401", ex.code == 401, ex.code)

    # upload c токеном
    req = urllib.request.Request(BASE + "/mod/upload?version=9.9.9", data=b"jar-bytes-test", method="POST",
        headers={"Authorization": f"Bearer {token}", "X-Filename": "shigusdream-9.9.9.jar"})
    resp = json.loads(urllib.request.urlopen(req).read())
    check("upload ok", resp.get("version") == "9.9.9" and resp.get("size") == 14, resp)

    # latest
    latest = json.loads(urllib.request.urlopen(BASE + "/mod/latest").read())
    check("latest 9.9.9 + sha256", latest["version"] == "9.9.9" and len(latest["sha256"]) == 64, latest)

    # download
    downloaded = urllib.request.urlopen(BASE + "/mod/download").read()
    sha = hashlib.sha256(downloaded).hexdigest()
    check("download байты и sha256", downloaded == b"jar-bytes-test" and sha == latest["sha256"])

    print("MOD UPDATE TEST " + ("OK" if ok else "FAILED"))
    sys.exit(0 if ok else 1)

asyncio.run(main())
