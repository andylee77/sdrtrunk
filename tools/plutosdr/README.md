# PlutoSDR Support for SDRTrunk

This document explains how to use an **ADALM-PlutoSDR** (or compatible AD9361-based device)
with SDRTrunk via a lightweight Python TCP server that bridges the PlutoSDR's libiio interface
to SDRTrunk's network tuner framework.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  Windows PC running SDRTrunk                                        │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  SDRTrunk (Java)                                             │   │
│  │  PlutoSdrTunerController  ──TCP──►  pluto_server.py (Python) │   │
│  │  port 1234 (configurable)          │                         │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                        │ pyadi-iio / libiio         │
│                                        ▼                            │
│                              ┌──────────────────┐                  │
│                              │  PlutoSDR         │                  │
│                              │  192.168.2.1      │                  │
│                              │  (Ethernet)       │                  │
│                              └──────────────────┘                  │
└─────────────────────────────────────────────────────────────────────┘
```

The Python server (`pluto_server.py`) runs on the same Windows PC as SDRTrunk (or on any
machine that can reach both the PlutoSDR and the SDRTrunk PC).  It:

1. Connects to the PlutoSDR over its Ethernet interface using **pyadi-iio**.
2. Listens on a TCP port for SDRTrunk to connect.
3. Receives a JSON configuration command (frequency, sample rate, gain, AGC).
4. Configures the PlutoSDR and streams raw interleaved **signed 16-bit little-endian IQ**
   samples back to SDRTrunk continuously.

---

## Hardware Setup

| Item | Value |
|------|-------|
| PlutoSDR default IP | `192.168.2.1` |
| PC IP (same subnet) | `192.168.2.x` (e.g. `192.168.2.10`) |
| Server default port | `1234` |

Connect the PlutoSDR to your PC via the **Ethernet** port (not USB).  Assign your PC's
Ethernet adapter a static IP in the `192.168.2.0/24` subnet.

Verify connectivity:
```
ping 192.168.2.1
```

---

## Python Server Setup

### 1. Install Python dependencies

```bash
pip install pyadi-iio numpy
```

> **Note:** `pyadi-iio` requires `libiio` to be installed on your system.
> On Windows, download the libiio installer from:
> https://github.com/analogdevicesinc/libiio/releases

### 2. Run the server

```bash
# Basic usage (PlutoSDR at default IP, server on port 1234)
python pluto_server.py

# Custom PlutoSDR IP
python pluto_server.py --pluto-uri ip:192.168.2.1

# Custom port
python pluto_server.py --port 5678

# Debug logging
python pluto_server.py --debug

# All options
python pluto_server.py --help
```

The server will print something like:
```
08:30:00 [INFO] Connecting to PlutoSDR at ip:192.168.2.1 …
08:30:01 [INFO] Connected to PlutoSDR (firmware: v0.37)
08:30:01 [INFO] PlutoSDR IQ server listening on 0.0.0.0:1234
08:30:01 [INFO] Press Ctrl+C to stop.
```

Leave this terminal window open while using SDRTrunk.

---

## SDRTrunk Configuration

### First-time setup

PlutoSDR tuners are **not** auto-discovered (they connect over Ethernet, not USB).
You need to add one manually:

1. Open SDRTrunk.
2. Go to **View → Tuners** (or the Tuners tab in the main window).
3. Click **Add Tuner** (or the `+` button).
4. Select **PlutoSDR** from the tuner type list.
5. In the editor panel that appears, configure:

   | Field | Value |
   |-------|-------|
   | **Server Host** | `localhost` (if server runs on same PC) or the server's IP |
   | **Server Port** | `1234` (must match `--port` used when starting the server) |
   | **Sample Rate** | `2500000` (2.5 MSPS) recommended to start |
   | **RF Bandwidth** | `Auto` (recommended) or a specific value (200 kHz – 56 MHz) |
   | **RF Gain** | `30` dB (adjust as needed; range 0–73 dB) |
   | **AGC** | Unchecked (manual gain) or checked (automatic) |

6. Click **Apply & Reconnect**.
7. The tuner status should change to **Enabled** and the frequency display will appear.
8. The **Device Info** panel at the bottom of the editor will populate with live data
   from the server (model, serial, firmware version, die temperature, RSSI, actual RF BW).

### Subsequent startups

The configuration is saved automatically.  On the next SDRTrunk startup, the PlutoSDR
tuner will appear in the tuner list and attempt to connect to the server automatically.
Make sure `pluto_server.py` is running **before** starting SDRTrunk.

---

## PlutoSDR Specifications (AD9361)

| Parameter | Value |
|-----------|-------|
| Frequency range | 70 MHz – 6 GHz |
| Sample rate | 521 kSPS – 61.44 MSPS |
| ADC resolution | 12 bits (sign-extended to 16 bits) |
| Bandwidth | Up to 56 MHz |
| Interface | Ethernet (default 192.168.2.1) or USB |

---

## Recommended Sample Rates

| Rate | Use case |
|------|----------|
| 2.5 MSPS | P25, DMR, NXDN – good starting point |
| 5 MSPS | Wider coverage, still low CPU |
| 10 MSPS | Multi-channel monitoring |
| 20+ MSPS | Wideband spectrum monitoring |

Higher sample rates require more CPU and network bandwidth.  The Ethernet link on the
standard PlutoSDR is 100 Mbps, which supports up to ~6 MSPS of 16-bit IQ data before
the link becomes the bottleneck.  For higher rates, use the USB interface instead
(`--pluto-uri usb:`).

---

## Troubleshooting

### "Unable to connect to server"
- Verify `pluto_server.py` is running.
- Check the host and port in the SDRTrunk tuner editor match the server's `--host` and `--port`.
- Check Windows Firewall is not blocking port 1234.

### "Failed to configure PlutoSDR"
- Verify the PlutoSDR is powered on and reachable: `ping 192.168.2.1`
- Check the `--pluto-uri` argument matches your PlutoSDR's IP.
- Try `python pluto_server.py --debug` for detailed error messages.

### No signal / very weak signal
- Increase the RF gain in the SDRTrunk tuner editor (try 40–60 dB).
- Enable AGC for automatic gain control.
- Verify the antenna is connected to the **RX** port.

### High CPU usage
- Reduce the sample rate.
- Use the polyphase channelizer (SDRTrunk preference) instead of the heterodyne channelizer.

### SDRTrunk shows "Error" status after reconnect
- The server may have crashed.  Restart `pluto_server.py` and click **Apply & Reconnect**
  in the SDRTrunk tuner editor.

---

## Files

| File | Description |
|------|-------------|
| `pluto_server.py` | Python TCP server – run this on the PC connected to the PlutoSDR |
| `README.md` | This document |

### Java source files added to SDRTrunk

| File | Description |
|------|-------------|
| `src/.../plutosdr/PlutoSdrTunerConfiguration.java` | Persisted tuner settings (host, port, gain, etc.) |
| `src/.../plutosdr/PlutoSdrTunerController.java` | TCP client + IQ streaming engine |
| `src/.../plutosdr/PlutoSdrTuner.java` | Tuner wrapper (name, class, unique ID) |
| `src/.../plutosdr/DiscoveredPlutoSdrTuner.java` | Discovery/lifecycle management |
| `src/.../plutosdr/PlutoSdrTunerEditor.java` | Swing UI editor panel |
| `src/.../buffer/SignedShortNativeBuffer.java` | 16-bit IQ sample buffer (new, shared) |

---

## Protocol Reference

The TCP protocol between SDRTrunk and `pluto_server.py` is intentionally simple so that
alternative server implementations (e.g. GNU Radio, SoapySDR) can be used.

### IQ-stream connection sequence

```
Client (SDRTrunk)                          Server (pluto_server.py)
─────────────────                          ────────────────────────
TCP connect ──────────────────────────────►
                                           accept()
send JSON command + '\n' ────────────────►
  {"freq":101100000,
   "sample_rate":2500000,
   "gain":30,
   "agc":false,
   "rf_bandwidth":0}
                                           configure PlutoSDR
                          ◄────────────── send JSON response + '\n'
                                            {"status":"ok",
                                             "sample_rate":2500000,
                                             "rf_bandwidth":1875000,
                                             "hw_model":"ADALM-PLUTO",
                                             "hw_serial":"...",
                                             "fw_version":"...",
                                             "temperature":42.5,
                                             "rssi":"93.75 dB"}
                          ◄────────────── stream int16 IQ bytes
                          ◄────────────── (continuous until disconnect)
TCP close ────────────────────────────────►
```

### Status-poll connection sequence

SDRTrunk opens a separate short-lived connection every 10 seconds to refresh
the Device Info panel (temperature, RSSI, etc.) without interrupting the IQ stream.

```
Client (SDRTrunk)                          Server (pluto_server.py)
─────────────────                          ────────────────────────
TCP connect ──────────────────────────────►
send JSON command + '\n' ────────────────►
  {"command":"status"}
                          ◄────────────── send JSON response + '\n'
                                            {"status":"ok",
                                             "temperature":43.1,
                                             "rssi":"91.25 dB",
                                             "rf_bandwidth":1875000,
                                             "hw_model":"ADALM-PLUTO",
                                             ...}
                          ◄────────────── server closes connection
```

### JSON IQ-stream command fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `freq` | integer | — | Centre frequency in Hz (required) |
| `sample_rate` | integer | — | Sample rate in samples/second (required) |
| `gain` | integer | 30 | Manual RF gain in dB (0–73) |
| `agc` | boolean | false | `true` = slow-attack AGC, `false` = manual gain |
| `rf_bandwidth` | integer | 0 | RF bandwidth in Hz (200000–56000000); **0 = auto** (~0.75 × sample_rate) |
| `gain_mode` | string | `"slow_attack"` | AGC mode when `agc=true`: `"slow_attack"`, `"fast_attack"`, `"hybrid"` |

### JSON IQ-stream response fields

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"ok"` or `"error"` |
| `sample_rate` | integer | Actual sample rate confirmed by hardware |
| `rf_bandwidth` | integer | Actual RF bandwidth set by the AD9361 driver (Hz) |
| `hw_model` | string | Hardware model string (e.g. `"ADALM-PLUTO"`) |
| `hw_serial` | string | Hardware serial number |
| `fw_version` | string | Firmware version string |
| `temperature` | float | AD9361 die temperature in °C |
| `rssi` | string | RX RSSI as reported by the AD9361 (e.g. `"93.75 dB"`) |
| `message` | string | Error description (on error only) |

### RF Bandwidth notes

The AD9361 RF bandwidth filter is **independent of the sample rate**.  It controls the
analogue anti-aliasing filter before the ADC.

- **Auto (0):** The driver sets the bandwidth to approximately 0.75 × sample_rate.
  This is the recommended setting for most use cases.
- **Manual:** Set a specific value (200 kHz – 56 MHz) to narrow the filter and improve
  adjacent-channel rejection.  The actual value applied by the hardware is reported back
  in the `rf_bandwidth` response field and shown in the **Device Info** panel in SDRTrunk.

### Sample data format

Raw bytes, continuous stream:
- **Encoding:** interleaved signed 16-bit integers, little-endian
- **Order:** I₀, Q₀, I₁, Q₁, I₂, Q₂, …
- **Bytes per complex sample:** 4 (2 bytes I + 2 bytes Q)
- **Buffer size:** 65536 complex samples = 262144 bytes per SDRTrunk read

---

## Device Info Panel

When the PlutoSDR tuner is running, the **Device Info** panel in the SDRTrunk tuner editor
shows live data polled from the server every 10 seconds:

| Field | Description |
|-------|-------------|
| **Model** | Hardware model (e.g. `ADALM-PLUTO`) |
| **Serial** | Hardware serial number |
| **Firmware** | Firmware version |
| **Die Temp** | AD9361 internal temperature sensor (°C) |
| **RSSI** | Received Signal Strength Indicator from the AD9361 |
| **Actual RF BW** | The RF bandwidth actually set by the hardware driver |

The **Actual RF BW** field is especially useful when **RF Bandwidth** is set to **Auto** –
it shows you exactly what bandwidth the driver chose based on your sample rate.
