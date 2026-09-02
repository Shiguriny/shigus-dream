# -*- coding: utf-8 -*-
"""
Smoke-тест Shigu's Dream backend (end-to-end, in-memory режим).

Симулирует двух клиентов мода: admin_boss (админ, первый аккаунт) и target_player.
Проверяет: auth-link flow, presence, доставку action.execute/action.result,
дедупликацию request_id, target_offline, queued-доставку, cancel, permissions.

Запуск:  python tests/smoke_test.py  (backend должен быть запущен на :8080)
"""
import asyncio
import json
import sys
import urllib.request
import urllib.parse

import websockets

BASE = "http://127.0.0.1:8080"
WS = "ws://127.0.0.1:8080/ws"

PASSED = 0
FAILED = []


def check(name: str, cond: bool, detail: str = ""):
    global PASSED
    if cond:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED.append(name)
        print(f"  FAIL  {name}  {detail}")


def http_post_json(path: str, body: dict, token: str = None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def http_post_form(path: str, fields: dict):
    data = urllib.parse.urlencode(fields).encode()
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def http_get(path: str, token: str = None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    req = urllib.request.Request(BASE + path, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def env(msg_type: str, payload=None, request_id=None) -> str:
    e = {"protocol_version": 1, "message_type": msg_type, "payload": payload or {}}
    if request_id:
        e["request_id"] = request_id
    return json.dumps(e)


async def recv_until(ws, wanted_types, timeout=5.0):
    """Читает сообщения, пока не встретит одно из wanted_types; возвращает (type, envelope)."""
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
        e = json.loads(raw)
        if e["message_type"] in wanted_types:
            return e["message_type"], e


async def link_and_connect(ws, mc_uuid, mc_name, username):
    """Полный цикл привязки: POST /auth/link -> auth(link_code) -> подтверждение по HTTP -> auth.success."""
    status, body = http_post_json("/auth/link", {"mc_uuid": mc_uuid, "mc_name": mc_name})
    assert status == 200, f"auth/link failed: {status} {body}"
    code = body["link_code"]

    await ws.send(env("auth", {"mc_uuid": mc_uuid, "mc_name": mc_name, "link_code": code}))
    t, e = await recv_until(ws, {"auth.pending"})
    assert t == "auth.pending", f"ожидался auth.pending, получен {t}"

    status, html = http_post_form("/link", {"code": code, "username": username})
    assert status == 200, f"подтверждение кода не удалось: {status} {html[:200]}"

    t, e = await recv_until(ws, {"auth.success"})
    return e["payload"]["access_token"], e["payload"]["refresh_token"], e["payload"]["user"]


async def main():
    print("== Shigu's Dream smoke test ==")

    # --- 1. HTTP API
    status, actions = http_get("/actions")
    action_list = actions if isinstance(actions, list) else actions.get("actions", [])
    check("GET /actions", status == 200 and any(a["id"] == "shigusdream:show_message" for a in action_list))

    # --- 2. Привязка двух клиентов (первый аккаунт становится admin)
    ws_admin = await websockets.connect(WS)
    tok_admin, refresh_admin, user_admin = await link_and_connect(
        ws_admin, "11111111-1111-1111-1111-111111111111", "AdminPlayer", "admin_boss",
    )
    check("admin auth.success + роль admin", user_admin.get("role") == "admin", str(user_admin))

    ws_target = await websockets.connect(WS)
    tok_target, refresh_target, user_target = await link_and_connect(
        ws_target, "22222222-2222-2222-2222-222222222222", "TargetPlayer", "target_player",
    )
    check("target auth.success", user_target.get("username") == "target_player", str(user_target))

    # --- 3. Presence: админ должен видеть target_player online.
    # В буфере ws_admin уже лежат более старые снапшоты — дочитываем их все.
    await ws_admin.send(env("presence.list"))
    users = {}
    for _ in range(10):
        t, e = await recv_until(ws_admin, {"presence.list"})
        users = {u["username"]: u["online"] for u in e["payload"]["users"]}
        if users.get("admin_boss") is True and users.get("target_player") is True:
            break
    check("presence.list видит обоих онлайн", users.get("admin_boss") is True and users.get("target_player") is True, str(users))

    # --- 4. GET /users с Bearer токеном
    status, body = http_get("/users", tok_admin)
    check("GET /users (admin)", status == 200 and len(body.get("users", [])) == 2)

    # --- 5. Доставка action.execute -> результат
    req_id = "smoke-execute-1"
    await ws_admin.send(env("action.execute", {
        "target": "target_player",
        "action": "shigusdream:show_message",
        "args": {"text": "Привет от smoke-теста!", "duration": 100},
        "mode": "immediate",
    }, request_id=req_id))

    t, e = await recv_until(ws_target, {"action.execute"})
    check("target получил action.execute", e["request_id"] == req_id and e["payload"]["action"] == "shigusdream:show_message")
    command_id = e["payload"].get("command_id")

    await ws_target.send(env("action.result", {"action": "shigusdream:show_message", "status": "executed"}, request_id=req_id))
    t, e = await recv_until(ws_admin, {"action.result"})
    check("админ получил action.result executed", e["payload"]["status"] == "executed")

    # --- 6. Команда в БД
    status, cmd = http_get(f"/commands/{command_id}", tok_admin)
    check("GET /commands/{id} status=executed", status == 200 and cmd.get("status") == "executed", str(cmd))

    # --- 7. Дедупликация request_id
    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:show_message",
        "args": {"text": "dup"}, "mode": "immediate",
    }, request_id=req_id))
    t, e = await recv_until(ws_admin, {"action.error"})
    check("дубликат request_id отклонён", e["payload"]["code"] == "duplicate_request", str(e["payload"]))

    # --- 8. target_offline (immediate)
    await ws_target.close()
    await asyncio.sleep(0.3)
    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:play_sound",
        "args": {"sound": "minecraft:entity.player.levelup", "volume": 0.5, "pitch": 1.0},
        "mode": "immediate",
    }, request_id="smoke-offline-1"))
    t, e = await recv_until(ws_admin, {"action.error"})
    check("offline immediate -> target_offline", e["payload"]["code"] == "target_offline", str(e["payload"]))

    # --- 9. queued-доставка после переподключения
    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:show_message",
        "args": {"text": "queued message"}, "mode": "queued",
    }, request_id="smoke-queued-1"))
    t, e = await recv_until(ws_admin, {"action.result"})
    check("queued -> action.result status=queued", e["payload"]["status"] == "queued", str(e["payload"]))

    ws_target2 = await websockets.connect(WS)
    await ws_target2.send(env("auth", {"token": refresh_target}))
    t, e = await recv_until(ws_target2, {"auth.success"})
    check("target reconnect по refresh-токену", True)

    t, e = await recv_until(ws_target2, {"action.execute"})
    check("queued-команда доставлена после подключения", e["request_id"] == "smoke-queued-1", str(e))

    # --- 10. Права: обычный пользователь не может отправлять команды
    await ws_target2.send(env("action.execute", {
        "target": "admin_boss", "action": "shigusdream:show_message",
        "args": {"text": "nope"}, "mode": "immediate",
    }, request_id="smoke-perm-1"))
    t, e = await recv_until(ws_target2, {"action.error"})
    check("user без прав -> no_permission", e["payload"]["code"] == "no_permission", str(e["payload"]))

    # --- 11. Неизвестное действие / невалидные аргументы
    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:not_a_thing", "args": {}, "mode": "immediate",
    }, request_id="smoke-unknown-1"))
    t, e = await recv_until(ws_admin, {"action.error"})
    check("unknown_action", e["payload"]["code"] == "unknown_action")

    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:show_message",
        "args": {"duration": 999999}, "mode": "immediate",
    }, request_id="smoke-badargs-1"))
    t, e = await recv_until(ws_admin, {"action.error"})
    check("invalid_arguments", e["payload"]["code"] == "invalid_arguments", str(e["payload"]))

    # --- 12. Cancel queued-команды
    await ws_target2.close()
    await asyncio.sleep(0.3)
    await ws_admin.send(env("action.execute", {
        "target": "target_player", "action": "shigusdream:show_message",
        "args": {"text": "to be cancelled"}, "mode": "queued",
    }, request_id="smoke-cancel-1"))
    t, e = await recv_until(ws_admin, {"action.result"})
    cancel_cmd = e["payload"]["command_id"]
    status, body = http_post_json(f"/commands/{cancel_cmd}/cancel", {}, token=tok_admin)
    check("POST /commands/{id}/cancel", status == 200 and body.get("status") == "cancelled", str(body))

    # --- 13. GET /users без токена -> 401
    status, _ = http_get("/users")
    check("GET /users без токена -> 401", status == 401)

    # --- 14. Неаутентифицированный WS не может слать команды
    ws_anon = await websockets.connect(WS)
    await ws_anon.send(env("action.execute", {"target": "x", "action": "y", "args": {}}, request_id="anon-1"))
    t, e = await recv_until(ws_anon, {"action.error"})
    check("неаутентифицированный -> not_authenticated", e["payload"]["code"] == "not_authenticated")
    await ws_anon.close()

    await ws_admin.close()

    print(f"\nИтого: {PASSED} passed, {len(FAILED)} failed")
    if FAILED:
        print("Провалены:", ", ".join(FAILED))
        sys.exit(1)
    print("SMOKE TEST OK")


if __name__ == "__main__":
    asyncio.run(main())
