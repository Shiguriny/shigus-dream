import asyncio, json, sys
import websockets

async def main():
    url = "wss://shigusdream-backend.onrender.com/ws"
    try:
        async with websockets.connect(url) as ws:
            await ws.send(json.dumps({"protocol_version": 1, "message_type": "auth", "payload": {}}))
            raw = await asyncio.wait_for(ws.recv(), timeout=25)
            e = json.loads(raw)
            print("RESP:", e["message_type"], "|", e["payload"].get("code"), "-", e["payload"].get("message"))
    except Exception as ex:
        print("ERROR:", type(ex).__name__, ex)
        sys.exit(1)

asyncio.run(main())
