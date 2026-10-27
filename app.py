"""
Viatom PC-60FW - Kórházi Betegőrző Monitor és Éjszakai Elemző Rendszer
----------------------------------------------------------------------
FastAPI + Uvicorn + WebSocket + Bleak
Valós idejű kórházi ICU felület (60 FPS Plethysmogram + Éjszakai idősáv).
"""

import os
import sys
import time
import json
import asyncio
import threading
import webbrowser
from datetime import datetime
from typing import Optional, List, Dict
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
import uvicorn
from bleak import BleakScanner, BleakClient
from bleak.exc import BleakError, BleakBluetoothNotAvailableError

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
CSV_PATH = os.path.join(BASE_DIR, "meresi_naplo.csv")
DEVICE_CACHE_PATH = os.path.join(BASE_DIR, "device_cache.json")

UART_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
UART_TX_CHAR_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
IHEALTH_PO_SERVICE_UUID = "00000af0-0000-1000-8000-00805f9b34fb"


def load_cached_device():
    """Betölti a lemezről a legutóbb párosított csipesz címét és nevét (Android SharedPreferences mintára)."""
    if os.path.exists(DEVICE_CACHE_PATH):
        try:
            with open(DEVICE_CACHE_PATH, "r", encoding="utf-8") as f:
                d = json.load(f)
                return d.get("address", ""), d.get("name", "")
        except Exception:
            pass
    return "", ""


def save_cached_device(address: str, name: str):
    """Lemezre menti a csipesz hardveres MAC címét a gyorscsatlakozáshoz."""
    if not address:
        return
    try:
        with open(DEVICE_CACHE_PATH, "w", encoding="utf-8") as f:
            json.dump({"address": address, "name": name, "saved_at": datetime.now().isoformat()}, f, indent=2)
    except Exception as e:
        print(f"[CACHE HIBA] {e}")


class MonitorState:
    def __init__(self):
        cached_addr, cached_name = load_cached_device()
        self.ble_connected = False
        self.ble_scanning = False
        self.device_name = cached_name or "Nincs csatlakoztatva"
        self.device_address = cached_addr
        self.ble_status_msg = "CSIPESZ NINCS CSATLAKOZTATVA"
        
        self.recording = False
        self.record_start_time: Optional[float] = None
        self.record_seconds = 0
        
        # Élő orvosi értékek
        self.spo2: Optional[int] = None
        self.pulse: Optional[int] = None
        self.pi: Optional[float] = None
        self.finger_in = False
        self.wave_sample = 0
        self.beat_event = False
        self.synth_beat_latched = False
        self.raw_wave_buffer: List = []
        
        # Éjszakai idősáv adatok
        self.overnight_points: List[Dict] = []
        self.max_points = 43200
        
        # Statisztikák (Zsibbadás)
        self.numb_episodes = 0
        self.numb_total_seconds = 0
        self.in_numb_episode = False
        self.lowest_pi = 100.0
        self.lowest_pi_time = ""

        # Alvási Apnoé (OSAS / ODI) statisztika
        self.spo2_baseline = 97.5
        self.apnea_episodes = 0
        self.in_apnea_episode = False
        self.apnea_total_seconds = 0
        self.lowest_spo2 = 100
        self.lowest_spo2_time = ""
        
        # Aritmia számláló és gyors Bluetooth újracsatlakozás
        self.extra_beats_count = 0
        self.last_device_address = ""
        
        self.ble_task: Optional[asyncio.Task] = None
        self.ble_client: Optional[BleakClient] = None
        self.stop_ble_requested = False


state = MonitorState()
connected_websockets: List[WebSocket] = []
rx_stream_buffer = bytearray()


def ensure_csv_header():
    if not os.path.exists(CSV_PATH):
        with open(CSV_PATH, "w", encoding="utf-8") as f:
            f.write("Időbélyeg;SpO2;Pulzus;PI;Állapot\n")


def append_csv_line(timestamp: str, spo2, pulse, pi, status: str):
    try:
        with open(CSV_PATH, "a", encoding="utf-8") as f:
            f.write(f"{timestamp};{spo2 if spo2 is not None else ''};{pulse if pulse is not None else ''};{pi if pi is not None else ''};{status}\n")
    except Exception as e:
        print(f"[CSV HIBA] {e}")


def update_overnight_stats(spo2, pulse, pi, finger_in):
    now = datetime.now()
    now_str = now.strftime("%H:%M:%S")
    now_full = now.strftime("%Y-%m-%d %H:%M:%S")

    is_numb = False
    is_apnea = False

    # 1. Zsibbadás figyelése (PI < 0.8%)
    if finger_in and pi is not None:
        if pi < 0.8:
            is_numb = True
            state.numb_total_seconds += 1
            if not state.in_numb_episode:
                state.in_numb_episode = True
                state.numb_episodes += 1
            if pi < state.lowest_pi:
                state.lowest_pi = pi
                state.lowest_pi_time = now_str
        else:
            state.in_numb_episode = False

    # 2. Alvási Apnoé / Deszaturáció figyelése (AASM szabvány)
    if finger_in and spo2 is not None:
        # Tanuló nyugalmi alapvonal (baseline)
        state.spo2_baseline = state.spo2_baseline * 0.995 + spo2 * 0.005
        if spo2 < state.lowest_spo2:
            state.lowest_spo2 = spo2
            state.lowest_spo2_time = now_str

        # Deszaturáció: legalább 3%-os esés az alapvonalhoz képest VAGY 91% alatti oxigén
        if (spo2 <= state.spo2_baseline - 3.0) or (spo2 < 91):
            is_apnea = True
            state.apnea_total_seconds += 1
            if not state.in_apnea_episode:
                state.in_apnea_episode = True
                state.apnea_episodes += 1
        elif spo2 >= state.spo2_baseline - 1.0:
            state.in_apnea_episode = False

    point = {
        "time": now_str,
        "full_time": now_full,
        "spo2": spo2 if finger_in else None,
        "pulse": pulse if finger_in else None,
        "pi": pi if finger_in else None,
        "finger_in": finger_in,
        "is_numb": is_numb,
        "is_apnea": is_apnea,
        "is_hypoxia": (finger_in and spo2 is not None and spo2 < 90)
    }
    
    state.overnight_points.append(point)
    if len(state.overnight_points) > state.max_points:
        state.overnight_points.pop(0)

    if state.recording:
        state.record_seconds = int(time.time() - (state.record_start_time or time.time()))
        events = []
        if is_numb: events.append("ZSIBBADÁS (PI < 0.8%)")
        if is_apnea: events.append("ALVÁSI APNOÉ (SpO2 esés)")
        if finger_in and spo2 is not None and spo2 < 90: events.append("HIPOXIA (<90%)")
        if not finger_in: events.append("CSIPESZ LEVÉVE")
        status_txt = " | ".join(events) if events else "NORMÁL"
        append_csv_line(now_full, spo2, pulse, pi, status_txt)


async def broadcast_tick():
    """Valós idejű WebSocket adatküldés a frontendnek."""
    if not connected_websockets:
        return
    
    rec_hrs = max(state.record_seconds, 60) / 3600.0
    odi = round(state.apnea_episodes / rec_hrs, 1) if state.recording else 0.0
    if odi < 5.0:
        apnea_sev = "NORMÁL (<5/h)"
    elif odi < 15.0:
        apnea_sev = "ENYHE APNOÉ"
    elif odi < 30.0:
        apnea_sev = "KÖZEPES APNOÉ"
    else:
        apnea_sev = "SÚLYOS APNOÉ"

    payload = {
        "type": "live",
        "ble_connected": state.ble_connected,
        "ble_scanning": state.ble_scanning,
        "device_name": state.device_name,
        "ble_status_msg": state.ble_status_msg,
        "recording": state.recording,
        "record_seconds": state.record_seconds,
        "finger_in": state.finger_in,
        "spo2": state.spo2,
        "pulse": state.pulse,
        "pi": state.pi,
        "wave": state.wave_sample,
        "beat": state.beat_event,
        "stats": {
            "episodes": state.numb_episodes,
            "total_numb_minutes": round(state.numb_total_seconds / 60.0, 1),
            "lowest_pi": None if state.lowest_pi == 100.0 else state.lowest_pi,
            "lowest_pi_time": state.lowest_pi_time,
            "apnea_episodes": state.apnea_episodes,
            "odi": odi,
            "apnea_severity": apnea_sev,
            "lowest_spo2": None if state.lowest_spo2 == 100 else state.lowest_spo2,
            "lowest_spo2_time": state.lowest_spo2_time,
            "extra_beats": state.extra_beats_count
        }
    }
    state.beat_event = False
    
    dead = []
    for ws in connected_websockets:
        try:
            await ws.send_json(payload)
        except Exception:
            dead.append(ws)
    for ws in dead:
        if ws in connected_websockets:
            connected_websockets.remove(ws)


def process_ble_stream(chunk: bytearray):
    """
    Viatom / Creative Medical PC-60FW adatfolyam illesztő és csomagkeretező.
    Kezeli a 20 bájtos BLE szeleteket, és szétválasztja az OLED numerikus
    paramétereket (sub-id 0x01) és a valós Pleth hullámformát (sub-id 0x02)!
    """
    global rx_stream_buffer
    rx_stream_buffer.extend(chunk)

    while len(rx_stream_buffer) >= 4:
        # 1. Keresünk fejlécet (0xAA 0x55)
        idx = rx_stream_buffer.find(b'\xAA\x55')
        if idx < 0:
            # Nincs fejléc a pufferben, megtartjuk az utolsó bájtot
            rx_stream_buffer = rx_stream_buffer[-1:]
            break
        
        # Szemét levágása a fejléc előtt
        if idx > 0:
            del rx_stream_buffer[0:idx]

        if len(rx_stream_buffer) < 4:
            break

        token = rx_stream_buffer[2]
        length = rx_stream_buffer[3]
        packet_len = 4 + length

        # Ellenőrizzük, hogy megérkezett-e a teljes csomag
        if len(rx_stream_buffer) < packet_len:
            break  # Várjuk a következő BLE szeletet!

        # Teljes csomag kivágása
        pkt = rx_stream_buffer[0:packet_len]
        del rx_stream_buffer[0:packet_len]

        # 0x0F: Creative Medical Telemetria Csomag
        if token == 0x0F and len(pkt) >= 5:
            sub_id = pkt[4]

            # SUB-ID 0x01: OLED Numerikus Paraméterek (SpO2, Pulzus, PI)
            if sub_id == 0x01 and len(pkt) >= 9:
                s = pkt[5]
                pr = pkt[6]
                
                # PI (Perfúziós Index / 10)
                if len(pkt) > 8:
                    p_index = round(pkt[8] / 10.0, 1) if pkt[8] != 0xFF else 0.0
                else:
                    p_index = 0.0

                # Csipesz ujj érzékelés (ha nincs ujj, 0xFF vagy 0 érkezik)
                finger = not (s == 0xFF or s == 0 or pr == 0xFF or pr == 0)
                state.finger_in = finger

                if finger:
                    state.spo2 = s
                    state.pulse = pr
                    state.pi = p_index
                    state.ble_status_msg = "MÉRÉS FOLYAMATBAN (ÉLŐ JEL)"
                else:
                    state.spo2 = None
                    state.pulse = None
                    state.pi = None
                    state.ble_status_msg = "CSIPESZ LEVÉVE AZ UJJRÓL"

                update_overnight_stats(state.spo2, state.pulse, state.pi, finger)

            # SUB-ID 0x02: Valós Plethysmogram Pulzushullám Minták!
            elif sub_id == 0x02:
                # A csomagban lévő bájtok a nyers optikai pulzushullám minták (5-8 minta/csomag)
                # Az utolsó bájt a Maxim CRC8
                samples = pkt[5:-1]
                for raw_s in samples:
                    # Bit 7 (0x80): Szisztolés pulzuscsúcs / R-hullám hardveres trigger!
                    is_beat = bool(raw_s & 0x80)
                    amp = raw_s & 0x7F
                    # Nyers 7-bites optikai érték továbbítása levágás nélkül
                    state.raw_wave_buffer.append((amp, is_beat))


def ble_notification_callback(sender, data: bytearray):
    process_ble_stream(data)


async def ensure_bluetooth_radio_on() -> bool:
    """Windows Bluetooth rádió automatikus bekapcsolása WinRT API-n keresztül."""
    try:
        import winrt.windows.devices.radios as radios
        rads = await radios.Radio.get_radios_async()
        for r in rads:
            if r.kind == radios.RadioKind.BLUETOOTH:
                if r.state != radios.RadioState.ON:
                    state.ble_status_msg = "Bluetooth rádió automatikus bekapcsolása..."
                    await broadcast_tick()
                    print("[BLE] Bluetooth rádió bekapcsolása a PC-n...")
                    await r.set_state_async(radios.RadioState.ON)
                    await asyncio.sleep(1.0)
                return True
    except Exception as e:
        print(f"[BLE RÁDIÓ HIBA] {e}")
    return False


async def ble_worker():
    state.stop_ble_requested = False
    direct_attempts = 0
    
    # Betöltjük a lemezről a korábbi mentett címet (Android mintára)
    if not state.last_device_address:
        cached_addr, cached_name = load_cached_device()
        if cached_addr:
            state.last_device_address = cached_addr
            state.device_name = cached_name or "Viatom PC-60FW"
            print(f"[BLE] Megjegyzett Viatom PC-60FW hardvercím betöltve: {cached_addr}")

    while not state.stop_ble_requested:
        # 1. Automatikusan bekapcsoljuk a PC Bluetooth rádióját, ha ki lenne kapcsolva!
        await ensure_bluetooth_radio_on()

        # 2. AZONNALI DIREKT GYORSCSATLAKOZÁS (Ha már ismerjük a csipesz címét, keresés nélkül!)
        if state.last_device_address and direct_attempts < 2:
            direct_attempts += 1
            state.ble_scanning = True
            state.ble_status_msg = f"AZONNALI GYORS ÚJRACSATLAKOZÁS ({direct_attempts}/2)..."
            await broadcast_tick()
            print(f"[BLE] Gyorscsatlakozási kísérlet a csipeszhez ({state.last_device_address})...")
            try:
                async with BleakClient(state.last_device_address, timeout=2.8) as client:
                    if client.is_connected:
                        direct_attempts = 0  # Sikerült, számláló nullázva
                        state.ble_client = client
                        state.ble_connected = True
                        state.ble_scanning = False
                        state.device_name = "Viatom PC-60FW"
                        state.device_address = state.last_device_address
                        save_cached_device(state.last_device_address, state.device_name)
                        state.ble_status_msg = f"CSATLAKOZVA: {state.device_name}"
                        await broadcast_tick()
                        print(f"[BLE GYORS SIKER] Csatlakozva: {state.device_name} ({state.last_device_address})!")

                        await client.start_notify(UART_TX_CHAR_UUID, ble_notification_callback)
                        while client.is_connected and not state.stop_ble_requested:
                            await asyncio.sleep(0.3)
                        await client.stop_notify(UART_TX_CHAR_UUID)
            except Exception as e:
                print(f"[BLE GYORS HIBA] {e}")
            finally:
                state.ble_connected = False
                state.ble_client = None
                state.finger_in = False
                state.spo2 = None
                state.pulse = None
                state.pi = None
                await broadcast_tick()

            if state.stop_ble_requested:
                state.ble_status_msg = "KAPCSOLAT BONTVA"
                await broadcast_tick()
                break
            elif direct_attempts < 2:
                await asyncio.sleep(0.4)
                continue

        # 3. KÖRNYEZETI FELDERÍTŐ KERESÉS (Ha nincs még cím vagy a direkt reconnect 2x nem sikerült)
        direct_attempts = 0
        state.ble_scanning = True
        state.ble_status_msg = "BLUETOOTH CSIPESZ KERESÉSE (Helyezze az ujjára)..."
        await broadcast_tick()
        print("[BLE] Bluetooth környezeti felderítés...")

        try:
            discovered = await BleakScanner.discover(timeout=4.0, return_adv=True)
        except BleakBluetoothNotAvailableError:
            print("[BLE] Rádió nem elérhető, újrapróbálkozás...")
            await ensure_bluetooth_radio_on()
            await asyncio.sleep(1.5)
            continue
        except Exception as e:
            state.ble_scanning = False
            state.ble_status_msg = f"Keresési hiba: {e}"
            await broadcast_tick()
            await asyncio.sleep(1.5)
            continue

        target_device = None
        for addr, (d, adv) in discovered.items():
            name = (d.name or adv.local_name or "").upper()
            uuids = [str(u).lower() for u in (adv.service_uuids or [])]
            if any(k in name for k in ["PC-60", "PC60", "VIATOM", "CREATIVE", "LEPU", "O2"]) or UART_SERVICE_UUID.lower() in uuids:
                target_device = d
                break

        state.ble_scanning = False

        if not target_device:
            state.ble_status_msg = "NEM TALÁLHATÓ CSIPESZ! Kapcsolja be az ujján..."
            await broadcast_tick()
            print("[BLE] Csipesz nem található, újrapróbálkozás 1.5 mp múlva...")
            await asyncio.sleep(1.5)
            continue

        state.device_name = target_device.name or "Viatom PC-60FW"
        state.device_address = target_device.address
        state.last_device_address = target_device.address
        save_cached_device(target_device.address, state.device_name)

        state.ble_status_msg = f"CSATLAKOZÁS: {state.device_name}..."
        await broadcast_tick()
        print(f"[BLE] Csatlakozás: {state.device_name} ({target_device.address})...")

        try:
            async with BleakClient(target_device.address, timeout=3.5) as client:
                if client.is_connected:
                    state.ble_client = client
                    state.ble_connected = True
                    state.ble_status_msg = f"CSATLAKOZVA: {state.device_name}"
                    await broadcast_tick()
                    print(f"[BLE SIKER] Csatlakozva: {state.device_name}!")

                    await client.start_notify(UART_TX_CHAR_UUID, ble_notification_callback)

                    while client.is_connected and not state.stop_ble_requested:
                        await asyncio.sleep(0.3)

                    await client.stop_notify(UART_TX_CHAR_UUID)
        except Exception as e:
            print(f"[BLE KAPCSOLAT HIBA] {e}")
            state.ble_status_msg = f"Kapcsolati hiba: {e}"
        finally:
            state.ble_connected = False
            state.ble_client = None
            state.finger_in = False
            state.spo2 = None
            state.pulse = None
            state.pi = None
            await broadcast_tick()

        if state.stop_ble_requested:
            state.ble_status_msg = "KAPCSOLAT BONTVA"
            await broadcast_tick()
            break
        else:
            state.ble_status_msg = "KAPCSOLAT MEGSZAKADT, AZONNALI ÚJRACSATLAKOZÁS..."
            await broadcast_tick()
            await asyncio.sleep(0.4)


async def wave_ticker():
    t = 0.0
    while True:
        await asyncio.sleep(0.02)  # 50 FPS folytonos hullám rajzolás!
        t += 0.02
        beat = False
        
        # 1. Valódi optikai hullámminták lejátszása a csipeszből
        if state.raw_wave_buffer:
            item = state.raw_wave_buffer.pop(0)
            if isinstance(item, tuple):
                state.wave_sample, beat = item
            else:
                state.wave_sample = item
        elif state.finger_in:
            # Puffer átmeneti kiürülése esetén (BLE jitter): megtartjuk az előző mintát enyhe simító lecsengéssel
            # SOHA NEM szúrunk be mű szinuszhullámot!
            state.wave_sample = int(state.wave_sample * 0.98)
        else:
            state.wave_sample = 0  # Csipesz levéve / nincs ujj: sima nyugvó alapvonal

        if beat:
            state.beat_event = True

        await broadcast_tick()


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_csv_header()
    ticker_task = asyncio.create_task(wave_ticker())
    # Automatikus Bluetooth rádió bekapcsolás és keresés már indításkor!
    state.ble_task = asyncio.create_task(ble_worker())
    yield
    ticker_task.cancel()
    state.stop_ble_requested = True
    if state.ble_task:
        state.ble_task.cancel()


app = FastAPI(title="Viatom PC-60FW Hospital Monitor", lifespan=lifespan)


@app.get("/", response_class=HTMLResponse)
async def get_index():
    index_file = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_file):
        with open(index_file, "r", encoding="utf-8") as f:
            return f.read()
    return "<h1>Betegmonitor index.html nem található!</h1>"


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_websockets.append(websocket)
    await websocket.send_json({
        "type": "history",
        "points": state.overnight_points[-3600:]
    })
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        if websocket in connected_websockets:
            connected_websockets.remove(websocket)


@app.post("/api/ble/toggle")
async def ble_toggle():
    if state.ble_connected:
        state.stop_ble_requested = True
        state.ble_status_msg = "Kapcsolat bontása..."
        await broadcast_tick()
        return {"status": "disconnecting"}
    else:
        if not state.ble_scanning:
            state.ble_task = asyncio.create_task(ble_worker())
            return {"status": "scanning"}
        return {"status": "already_scanning"}


@app.post("/api/record/toggle")
async def record_toggle():
    state.recording = not state.recording
    if state.recording:
        state.record_start_time = time.time()
        state.record_seconds = 0
        state.numb_episodes = 0
        state.numb_total_seconds = 0
        state.lowest_pi = 100.0
        state.lowest_pi_time = ""
        ensure_csv_header()
        return {"recording": True, "msg": "Éjszakai felvétel elindult"}
    else:
        return {
            "recording": False,
            "msg": "Éjszakai felvétel leállítva",
            "summary": {
                "duration_seconds": state.record_seconds,
                "episodes": state.numb_episodes,
                "total_numb_minutes": round(state.numb_total_seconds / 60.0, 1),
                "lowest_pi": None if state.lowest_pi == 100.0 else state.lowest_pi,
                "lowest_pi_time": state.lowest_pi_time
            }
        }


@app.post("/api/simulate")
async def simulate(mode: str = "normal"):
    if mode == "normal":
        state.finger_in = True
        state.ble_connected = True
        state.device_name = "Viatom Szimulátor"
        state.spo2 = 98
        state.pulse = 74
        state.pi = 2.6
        state.ble_status_msg = "SZIMULÁCIÓ: NORMÁL KERINGÉS"
    elif mode == "numb":
        state.finger_in = True
        state.ble_connected = True
        state.device_name = "Viatom Szimulátor"
        state.spo2 = 96
        state.pulse = 68
        state.pi = 0.4
        state.ble_status_msg = "SZIMULÁCIÓ: ZSIBBADÁS DETEKTÁLVA"
    elif mode == "off":
        state.finger_in = False
        state.ble_connected = False
        state.spo2 = None
        state.pulse = None
        state.pi = None
        state.ble_status_msg = "SZIMULÁCIÓ: CSIPESZ LEVÉVE"
    update_overnight_stats(state.spo2, state.pulse, state.pi, state.finger_in)
    await broadcast_tick()
    return {"mode": mode, "spo2": state.spo2, "pulse": state.pulse, "pi": state.pi}


def auto_open_browser():
    time.sleep(1.2)
    webbrowser.open("http://localhost:8000")


if __name__ == "__main__":
    print("\n" + "=" * 65)
    print("   VIATOM PC-60FW KÓRHÁZI INTENZÍV OSZTÁLYOS BETEGMONITOR")
    print("=" * 65)
    print("Indítás a böngészőben: http://localhost:8000")
    print("A leállításhoz nyomjon Ctrl+C-t.\n")
    
    threading.Thread(target=auto_open_browser, daemon=True).start()
    uvicorn.run(app, host="127.0.0.1", port=8000, log_level="warning")
