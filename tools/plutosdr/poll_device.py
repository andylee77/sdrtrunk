#!/usr/bin/env python3
"""
poll_device.py  –  Discover all PlutoSDR capabilities and validate pluto_server.py constants.
"""
import sys
import adi

URI = "ip:192.168.120.110"

print(f"Connecting to {URI} ...")
sdr = adi.Pluto(uri=URI)
print("Connected.\n")

# ── Context attributes ──────────────────────────────────────────────────────
print("=" * 60)
print("CONTEXT ATTRIBUTES")
print("=" * 60)
for k, v in sdr._ctx.attrs.items():
    # In newer pyadi-iio ctx attrs are plain strings
    val = v if isinstance(v, str) else v.value
    print(f"  {k}: {val}")

# ── Devices ─────────────────────────────────────────────────────────────────
print()
print("=" * 60)
print("IIO DEVICES")
print("=" * 60)
for dev in sdr._ctx.devices:
    print(f"  {dev.id}: {dev.name}")

# ── AD9361-PHY device attributes ────────────────────────────────────────────
print()
print("=" * 60)
print("AD9361-PHY DEVICE ATTRIBUTES")
print("=" * 60)
for k, v in sdr._ctrl.attrs.items():
    try:
        print(f"  {k}: {v.value}")
    except Exception as e:
        print(f"  {k}: ERROR({e})")

# ── AD9361-PHY channels ─────────────────────────────────────────────────────
print()
print("=" * 60)
print("AD9361-PHY CHANNELS")
print("=" * 60)
for ch in sdr._ctrl.channels:
    direction = "output" if ch.output else "input"
    print(f"\n  [{direction}] id={ch.id}  name={ch.name}")
    for k, v in ch.attrs.items():
        try:
            print(f"    {k}: {v.value}")
        except Exception as e:
            print(f"    {k}: ERROR({e})")

# ── Key limits extracted and compared to pluto_server.py constants ──────────
print()
print("=" * 60)
print("KEY LIMITS (from device) vs pluto_server.py CONSTANTS")
print("=" * 60)

def parse_range(s):
    """Parse '[min step max]' → (min, step, max) or None."""
    s = s.strip()
    if s.startswith("[") and s.endswith("]"):
        parts = s[1:-1].split()
        if len(parts) == 3:
            return tuple(int(x) for x in parts)
    return None

def parse_list(s):
    """Parse 'a b c ...' → list of values."""
    return s.strip().split()

checks = []

# RX LO frequency range
try:
    rx_lo_ch = next(c for c in sdr._ctrl.channels if c.id == "altvoltage0")
    rx_lo_avail = rx_lo_ch.attrs["frequency_available"].value
    r = parse_range(rx_lo_avail)
    if r:
        checks.append(("MIN_FREQ", r[0], 70_000_000))
        checks.append(("MAX_FREQ", r[2], 6_000_000_000))
    print(f"  RX LO frequency_available: {rx_lo_avail}")
except Exception as e:
    print(f"  RX LO frequency_available: ERROR({e})")

# RX gain range
try:
    rx_ch = next(c for c in sdr._ctrl.channels if c.id == "voltage0" and not c.output)
    gain_avail = rx_ch.attrs["hardwaregain_available"].value
    print(f"  RX hardwaregain_available: {gain_avail}")
    # Format: "[-1 1 73]"
    r = parse_range(gain_avail)
    if r:
        checks.append(("MIN_GAIN", r[0], -1))
        checks.append(("MAX_GAIN", r[2], 73))
except Exception as e:
    print(f"  RX hardwaregain_available: ERROR({e})")

# RX RF bandwidth range
try:
    rf_bw_avail = rx_ch.attrs["rf_bandwidth_available"].value
    print(f"  RX rf_bandwidth_available: {rf_bw_avail}")
    r = parse_range(rf_bw_avail)
    if r:
        checks.append(("MIN_RF_BW", r[0], 200_000))
        checks.append(("MAX_RF_BW", r[2], 56_000_000))
except Exception as e:
    print(f"  RX rf_bandwidth_available: ERROR({e})")

# RX sample rate range
try:
    sr_avail = rx_ch.attrs["sampling_frequency_available"].value
    print(f"  RX sampling_frequency_available: {sr_avail}")
    r = parse_range(sr_avail)
    if r:
        checks.append(("MIN_SAMPLE_RATE", r[0], 521_000))
        checks.append(("MAX_SAMPLE_RATE", r[2], 61_440_000))
except Exception as e:
    print(f"  RX sampling_frequency_available: ERROR({e})")

# Gain control modes
try:
    gc_avail = rx_ch.attrs["gain_control_mode_available"].value
    print(f"  gain_control_mode_available: {gc_avail}")
    modes = parse_list(gc_avail)
    print(f"  → Valid gain modes: {modes}")
except Exception as e:
    print(f"  gain_control_mode_available: ERROR({e})")

# RX port options
try:
    port_avail = rx_ch.attrs["rf_port_select_available"].value
    print(f"  RX rf_port_select_available: {port_avail}")
except Exception as e:
    print(f"  RX rf_port_select_available: ERROR({e})")

# Current operating values
print()
print("  --- Current operating values ---")
try:
    print(f"  rx_lo:              {sdr.rx_lo} Hz")
    print(f"  sample_rate:        {sdr.sample_rate} Hz")
    print(f"  rx_rf_bandwidth:    {sdr.rx_rf_bandwidth} Hz")
    print(f"  gain_control_mode:  {sdr.gain_control_mode_chan0}")
    print(f"  rx_hardwaregain:    {sdr.rx_hardwaregain_chan0} dB")
except Exception as e:
    print(f"  ERROR reading current values: {e}")

# ── Validation summary ───────────────────────────────────────────────────────
print()
print("=" * 60)
print("VALIDATION SUMMARY")
print("=" * 60)
all_ok = True
for name, device_val, code_val in checks:
    match = "OK" if device_val == code_val else "MISMATCH *** UPDATE CODE ***"
    if device_val != code_val:
        all_ok = False
    print(f"  {name}: device={device_val}  code={code_val}  [{match}]")

if all_ok:
    print("\n  All constants match the device. No code changes needed.")
else:
    print("\n  *** Some constants differ from device values – see above ***")
