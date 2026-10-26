import os
import sys
import json
import time
import base64
import hashlib
import threading
import asyncio
import ctypes
import win32gui
import win32con
import win32clipboard
from ably import AblyRealtime
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageDraw
import pystray

ABLY_API_KEY = "ca7YEA.YRpXLw:CnnU8B8-VhcmBWb30ljl4uXWVEaxu6OLu8sNglwAW80"
CHANNEL_NAME = "cross-clip-sync"
DEVICE_ID = "windows_pc"

# AES-256-GCM Key derived from Ably Key's secret component for End-to-End Encryption
ENCRYPTION_SECRET = ABLY_API_KEY.split(":")[-1]
AES_KEY = hashlib.sha256(ENCRYPTION_SECRET.encode("utf-8")).digest()
aesgcm = AESGCM(AES_KEY)

def encrypt_text(text: str) -> str:
    nonce = os.urandom(12)
    ct = aesgcm.encrypt(nonce, text.encode("utf-8"), None)
    return base64.b64encode(nonce + ct).decode("utf-8")

def decrypt_text(blob: str) -> str:
    raw = base64.b64decode(blob)
    nonce = raw[:12]
    ct = raw[12:]
    pt = aesgcm.decrypt(nonce, ct, None)
    return pt.decode("utf-8")

# Win32 Clipboard APIs
user32 = ctypes.windll.user32
AddClipboardFormatListener = user32.AddClipboardFormatListener
RemoveClipboardFormatListener = user32.RemoveClipboardFormatListener
WM_CLIPBOARDUPDATE = 0x031D

class ClipboardBridge:
    def __init__(self):
        self.last_hash = ""
        self.lock = threading.Lock()
        self.async_loop = None
        self.channel = None

    def get_clipboard_text(self):
        text = ""
        for _ in range(5):
            try:
                win32clipboard.OpenClipboard()
                if win32clipboard.IsClipboardFormatAvailable(win32con.CF_UNICODETEXT):
                    text = win32clipboard.GetClipboardData(win32con.CF_UNICODETEXT)
                win32clipboard.CloseClipboard()
                break
            except Exception:
                time.sleep(0.05)
        return text

    def set_clipboard_text(self, text: str, incoming_hash: str):
        with self.lock:
            self.last_hash = incoming_hash
            for _ in range(5):
                try:
                    win32clipboard.OpenClipboard()
                    win32clipboard.EmptyClipboard()
                    win32clipboard.SetClipboardData(win32con.CF_UNICODETEXT, text)
                    win32clipboard.CloseClipboard()
                    print("[+] Silently updated Windows clipboard with remote text!")
                    break
                except Exception:
                    time.sleep(0.05)

    def on_local_clipboard_changed(self):
        text = self.get_clipboard_text()
        if not text:
            return

        h = hashlib.sha256(text.encode("utf-8")).hexdigest()
        with self.lock:
            if h == self.last_hash:
                return  # Skip echo loop
            self.last_hash = h

        try:
            encrypted_blob = encrypt_text(text)
            payload = json.dumps({
                "sender": DEVICE_ID,
                "hash": h,
                "payload": encrypted_blob
            })
            if self.channel and self.async_loop and self.async_loop.is_running():
                asyncio.run_coroutine_threadsafe(
                    self.channel.publish("clip_sync", payload),
                    self.async_loop
                )
                print(f"[+] Outbound clipboard synced: {len(text)} chars (Hash: {h[:8]}...)")
        except Exception as e:
            print(f"[!] Encryption/Publish error: {e}")

bridge = ClipboardBridge()

# Win32 Hidden Window Event Loop
def wnd_proc(hwnd, msg, wparam, lparam):
    if msg == WM_CLIPBOARDUPDATE:
        bridge.on_local_clipboard_changed()
        return 0
    return win32gui.DefWindowProc(hwnd, msg, wparam, lparam)

def run_win32_listener():
    wc = win32gui.WNDCLASS()
    wc.lpfnWndProc = wnd_proc
    wc.lpszClassName = "CrossClipNativeListener"
    hinst = win32gui.GetModuleHandle(None)
    wc.hInstance = hinst

    try:
        win32gui.RegisterClass(wc)
    except Exception:
        pass

    hwnd = win32gui.CreateWindow(
        "CrossClipNativeListener",
        "CrossClipHiddenWindow",
        0, 0, 0, 0, 0,
        0, 0, hinst, None
    )

    AddClipboardFormatListener(hwnd)
    try:
        win32gui.PumpMessages()
    finally:
        RemoveClipboardFormatListener(hwnd)
        win32gui.DestroyWindow(hwnd)

# Ably Realtime Worker
async def ably_worker():
    bridge.async_loop = asyncio.get_running_loop()
    client = AblyRealtime(key=ABLY_API_KEY)
    channel = client.channels.get(CHANNEL_NAME)
    bridge.channel = channel

    async def on_message(msg):
        try:
            data = json.loads(msg.data)
            sender = data.get("sender")
            if sender == DEVICE_ID:
                return  # Ignore messages sent by this PC

            incoming_hash = data.get("hash")
            encrypted_payload = data.get("payload")
            if incoming_hash and encrypted_payload:
                plain_text = decrypt_text(encrypted_payload)
                bridge.set_clipboard_text(plain_text, incoming_hash)
        except Exception as e:
            print(f"[!] Incoming message error: {e}")

    await channel.subscribe(on_message)
    print("[+] CrossClip connected to Ably cloud relay! Ready for sync.")

    # Keep alive indefinitely
    while True:
        await asyncio.sleep(3600)

def start_ably_thread():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(ably_worker())

# Tray Icon Generator
def create_tray_image():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([12, 10, 52, 58], radius=6, fill=(41, 128, 185))
    d.rounded_rectangle([18, 18, 46, 52], radius=3, fill=(255, 255, 255))
    d.rounded_rectangle([24, 6, 40, 14], radius=3, fill=(52, 73, 94))
    d.line([22, 26, 42, 26], fill=(189, 195, 199), width=2)
    d.line([22, 34, 42, 34], fill=(189, 195, 199), width=2)
    d.line([22, 42, 36, 42], fill=(189, 195, 199), width=2)
    return img

def main():
    # Start Win32 listener thread
    t_win = threading.Thread(target=run_win32_listener, daemon=True)
    t_win.start()

    # Start Ably cloud thread
    t_ably = threading.Thread(target=start_ably_thread, daemon=True)
    t_ably.start()

    def on_exit(icon, item):
        icon.stop()
        sys.exit(0)

    icon = pystray.Icon(
        "CrossClip",
        create_tray_image(),
        "CrossClip: Active & Synced",
        menu=pystray.Menu(
            pystray.MenuItem("CrossClip (Connected)", None, enabled=False),
            pystray.MenuItem("Exit", on_exit)
        )
    )
    icon.run()

if __name__ == "__main__":
    main()
