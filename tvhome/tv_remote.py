"""Wi-Fi remote for the Chromecast (Android TV Remote protocol v2), for when the physical remote is gone.

  py tv_remote.py pair            -> asks the TV to show a pairing code, waits for it in <state>/code.txt
  py tv_remote.py keys K1 K2 ...  -> sends key presses (e.g. SETTINGS DPAD_DOWN DPAD_CENTER BACK HOME)
"""
import asyncio
import os
import sys
import time

from androidtvremote2 import AndroidTVRemote

HOST = "192.168.0.126"
STATE = os.path.join(os.environ["TEMP"], "atvr")
CERT, KEY, CODE = (os.path.join(STATE, n) for n in ("cert.pem", "key.pem", "code.txt"))


def remote() -> AndroidTVRemote:
    return AndroidTVRemote(client_name="PC Remote", certfile=CERT, keyfile=KEY, host=HOST)


async def pair() -> None:
    r = remote()
    await r.async_generate_cert_if_missing()
    if os.path.exists(CODE):
        os.remove(CODE)
    await r.async_start_pairing()
    print("PAIRING STARTED: code should be on the TV", flush=True)
    deadline = time.time() + 600
    while not os.path.exists(CODE):
        if time.time() > deadline:
            sys.exit("timed out waiting for code.txt")
        await asyncio.sleep(1)
    with open(CODE) as f:
        code = f.read().strip()
    await r.async_finish_pairing(code)
    print("PAIRED", flush=True)


async def keys(names: list[str]) -> None:
    r = remote()
    await r.async_connect()
    for name in names:
        if name.startswith("link:"):
            r.send_launch_app_command(name[5:])
            await asyncio.sleep(1)
            continue
        if name.startswith("wait"):
            await asyncio.sleep(float(name[4:] or 1))
            continue
        r.send_key_command(name)
        await asyncio.sleep(0.35)
    await asyncio.sleep(0.5)
    r.disconnect()
    print("sent:", " ".join(names), flush=True)


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    asyncio.run(pair() if cmd == "pair" else keys(args))
