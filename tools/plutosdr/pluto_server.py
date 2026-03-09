#!/usr/bin/env python3
"""
pluto_server.py  –  PlutoSDR IQ-stream TCP server for SDRTrunk
================================================================
Connects to a PlutoSDR over its Ethernet interface (default 192.168.2.1)
using the pyadi-iio / libiio Python bindings and streams raw interleaved
signed 16-bit little-endian IQ samples to SDRTrunk over a plain TCP socket.

Requirements
------------
    pip install pyadi-iio numpy

The PlutoSDR must be reachable on the network.  The default IP address
assigned by the PlutoSDR firmware is 192.168.2.1.  Your PC must be on the
same subnet (e.g. 192.168.2.x/24).

Usage
-----
    python pluto_server.py [--pluto-uri ip:192.168.2.1] [--host 0.0.0.0] [--port 1234]
    python pluto_server.py --format cs8 --pluto-uri ip:192.168.2.1 --max-rate 35000000 --port 5678
    
Protocol (client → server)
--------------------------
1. Client connects.
2. Client sends one UTF-8 JSON line terminated with '\\n'.

   IQ-stream command (normal operation):
       {"freq":101100000,"sample_rate":2500000,"gain":30,"agc":false,
        "rf_bandwidth":0}
   Optional fields:
       "gain_mode"    – "manual","fast_attack","slow_attack","hybrid"
       "rf_bandwidth" – Hz, 200000–56000000; 0 (or omitted) = auto (~0.75×SR)

   Status-only command (short-lived connection, no IQ stream):
       {"command":"status"}

3. Server responds with one UTF-8 JSON line terminated with '\\n'.

   For an IQ-stream command:
       {"status":"ok","sample_rate":2500000,"rf_bandwidth":1875000,
        "hw_model":"ADALM-PLUTO","hw_serial":"...","fw_version":"...",
        "temperature":42.5,"rssi":"93.75 dB"}
   or on error:
       {"status":"error","message":"..."}

   For a status command:
       {"status":"ok","temperature":42.5,"rssi":"93.75 dB",
        "rf_bandwidth":1875000,"hw_model":"ADALM-PLUTO",
        "hw_serial":"...","fw_version":"..."}

4. For IQ-stream connections: server streams raw interleaved int16 IQ
   samples (little-endian) until the socket is closed.
   For status connections: server closes the socket after the response.

5. To retune, the client closes the IQ socket and reconnects with a new
   IQ-stream command.

IQ bandwidth note
-----------------
For complex (IQ) sampling, the Nyquist theorem gives:
    usable bandwidth = sample_rate   (NOT sample_rate / 2)

This is because IQ captures both positive and negative frequencies
simultaneously.  The AD9361 RF bandwidth filter is a *separate* analog
anti-aliasing filter that should be ≤ sample_rate.  The driver default
is ~0.75 × sample_rate, which leaves a small guard band.  You can set
rf_bandwidth = sample_rate for maximum capture width, but the filter
roll-off at the edges will be steeper.

Example: sample_rate=8 MHz → 8 MHz of usable IQ bandwidth.
         rf_bandwidth=6 MHz → analog filter passes 6 MHz (guard band of 1 MHz each side).

Notes
-----
* pyadi-iio sdr.rx() returns a numpy complex128 array where:
    samples.real = I (in-phase) values,    range ±2048 (12-bit ADC)
    samples.imag = Q (quadrature) values,  range ±2048 (12-bit ADC)
  SDRTrunk's SignedShortNativeBuffer divides by 2048 to normalise to ±1.0,
  so the server must send values in the ±2048 range.
  The server builds an interleaved int16 array [I0, Q0, I1, Q1, ...] using
  samples.real and samples.imag directly — NOT np.frombuffer(dtype=float32).
* The AD9361 output via pyadi-iio is already in the correct spectral
  orientation — NO Q-negation is needed.  (Verified by test_sdr_direct.py:
  raw output has no mirror pairs; conjugating shifts signals to wrong side.)
* The server handles one IQ-stream client at a time.  Status-command
  connections are handled inline (they are very short-lived) and do not
  block the IQ stream.
* SDRTrunk reconnects 2-3 times at startup while the AD9361 hardware
  stabilises.  This is normal and expected – the "Socket error" messages
  during the first few seconds are harmless.

Spectrum orientation
---------------------
The AD9361 output via pyadi-iio (complex128) is already correctly oriented.
No spectrum inversion correction is needed.  The server sends samples.real
as I and samples.imag as Q directly.

Sample-rate support (521 kHz – 61.44 MHz)
------------------------------------------
The AD9361 supports sample rates from 521 kHz to 61.44 MHz.  The previous
MIN_SAMPLE_RATE constant of 2_083_333 was incorrect and silently clamped
521 kHz, 1 MHz, 1.5 MHz and 2 MHz requests to ~2.08 MHz.

IIO buffer-size ordering (4 MHz / 8 MHz fix)
---------------------------------------------
pyadi-iio destroys and recreates the internal IIO DMA buffer whenever
sample_rate, rx_enabled_channels, or rx_rf_bandwidth is changed.  Setting
rx_buffer_size before those calls caused it to be reset to the driver
default (typically 4096 samples) at higher sample rates (4 MHz, 8 MHz),
making SDRTrunk receive data at the wrong rate.  The fix is to set
rx_buffer_size LAST, after all other hardware parameters.
"""

import argparse
import json
import logging
import signal
import socket
import sys
import threading
import time
from pathlib import Path

import numpy as np

try:
    import adi
except ImportError:
    print("ERROR: pyadi-iio is not installed.  Run:  pip install pyadi-iio")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pluto_server")

# ---------------------------------------------------------------------------
# Hardware Configuration Loader
# ---------------------------------------------------------------------------
def parse_range(s):
    """Parse '[min step max]' string → (min, step, max) tuple or None."""
    s = str(s).strip()
    if s.startswith("[") and s.endswith("]"):
        parts = s[1:-1].split()
        if len(parts) == 3:
            try:
                return tuple(int(x) for x in parts)
            except ValueError:
                return None
    return None


def parse_list(s):
    """Parse space-separated string 'a b c ...' → list of strings."""
    return str(s).strip().split()


def update_hardware_config_from_device(pluto_uri: str, config_path: str = "hardware_config.json") -> None:
    """
    Poll PlutoSDR device and update hardware_config.json with detected capabilities.
    This is called when --update-config flag is used.
    """
    from datetime import datetime
    
    log.info("=" * 70)
    log.info("UPDATING HARDWARE CONFIGURATION")
    log.info("=" * 70)
    
    uri = _normalize_uri(pluto_uri)
    log.info("Polling device at %s ...", uri)
    
    try:
        # Connect and read device info
        sdr = adi.Pluto(uri=uri)
        
        hw_model = _ctx_attr(sdr, "hw_model")
        hw_serial = _ctx_attr(sdr, "hw_serial")
        fw_version = _ctx_attr(sdr, "fw_version")
        
        log.info("  Hardware model:  %s", hw_model)
        log.info("  Serial number:   %s", hw_serial)
        log.info("  Firmware:        %s", fw_version)
        
        # Extract hardware limits
        limits = {}
        
        # RX LO frequency range
        try:
            rx_lo_ch = next(c for c in sdr._ctrl.channels if c.id == "altvoltage0")
            rx_lo_avail = rx_lo_ch.attrs["frequency_available"].value
            r = parse_range(rx_lo_avail)
            if r:
                limits["min_freq"] = r[0]
                limits["max_freq"] = r[2]
                log.info("  RX frequency range: %d - %d Hz (%.1f - %.0f MHz)",
                        r[0], r[2], r[0]/1e6, r[2]/1e6)
        except Exception as e:
            log.warning("  Could not read RX frequency range: %s", e)
        
        # RX gain range
        try:
            rx_ch = next(c for c in sdr._ctrl.channels if c.id == "voltage0" and not c.output)
            gain_avail = rx_ch.attrs["hardwaregain_available"].value
            r = parse_range(gain_avail)
            if r:
                limits["min_gain"] = r[0]
                limits["max_gain"] = r[2]
                log.info("  RX gain range: %d - %d dB", r[0], r[2])
        except Exception as e:
            log.warning("  Could not read RX gain range: %s", e)
        
        # RX RF bandwidth range
        try:
            rf_bw_avail = rx_ch.attrs["rf_bandwidth_available"].value
            r = parse_range(rf_bw_avail)
            if r:
                limits["min_rf_bw"] = r[0]
                limits["max_rf_bw"] = r[2]
                log.info("  RF bandwidth range: %d - %d Hz (%.1f - %.0f MHz)",
                        r[0], r[2], r[0]/1e6, r[2]/1e6)
        except Exception as e:
            log.warning("  Could not read RF bandwidth range: %s", e)
        
        # RX sample rate range
        try:
            sr_avail = rx_ch.attrs["sampling_frequency_available"].value
            r = parse_range(sr_avail)
            if r:
                limits["min_sample_rate"] = r[0]
                limits["max_sample_rate"] = r[2]
                log.info("  Sample rate range: %d - %d Hz (%.3f - %.2f MHz)",
                        r[0], r[2], r[0]/1e6, r[2]/1e6)
        except Exception as e:
            log.warning("  Could not read sample rate range: %s", e)
        
        # Gain control modes
        try:
            gc_avail = rx_ch.attrs["gain_control_mode_available"].value
            modes = parse_list(gc_avail)
            limits["valid_gain_modes"] = modes
            log.info("  Valid gain modes: %s", modes)
        except Exception as e:
            log.warning("  Could not read gain control modes: %s", e)
        
        # Load existing config or create new one
        config_file = Path(config_path)
        if config_file.exists():
            with open(config_file, "r") as f:
                config = json.load(f)
        else:
            config = {
                "hardware_limits": {},
                "iio_settings": {
                    "buffer_size": 65536,
                    "kernel_buffers": 4,
                    "max_sustainable_rate": 6_000_000,
                    "stats_log_interval": 200,
                }
            }
        
        # Update config
        config["device_info"] = {
            "last_polled": datetime.now().isoformat(),
            "hw_model": hw_model,
            "hw_serial": hw_serial,
            "fw_version": fw_version,
        }
        config["hardware_limits"] = limits
        config["_comment"] = f"PlutoSDR hardware configuration - auto-updated on {config['device_info']['last_polled']}"
        
        # Write back
        with open(config_file, "w") as f:
            json.dump(config, f, indent=2)
        
        log.info("✓ Configuration updated: %s", config_file)
        log.info("=" * 70)
        
    except Exception as e:
        log.error("Failed to update hardware configuration: %s", e)
        log.error("Continuing with existing configuration...")


def load_hardware_config(config_path: str = "hardware_config.json") -> dict:
    """
    Load hardware configuration from JSON file.
    Falls back to default values if file is missing or invalid.
    
    Returns dict with keys: hardware_limits, iio_settings
    """
    config_file = Path(config_path)
    
    # Default configuration (fallback values)
    default_config = {
        "hardware_limits": {
            "min_sample_rate": 2_083_333,
            "max_sample_rate": 61_440_000,
            "min_freq": 46_875_001,
            "max_freq": 6_000_000_000,
            "min_gain": -3,
            "max_gain": 71,
            "min_rf_bw": 200_000,
            "max_rf_bw": 56_000_000,
            "valid_gain_modes": ["manual", "fast_attack", "slow_attack", "hybrid"],
        },
        "iio_settings": {
            "buffer_size": 65536,
            "kernel_buffers": 4,
            "max_sustainable_rate": 6_000_000,
            "stats_log_interval": 200,
        }
    }
    
    # Try to load from file
    if config_file.exists():
        try:
            with open(config_file, "r") as f:
                loaded_config = json.load(f)
            
            # Merge loaded config with defaults (in case some keys are missing)
            config = default_config.copy()
            if "hardware_limits" in loaded_config:
                config["hardware_limits"].update(loaded_config["hardware_limits"])
            if "iio_settings" in loaded_config:
                config["iio_settings"].update(loaded_config["iio_settings"])
            
            log.info("Loaded hardware configuration from %s", config_file)
            if "device_info" in loaded_config:
                dev_info = loaded_config["device_info"]
                if dev_info.get("hw_model") != "unknown":
                    log.info("  Device: %s (S/N: %s, FW: %s)",
                             dev_info.get("hw_model", "unknown"),
                             dev_info.get("hw_serial", "unknown"),
                             dev_info.get("fw_version", "unknown"))
                if dev_info.get("last_polled"):
                    log.info("  Last polled: %s", dev_info.get("last_polled"))
            
            return config
            
        except Exception as e:
            log.warning("Failed to load %s: %s", config_file, e)
            log.warning("Using default hardware configuration")
    else:
        log.info("Hardware config file %s not found, using defaults", config_file)
        log.info("Run 'python config_hardware.py' or use --update-config flag to auto-detect")
    
    return default_config


# Load hardware configuration at module level
_hardware_config = load_hardware_config()

# ---------------------------------------------------------------------------
# Constants (loaded from hardware_config.json)
# ---------------------------------------------------------------------------
# IIO buffer settings (from hardware_config.json)
IIO_BUFFER_SIZE = _hardware_config["iio_settings"]["buffer_size"]
IIO_KERNEL_BUFFERS = _hardware_config["iio_settings"]["kernel_buffers"]
IIO_MAX_SUSTAINABLE_RATE = _hardware_config["iio_settings"]["max_sustainable_rate"]
STATS_LOG_INTERVAL = _hardware_config["iio_settings"]["stats_log_interval"]

# Hardware limits (from hardware_config.json)
MIN_SAMPLE_RATE = _hardware_config["hardware_limits"]["min_sample_rate"]
MAX_SAMPLE_RATE = _hardware_config["hardware_limits"]["max_sample_rate"]
MIN_FREQ = _hardware_config["hardware_limits"]["min_freq"]
MAX_FREQ = _hardware_config["hardware_limits"]["max_freq"]
MIN_GAIN = _hardware_config["hardware_limits"]["min_gain"]
MAX_GAIN = _hardware_config["hardware_limits"]["max_gain"]
MIN_RF_BW = _hardware_config["hardware_limits"]["min_rf_bw"]
MAX_RF_BW = _hardware_config["hardware_limits"]["max_rf_bw"]
VALID_GAIN_MODES = tuple(_hardware_config["hardware_limits"]["valid_gain_modes"])


# ---------------------------------------------------------------------------
# PlutoSDR helpers
# ---------------------------------------------------------------------------

def _normalize_uri(uri: str) -> str:
    """Ensure the URI has a libiio scheme prefix (ip:, usb:, etc.)."""
    known_schemes = ("ip:", "usb:", "serial:", "xml:", "local:")
    if any(uri.startswith(s) for s in known_schemes):
        return uri
    return "ip:" + uri


def _ctx_attr(sdr: adi.Pluto, name: str, default: str = "unknown") -> str:
    """Safely read a context-level IIO attribute (e.g. fw_version, hw_model)."""
    try:
        val = sdr._ctx.attrs[name]
        if isinstance(val, str):
            return val
        return val.value
    except (KeyError, AttributeError):
        return default


def _dev_attr(sdr: adi.Pluto, name: str, default: str = "unknown") -> str:
    """Safely read a device-level IIO attribute from the AD9361 PHY ctrl."""
    try:
        return sdr._ctrl.attrs[name].value
    except (KeyError, AttributeError):
        return default


def _read_temperature(sdr: adi.Pluto) -> float:
    """
    Read the AD9361 die temperature in °C.
    The raw IIO value is in milli-degrees Celsius → divide by 1000.
    Returns float('nan') on failure.
    """
    try:
        raw = int(sdr._ctrl.find_channel("temp0", False).attrs["input"].value)
        return raw / 1000.0
    except Exception:
        return float("nan")


def _read_rssi(sdr: adi.Pluto) -> str:
    """Read the RX RSSI string from the AD9361 PHY. Returns 'unknown' on failure."""
    try:
        return sdr._ctrl.find_channel("voltage0", False).attrs["rssi"].value
    except Exception:
        return "unknown"


def _read_rf_bandwidth(sdr: adi.Pluto) -> int:
    """Read the current RX RF bandwidth from the hardware. Returns 0 on failure."""
    try:
        return int(sdr.rx_rf_bandwidth)
    except Exception:
        return 0


def _log_device_info(sdr: adi.Pluto) -> None:
    """Log hardware model, serial number, firmware version, and temperature."""
    log.info("  Hardware : %s", _ctx_attr(sdr, "hw_model"))
    log.info("  Serial   : %s", _ctx_attr(sdr, "hw_serial"))
    log.info("  Firmware : %s", _ctx_attr(sdr, "fw_version"))
    log.info("  Die temp : %.2f °C", _read_temperature(sdr))


def _log_rx_path_rates(sdr: adi.Pluto) -> None:
    """Log the AD9361 RX path clock rates after configuration."""
    rates = _dev_attr(sdr, "rx_path_rates")
    if rates != "unknown":
        log.info("  RX path rates: %s", rates)


def _build_device_info_dict(sdr: adi.Pluto) -> dict:
    """
    Build a dict of live device info (model, serial, fw, temp, rssi, rf_bw).
    Used in both the connect response and the status response.
    """
    temp_c = _read_temperature(sdr)
    rssi   = _read_rssi(sdr)
    rf_bw  = _read_rf_bandwidth(sdr)
    return {
        "hw_model":    _ctx_attr(sdr, "hw_model"),
        "hw_serial":   _ctx_attr(sdr, "hw_serial"),
        "fw_version":  _ctx_attr(sdr, "fw_version"),
        "temperature": round(temp_c, 2) if not (temp_c != temp_c) else None,  # NaN → None
        "rssi":        rssi,
        "rf_bandwidth": rf_bw,
    }


def create_sdr(pluto_uri: str, data_format: str = "cs16") -> adi.Pluto:
    """Create and return a connected Pluto SDR instance."""
    pluto_uri = _normalize_uri(pluto_uri)
    log.info("Connecting to PlutoSDR at %s …", pluto_uri)
    sdr = adi.Pluto(uri=pluto_uri)
    
    # Note: CS8 mode must be set AFTER configure_sdr() because changing
    # sample_rate resets the data format. See _apply_cs8_mode() below.
    if data_format.lower() == "cs8":
        log.info("CS8 mode requested – will be applied after hardware configuration")
    else:
        log.info("Using default CS16 mode")
    
    log.info("Connected to PlutoSDR:")
    _log_device_info(sdr)
    return sdr


def _apply_cs8_mode(sdr: adi.Pluto) -> bool:
    """
    Apply CS8 (complex 8-bit) mode to the PlutoSDR.
    
    IMPORTANT: This must be called AFTER configure_sdr() because changing
    sample_rate/buffer_size resets the data format to CS16.
    
    Returns True if CS8 was successfully enabled, False otherwise.
    """
    try:
        # Try setting rx_data_format property
        sdr.rx_data_format = "ci8"
        
        # Verify it actually stuck
        try:
            actual_format = sdr.rx_data_format
            if actual_format == "ci8":
                log.info("✅ CS8 mode ENABLED and VERIFIED via rx_data_format=ci8 – up to 45 MHz over GigE!")
                return True
            else:
                log.warning("⚠️  rx_data_format was set to 'ci8' but reads back as '%s'", actual_format)
                log.warning("    This pyadi-iio version may not support CS8 format on this firmware")
                return False
        except Exception:
            # Can't read it back, assume it worked
            log.info("✅ CS8 mode set via rx_data_format=ci8 (verification not available)")
            return True
            
    except Exception as e:
        log.warning("⚠️  rx_data_format=ci8 failed: %s", e)
        log.warning("    Your Tezuka firmware (tezuka-0.2.4) may not support CS8 mode")
        log.warning("    CS8 requires Tezuka 0.34 or later. You have: tezuka-0.2.4")
        log.info("ℹ️  Continuing in CS16 mode - your existing 8-bit detection will handle it if available")
        return False


def configure_sdr(
    sdr: adi.Pluto,
    freq: int,
    sample_rate: int,
    gain: int,
    agc: bool,
    gain_mode: str = "slow_attack",
    rf_bandwidth: int = 0,
    rx_channel: int = 0,
) -> dict:
    """
    Apply radio parameters to the PlutoSDR.

    IQ bandwidth note
    -----------------
    For complex (IQ) sampling, usable bandwidth = sample_rate (not sample_rate/2).
    The rf_bandwidth is a separate analog anti-aliasing filter; it should be
    ≤ sample_rate.  When rf_bandwidth=0 the AD9361 driver sets it automatically
    to ~0.75 × sample_rate, leaving a small guard band at the edges.

    Parameters
    ----------
    freq         : RX LO frequency in Hz
    sample_rate  : desired sample rate in Hz  (= usable IQ bandwidth)
    gain         : manual gain in dB (used only when agc=False)
    agc          : if True, use gain_mode for automatic gain control
    gain_mode    : AGC mode string when agc=True
    rf_bandwidth : analog RF filter in Hz; 0 = auto (~0.75 × sample_rate)
    rx_channel   : which AD9361 RX channel to use (0 = RX0, 1 = RX1).
                   RX1 requires patched PlutoSDR firmware with dual-channel support.

    Returns
    -------
    dict with keys: sample_rate (int), rf_bandwidth (int)
    """
    # Clamp to valid ranges
    freq        = max(MIN_FREQ,        min(MAX_FREQ,        int(freq)))
    sample_rate = max(MIN_SAMPLE_RATE, min(MAX_SAMPLE_RATE, int(sample_rate)))
    gain        = max(MIN_GAIN,        min(MAX_GAIN,        int(gain)))

    # Validate gain mode
    if gain_mode not in VALID_GAIN_MODES:
        log.warning("Unknown gain_mode '%s', defaulting to 'slow_attack'", gain_mode)
        gain_mode = "slow_attack"

    # Set LO frequency.
    # IMPORTANT: On the standard PlutoSDR (1r1t or 2r2t mode), both RX channels
    # share a SINGLE LO (local oscillator).  There is NO way to tune RX0 and RX1
    # to different frequencies on a single PlutoSDR device.
    # Both channels always receive the same centre frequency.
    #
    # To receive two DIFFERENT frequencies simultaneously you need TWO separate
    # PlutoSDR devices, each running its own pluto_server.py instance.
    #
    # The 2r2t mode (enabled via fw_setenv) gives you two RX channels at the
    # SAME frequency — useful for diversity reception or phase comparison, but
    # NOT for covering a wider frequency span.
    sdr.rx_lo = freq
    if rx_channel == 1:
        log.info("  Set shared LO to %.3f MHz (RX0 and RX1 both tune to this frequency)", freq / 1e6)

    sdr.sample_rate = sample_rate

    # Apply RF bandwidth.
    # When rf_bandwidth=0 (auto), we explicitly set rx_rf_bandwidth = sample_rate
    # so the AD9361 analog filter always matches the current sample rate.
    # Without this, the driver keeps whatever bandwidth was set in the PREVIOUS
    # session (e.g. 6 MHz from a prior 6 MHz run), causing the filter to clip
    # signals at the edges of an 8 MHz capture even though the sample rate is 8 MHz.
    #
    # Setting rf_bandwidth = sample_rate gives the widest possible filter for the
    # current sample rate.  The AD9361 driver will clamp it to its internal maximum
    # for the given rate if needed.  This is safe: the IIO DMA buffer is the real
    # rate limiter; the RF filter just needs to be ≥ sample_rate to avoid clipping.
    if rf_bandwidth > 0:
        rf_bandwidth = max(MIN_RF_BW, min(MAX_RF_BW, int(rf_bandwidth)))
    else:
        # Auto: use sample_rate as the filter width so the full IQ bandwidth is passed.
        rf_bandwidth = max(MIN_RF_BW, min(MAX_RF_BW, sample_rate))
        log.info(
            "  rf_bandwidth=auto → setting rx_rf_bandwidth=%d Hz (= sample_rate)",
            rf_bandwidth,
        )
    sdr.rx_rf_bandwidth = rf_bandwidth

    # Apply gain settings to the correct channel
    gain_mode_attr  = f"gain_control_mode_chan{rx_channel}"
    gain_value_attr = f"rx_hardwaregain_chan{rx_channel}"
    if agc:
        try:
            setattr(sdr, gain_mode_attr, gain_mode)
        except AttributeError:
            sdr.gain_control_mode_chan0 = gain_mode
    else:
        try:
            setattr(sdr, gain_mode_attr, "manual")
            setattr(sdr, gain_value_attr, gain)
        except AttributeError:
            sdr.gain_control_mode_chan0 = "manual"
            sdr.rx_hardwaregain_chan0   = gain

    # Set rx_buffer_size LAST – after all other hardware parameters.
    # pyadi-iio internally destroys and recreates the IIO DMA buffer whenever
    # sample_rate, rx_enabled_channels, or rf_bandwidth changes.  Setting
    # rx_buffer_size before those calls means the buffer gets reset to the
    # driver default (typically 4096 samples) by the subsequent hardware
    # reconfiguration.  At 4 MHz and 8 MHz this caused the server to send
    # 4096-sample chunks instead of 65536-sample chunks, making SDRTrunk
    # receive data at the wrong rate and fail to decode channels correctly.
    sdr.rx_buffer_size = IIO_BUFFER_SIZE
    actual_buf_size = sdr.rx_buffer_size
    if actual_buf_size != IIO_BUFFER_SIZE:
        log.warning(
            "  rx_buffer_size: requested %d but hardware returned %d – "
            "stream timing may be incorrect at this sample rate",
            IIO_BUFFER_SIZE, actual_buf_size,
        )
    else:
        log.info("  rx_buffer_size: %d samples (confirmed)", actual_buf_size)

    # Set kernel_buffers_count to pipeline IIO DMA buffers.
    # Over the IIO network backend (Ethernet), each sdr.rx() call has ~2ms of
    # round-trip latency on top of the hardware fill time.  With only 1 kernel
    # buffer (the default), sdr.rx() blocks for fill_time + network_latency,
    # which at 8 MHz gives ~10ms instead of the expected ~8.19ms.
    # With 4 kernel buffers the DMA can prefill the next buffer while Python
    # is processing the current one, hiding the network latency and keeping
    # sdr.rx() close to the true hardware fill time.
    try:
        sdr.rx_kernel_buffers_count = IIO_KERNEL_BUFFERS
        log.info("  rx_kernel_buffers_count: %d", IIO_KERNEL_BUFFERS)
    except AttributeError:
        log.debug("  rx_kernel_buffers_count not supported by this pyadi-iio version")

    # Enable only the requested RX channel — set AFTER rx_buffer_size.
    # pyadi-iio destroys and recreates the IIO DMA buffer when rx_enabled_channels
    # changes, which resets rx_buffer_size back to the driver default (~4096).
    # By setting rx_enabled_channels AFTER rx_buffer_size, we avoid that reset.
    # Without this, both RX0 and RX1 remain active, sdr.rx() returns 2× the
    # expected samples, and SDRTrunk receives data at 2× real-time speed
    # (shows as ~122 bufs/s instead of ~61 bufs/s at 4 MHz).
    try:
        enabled_before = sdr.rx_enabled_channels
        log.info("  RX enabled channels (before): %s", enabled_before)
        sdr.rx_enabled_channels = [rx_channel]
        enabled_after = sdr.rx_enabled_channels
        log.info("  RX enabled channels (after):  %s", enabled_after)
        if len(enabled_after) != 1 or enabled_after[0] != rx_channel:
            log.warning(
                "  rx_enabled_channels did not take effect: requested [%d] but got %s – "
                "sdr.rx() may return 2× samples causing 2× real-time streaming",
                rx_channel, enabled_after,
            )
    except Exception as e:
        log.warning("  Could not set rx_enabled_channels to [%d]: %s", rx_channel, e)

    actual_rate = int(sdr.sample_rate)
    actual_bw   = int(sdr.rx_rf_bandwidth)

    # IQ bandwidth = sample_rate (complex sampling captures full bandwidth)
    # RF bandwidth = analog filter (should be ≤ sample_rate to avoid aliasing)
    #
    # NOTE: freq is logged in full Hz precision so that PPM corrections applied
    # by SDRTrunk (which shift the LO by a few hundred Hz) are visible in the log.
    # e.g. 858211571 Hz (858.211571 MHz) vs 858212000 Hz (858.212000 MHz) shows
    # a 429 Hz shift from a 0.5 PPM correction at 858 MHz.
    log.info(
        "PlutoSDR configured: channel=RX%d  freq=%d Hz (%.6f MHz)  sampleRate=%d Hz  "
        "iqBw=%d Hz (=sampleRate)  rfFilter=%d Hz  gain=%s  mode=%s",
        rx_channel, freq, freq / 1e6, actual_rate,
        actual_rate,   # IQ usable bandwidth = sample rate
        actual_bw,
        "AGC" if agc else f"{gain} dB",
        gain_mode if agc else "manual",
    )
    _log_rx_path_rates(sdr)

    rssi   = _read_rssi(sdr)
    temp_c = _read_temperature(sdr)
    log.info("  RSSI: %s   Die temp: %.2f °C", rssi, temp_c)

    return {"sample_rate": actual_rate, "rf_bandwidth": actual_bw}


# ---------------------------------------------------------------------------
# Peek helper – classify an incoming connection without consuming data
# ---------------------------------------------------------------------------

def _peek_is_status_command(conn: socket.socket, timeout: float = 1.0) -> bool:
    """
    Peek at the first bytes of an incoming connection to decide whether it
    is a status-only command ({"command":"status"}) or an IQ-stream command.

    Returns True if the peeked data contains the key "command" (which only
    appears in status requests, not in IQ-stream commands).
    """
    conn.settimeout(timeout)
    try:
        peek = conn.recv(256, socket.MSG_PEEK)
        # Status commands contain the key "command"; IQ-stream commands do not.
        return b'"command"' in peek
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Client handler
# ---------------------------------------------------------------------------

def handle_client(
    conn: socket.socket,
    addr,
    sdr: adi.Pluto,
    stop_event: threading.Event,
    rx_channel: int = 0,
    sample_bits: int = 0,
    data_format: str = "cs16",
) -> None:
    """
    Handle a single SDRTrunk client connection.

    Two connection types are supported:
    - IQ-stream: long-lived, streams samples until client disconnects
    - Status:    short-lived, returns JSON and closes immediately

    Startup reconnects (2-3 rapid IQ connections) are normal – SDRTrunk
    reconnects while the AD9361 hardware stabilises after the first connect.

    Parameters
    ----------
    rx_channel : AD9361 RX channel to use (0 = RX0, 1 = RX1).
    sample_bits : Sample bit depth (0 = auto, 8 = force 8-bit, 16 = force 16-bit).
                  Tezuka 0.34 uses 8-bit, older firmware uses 16-bit (12-bit ADC padded).
    data_format : Sample format - "cs16" (default) or "cs8" (Tezuka high-bandwidth mode).
    """
    log.info("Client connected from %s:%d", *addr)

    try:
        # ----------------------------------------------------------------
        # 1. Read the JSON command (one line terminated by '\n')
        # ----------------------------------------------------------------
        conn.settimeout(2.0)
        raw = b""
        while b"\n" not in raw:
            if stop_event.is_set():
                return
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                log.warning("Client %s:%d disconnected before sending command", *addr)
                return
            raw += chunk

        line = raw.split(b"\n", 1)[0].decode("utf-8").strip()
        log.debug("Received command from %s:%d: %s", *addr, line)

        try:
            cmd = json.loads(line)
        except json.JSONDecodeError as e:
            _send_error(conn, f"Invalid JSON: {e}")
            return

        # ----------------------------------------------------------------
        # 2a. Status-only command (short-lived, no IQ stream)
        # ----------------------------------------------------------------
        if cmd.get("command") == "status":
            _handle_status_command(conn, addr, sdr)
            return

        # ----------------------------------------------------------------
        # 2b. IQ-stream command
        # ----------------------------------------------------------------
        freq         = int(cmd.get("freq",         101_100_000))
        sample_rate  = int(cmd.get("sample_rate",  2_500_000))
        gain         = int(cmd.get("gain",         30))
        agc          = bool(cmd.get("agc",         False))
        gain_mode    = str(cmd.get("gain_mode",    "slow_attack"))
        rf_bandwidth = int(cmd.get("rf_bandwidth", 0))

        # ----------------------------------------------------------------
        # 2c. Warn if requested sample rate exceeds the IIO network cap
        # ----------------------------------------------------------------
        if IIO_MAX_SUSTAINABLE_RATE > 0 and sample_rate > IIO_MAX_SUSTAINABLE_RATE:
            log.warning(
                "*** SAMPLE RATE WARNING: requested %d Hz exceeds the IIO network "
                "throughput cap of %d Hz (~24 MB/s). ***",
                sample_rate, IIO_MAX_SUSTAINABLE_RATE,
            )
            log.warning(
                "*** The iiod daemon on this device caps IIO transfers at ~24 MB/s "
                "(= 6 MHz). Above 6 MHz, sdr.rx() will take ~10ms per buffer "
                "regardless of sample rate, causing SDRTrunk to receive data "
                "slower than real-time and producing CRC/link-loss errors. ***"
            )
            log.warning(
                "*** Recommendation: set sample_rate=6000000 in SDRTrunk for "
                "reliable P25 decoding over this Ethernet connection. ***"
            )

        # ----------------------------------------------------------------
        # 3. Configure the PlutoSDR
        # ----------------------------------------------------------------
        try:
            hw_info = configure_sdr(
                sdr, freq, sample_rate, gain, agc,
                gain_mode=gain_mode,
                rf_bandwidth=rf_bandwidth,
                rx_channel=rx_channel,
            )
        except Exception as e:
            log.error("Failed to configure PlutoSDR: %s", e)
            _send_error(conn, str(e))
            return

        # ----------------------------------------------------------------
        # 3b. Apply CS8 mode AFTER hardware configuration
        # ----------------------------------------------------------------
        # IMPORTANT: This must be done AFTER configure_sdr() because changing
        # sample_rate resets the data format back to CS16.
        if data_format.lower() == "cs8":
            _apply_cs8_mode(sdr)

        # ----------------------------------------------------------------
        # 4. Send OK response with device info
        # ----------------------------------------------------------------
        device_info = _build_device_info_dict(sdr)
        response = {
            "status":       "ok",
            "sample_rate":  hw_info["sample_rate"],
            "rf_bandwidth": hw_info["rf_bandwidth"],
        }
        response.update(device_info)
        conn.sendall((json.dumps(response) + "\n").encode("utf-8"))

        # ----------------------------------------------------------------
        # 5. Stream IQ samples until the client disconnects or server stops
        # ----------------------------------------------------------------
        actual_sr = hw_info["sample_rate"]
        actual_bw = hw_info["rf_bandwidth"]
        log.info(
            "Streaming IQ to %s:%d  sampleRate=%d Hz  iqBw=%d Hz  rfFilter=%d Hz",
            addr[0], addr[1], actual_sr, actual_sr, actual_bw,
        )
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        conn.settimeout(None)

        buf_count    = 0
        total_bytes  = 0
        window_start = time.monotonic()
        window_bytes = 0

        # Timing diagnostics: track how long sdr.rx() and sendall() each take.
        # sdr.rx() blocks until the IIO DMA kernel driver has a full buffer ready,
        # which naturally paces the stream to the hardware sample rate.
        # No sleep is needed; the IIO driver IS the rate limiter.
        buf_duration_expected_s = IIO_BUFFER_SIZE / actual_sr
        diag_rx_total_s   = 0.0
        diag_send_total_s = 0.0

        # On the first rx() call, detect whether pyadi-iio returns a single
        # array (one channel) or a tuple/list of arrays (two channels).
        # With two channels active, we take only channel 0.
        # Also verify the actual sample count matches IIO_BUFFER_SIZE.
        # Additionally, detect the sample format (8-bit vs 16-bit).
        _rx_channel_logged = False
        _actual_samples_per_buf = IIO_BUFFER_SIZE  # updated on first rx() call
        _sample_format_logged = False
        _detected_8bit = None  # True if 8-bit, False if 16-bit, None if not yet detected

        while not stop_event.is_set():
            t0 = time.monotonic()
            samples = sdr.rx()  # shape: (IIO_BUFFER_SIZE,) or tuple of two arrays
            t1 = time.monotonic()

            if stop_event.is_set():
                break

            # Handle both single-channel (array) and dual-channel (tuple) returns.
            # If pyadi-iio returns a tuple/list, take only the first channel (RX0).
            if isinstance(samples, (list, tuple)):
                if not _rx_channel_logged:
                    log.info(
                        "  pyadi-iio returned %d channels from sdr.rx() – using channel 0 only. "
                        "Each sdr.rx() call returns %d samples per channel.",
                        len(samples), len(samples[0]),
                    )
                    _rx_channel_logged = True
                samples = samples[0]
            elif not _rx_channel_logged:
                log.info(
                    "  pyadi-iio returned single-channel array from sdr.rx() – %d samples.",
                    len(samples),
                )
                _rx_channel_logged = True

            # Always enforce exactly IIO_BUFFER_SIZE samples per buffer.
            # If sdr.rx() returns 2× IIO_BUFFER_SIZE samples as a flat array
            # (both RX channels interleaved in a single array rather than a
            # tuple), we must take every other sample (channel 0 only), NOT
            # just the first half.  Taking samples[:IIO_BUFFER_SIZE] would
            # give only the I values from both channels, leaving Q=0.
            actual_len = len(samples)
            if actual_len == IIO_BUFFER_SIZE * 2:
                if actual_len != _actual_samples_per_buf:
                    log.warning(
                        "  sdr.rx() returned %d samples (2× IIO_BUFFER_SIZE) as flat array – "
                        "extracting channel 0 by taking every other sample",
                        actual_len,
                    )
                    _actual_samples_per_buf = actual_len
                # Both channels interleaved: [ch0_s0, ch1_s0, ch0_s1, ch1_s1, ...]
                # Take every other sample starting at 0 to get channel 0 only.
                samples = samples[0::2]
            elif actual_len > IIO_BUFFER_SIZE:
                if actual_len != _actual_samples_per_buf:
                    log.warning(
                        "  sdr.rx() returned %d samples but IIO_BUFFER_SIZE=%d – "
                        "truncating to %d",
                        actual_len, IIO_BUFFER_SIZE, IIO_BUFFER_SIZE,
                    )
                    _actual_samples_per_buf = actual_len
                samples = samples[:IIO_BUFFER_SIZE]
            elif actual_len < IIO_BUFFER_SIZE:
                log.warning(
                    "  sdr.rx() returned only %d samples (expected %d) – "
                    "stream may be corrupted",
                    actual_len, IIO_BUFFER_SIZE,
                )

            # Detect sample format (8-bit vs 16-bit) on first iteration.
            # Tezuka 0.34 firmware uses 8-bit samples (int8/uint8).
            # Older firmware uses 12-bit ADC padded to 16-bit (complex64/complex128).
            n = len(samples)
            
            if _detected_8bit is None:
                # Auto-detect on first call (unless --sample-bits forces a mode)
                if sample_bits == 8:
                    _detected_8bit = True
                    log.info("  Sample format: 8-bit (forced via --sample-bits=8)")
                elif sample_bits == 16:
                    _detected_8bit = False
                    log.info("  Sample format: 16-bit (forced via --sample-bits=16)")
                elif samples.dtype in (np.int8, np.uint8):
                    _detected_8bit = True
                    log.info("  Sample format: 8-bit detected (dtype=%s, Tezuka 0.34 firmware)", samples.dtype)
                elif samples.dtype in (np.complex64, np.complex128):
                    _detected_8bit = False
                    log.info("  Sample format: 16-bit detected (dtype=%s, older firmware)", samples.dtype)
                else:
                    # Unknown dtype — assume 16-bit and log a warning
                    _detected_8bit = False
                    log.warning("  Sample format: unknown dtype %s, assuming 16-bit complex", samples.dtype)

            # Build interleaved int16 array for SDRTrunk.
            # SDRTrunk expects [I0, Q0, I1, Q1, ...] in the range ±2048 (for 12-bit ADC)
            # or ±32768 (for full int16 range). SignedShortNativeBuffer divides by 2048
            # to normalize to ±1.0, so we must match that scaling.
            raw = np.empty(n * 2, dtype=np.int16)

            if _detected_8bit:
                # 8-bit mode (Tezuka 0.34):
                # pyadi-iio may return int8 interleaved [I0, Q0, I1, Q1, ...] in range ±128.
                # Or it may return a complex array with 8-bit components.
                # Upscale to int16 range: multiply by 256 to map [-128, 127] → [-32768, 32512].
                # Then scale down to ±2048 range to match SDRTrunk's expectations:
                # multiply by 16 instead of 256 (256 / 16 = 16, so [-128,127] → [-2048, 2032])
                if samples.dtype in (np.int8, np.uint8):
                    # Interleaved int8/uint8 array [I0, Q0, I1, Q1, ...]
                    if samples.dtype == np.uint8:
                        # Convert unsigned [0, 255] to signed [-128, 127]
                        samples = samples.astype(np.int8)
                    # Scale to ±2048 range: multiply by 16
                    raw[0::2] = samples[0::2].astype(np.int16) * 16  # I channel
                    raw[1::2] = samples[1::2].astype(np.int16) * 16  # Q channel
                else:
                    # Complex array with 8-bit components (unlikely but handle it)
                    raw[0::2] = (samples.real * 16).astype(np.int16)  # I channel
                    raw[1::2] = (samples.imag * 16).astype(np.int16)  # Q channel
            else:
                # 16-bit mode (older firmware):
                # pyadi-iio returns complex128 with real/imag in range ±2048 (12-bit ADC).
                # Cast directly to int16 — values are already in the correct range.
                raw[0::2] = samples.real.astype(np.int16)  # I channel
                raw[1::2] = samples.imag.astype(np.int16)  # Q channel

            # Send as little-endian bytes (int16 is native little-endian on x86)
            data = raw.tobytes()
            t2 = time.monotonic()
            conn.sendall(data)
            t3 = time.monotonic()

            diag_rx_total_s   += (t1 - t0)
            diag_send_total_s += (t3 - t2)

            buf_count    += 1
            total_bytes  += len(data)
            window_bytes += len(data)

            # Periodic throughput + RF stats log.
            # Note: SDRTrunk status-poll connections (every 30 s) arrive on a
            # SEPARATE short-lived socket handled in their own thread – they
            # do NOT interrupt this loop or appear as "new connections" here.
            if buf_count % STATS_LOG_INTERVAL == 0:
                now     = time.monotonic()
                elapsed = now - window_start
                mb_s    = (window_bytes / 1_048_576.0 / elapsed) if elapsed > 0 else 0.0
                total_mb = total_bytes / 1_048_576.0
                rssi    = _read_rssi(sdr)
                temp_c  = _read_temperature(sdr)
                rf_bw   = _read_rf_bandwidth(sdr)

                # Timing diagnostics: average time per sdr.rx() and sendall()
                avg_rx_ms   = diag_rx_total_s   / STATS_LOG_INTERVAL * 1000.0
                avg_send_ms = diag_send_total_s / STATS_LOG_INTERVAL * 1000.0
                avg_loop_ms = elapsed / STATS_LOG_INTERVAL * 1000.0
                expected_ms = buf_duration_expected_s * 1000.0
                log.info(
                    "  [%s:%d] bufs=%d  %.2f MB/s  total=%.1f MB  "
                    "sampleRate=%d Hz  rfFilter=%d Hz  RSSI=%s  temp=%.1f°C  "
                    "| timing: rx=%.2fms  send=%.2fms  loop=%.2fms  expected=%.2fms",
                    addr[0], addr[1], buf_count,
                    mb_s, total_mb,
                    actual_sr, rf_bw,
                    rssi, temp_c,
                    avg_rx_ms, avg_send_ms, avg_loop_ms, expected_ms,
                )
                window_start      = now
                window_bytes      = 0
                diag_rx_total_s   = 0.0
                diag_send_total_s = 0.0

    except (BrokenPipeError, ConnectionResetError):
        log.info("Client %s:%d disconnected", *addr)
    except OSError as e:
        if not stop_event.is_set():
            # "Socket closed" / WinError 10053 during startup reconnects is normal.
            # SDRTrunk reconnects 2-3 times while the AD9361 hardware stabilises.
            log.debug("Socket error for %s:%d: %s", *addr, e)
    except Exception as e:
        if not stop_event.is_set():
            log.error("Unexpected error handling client %s:%d: %s", *addr, e, exc_info=True)
    finally:
        try:
            conn.close()
        except Exception:
            pass
        log.info("Client %s:%d session ended", *addr)


def _handle_status_command(
    conn: socket.socket,
    addr,
    sdr: adi.Pluto,
) -> None:
    """
    Handle a {"command":"status"} request.

    Reads live temperature, RSSI, RF bandwidth and static device info,
    sends a single JSON response line, then closes the connection.
    This is a very fast operation (< 50 ms) and runs in its own thread
    so it does not interrupt the IQ stream.
    """
    log.debug("Status request from %s:%d", *addr)
    try:
        device_info = _build_device_info_dict(sdr)
        response = {"status": "ok"}
        response.update(device_info)
        conn.sendall((json.dumps(response) + "\n").encode("utf-8"))
        log.debug(
            "Status response sent to %s:%d: temp=%.1f°C  rssi=%s  rfBw=%d Hz",
            addr[0], addr[1],
            device_info.get("temperature") or float("nan"),
            device_info.get("rssi", "unknown"),
            device_info.get("rf_bandwidth", 0),
        )
    except Exception as e:
        log.warning("Error handling status request from %s:%d: %s", *addr, e)
        try:
            _send_error(conn, str(e))
        except Exception:
            pass
    finally:
        try:
            conn.close()
        except Exception:
            pass


def _send_error(conn: socket.socket, message: str) -> None:
    """Send a JSON error response to the client."""
    response = json.dumps({"status": "error", "message": message}) + "\n"
    try:
        conn.sendall(response.encode("utf-8"))
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Main server loop
# ---------------------------------------------------------------------------

def run_server(pluto_uri: str, host: str, port: int, rx_channel: int = 0, 
               sample_bits: int = 0, data_format: str = "cs16") -> None:
    """
    Start the TCP server and serve clients sequentially.

    Parameters
    ----------
    rx_channel : AD9361 RX channel to stream (0 = RX0, 1 = RX1).
                 To use both channels simultaneously, run two server instances
                 on different ports:
                   python pluto_server.py --port 1234 --channel 0
                   python pluto_server.py --port 1235 --channel 1
                 Then add both as separate PlutoSDR tuners in SDRTrunk.
    sample_bits : Sample bit depth (0 = auto, 8 = force 8-bit, 16 = force 16-bit).
                  Tezuka 0.34 uses 8-bit, older firmware uses 16-bit (12-bit ADC padded).
    data_format : Sample format - "cs16" (default) or "cs8" (Tezuka high-bandwidth mode).
    """
    sdr = create_sdr(pluto_uri, data_format=data_format)

    stop_event = threading.Event()

    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind((host, port))
    server_sock.listen(5)   # backlog=5 so status polls queue behind the IQ client
    server_sock.settimeout(1.0)

    def _shutdown(signum, frame):
        if not stop_event.is_set():
            log.info("Shutdown signal received – stopping server …")
            stop_event.set()
            try:
                server_sock.close()
            except Exception:
                pass

    signal.signal(signal.SIGINT,  _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    log.info("PlutoSDR IQ server listening on %s:%d  (RX channel: %d)", host, port, rx_channel)
    log.info("PlutoSDR URI: %s", _normalize_uri(pluto_uri))
    log.info("IQ bandwidth note: for complex sampling, usable bandwidth = sample_rate")
    log.info("  (e.g. 8 MHz sample rate → 8 MHz IQ bandwidth; rf_bandwidth is the analog filter)")
    if rx_channel == 1:
        log.info("  NOTE: RX1 requires patched PlutoSDR firmware with dual-channel support.")
        log.info("  To use both channels, run a second instance: --port 1235 --channel 1")
    log.info("Press Ctrl+C to stop.")

    client_thread: threading.Thread | None = None

    try:
        while not stop_event.is_set():
            try:
                conn, addr = server_sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break

            # If an IQ-stream client is already active, peek at the new
            # connection to decide whether it is a status poll or a new
            # IQ-stream request (e.g. a retune or startup reconnect).
            if client_thread is not None and client_thread.is_alive():
                is_status = _peek_is_status_command(conn)

                if is_status:
                    # Status polls are short-lived – handle in a background
                    # thread without waiting for the IQ stream to finish.
                    log.debug("Status poll from %s:%d – handling inline", *addr)
                    threading.Thread(
                        target=handle_client,
                        args=(conn, addr, sdr, stop_event, rx_channel, sample_bits, data_format),
                        daemon=True,
                        name=f"status-{addr[0]}:{addr[1]}",
                    ).start()
                    continue
                else:
                    # New IQ-stream request (retune or startup reconnect).
                    # Wait for the previous stream to finish before reconfiguring
                    # the hardware (PlutoSDR only supports one active RX stream).
                    log.debug(
                        "New IQ-stream request from %s:%d – waiting for previous session to end …",
                        *addr,
                    )
                    client_thread.join()

            client_thread = threading.Thread(
                target=handle_client,
                args=(conn, addr, sdr, stop_event, rx_channel, sample_bits, data_format),
                daemon=True,
                name=f"client-{addr[0]}:{addr[1]}",
            )
            client_thread.start()

    finally:
        stop_event.set()
        try:
            server_sock.close()
        except Exception:
            pass
        if client_thread is not None and client_thread.is_alive():
            log.info("Waiting for active client to disconnect …")
            client_thread.join(timeout=5.0)
        log.info("Server stopped.")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="PlutoSDR IQ-stream TCP server for SDRTrunk",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--pluto-uri",
        default="ip:192.168.2.1",
        help="libiio URI for the PlutoSDR (e.g. ip:192.168.2.1 or usb:)",
    )
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="TCP host/interface to listen on (0.0.0.0 = all interfaces)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=1234,
        help="TCP port to listen on",
    )
    parser.add_argument(
        "--channel",
        type=int,
        default=0,
        choices=[0, 1],
        help=(
            "AD9361 RX channel to stream: 0 = RX0 (default), 1 = RX1. "
            "RX1 requires patched PlutoSDR firmware with dual-channel support. "
            "To use both channels simultaneously, run two server instances on "
            "different ports: --port 1234 --channel 0  and  --port 1235 --channel 1, "
            "then add both as separate PlutoSDR tuners in SDRTrunk."
        ),
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Enable debug logging (shows status polls, raw commands, socket errors)",
    )
    parser.add_argument(
        "--sample-bits",
        type=int,
        default=0,
        choices=[0, 8, 16],
        help=(
            "Sample bit depth: 8 (Tezuka 0.34 firmware), 16 (older firmware), "
            "0 = auto-detect (default). When set to 8 or 16, forces that format "
            "regardless of what pyadi-iio returns."
        ),
    )
    parser.add_argument(
        "--format",
        choices=["cs16", "cs8"],
        default="cs16",
        help=(
            "Sample format: cs16 (default, compatible with everything) or cs8 "
            "(Tezuka high-bandwidth mode). CS8 mode forces the Pluto device into "
            "8-bit mode at the IIO level, enabling 30-45 MSPS stable over GigE."
        ),
    )
    parser.add_argument(
        "--max-rate",
        type=int,
        default=None,
        help=(
            "Maximum sustainable sample rate in Hz. Above this rate, a warning "
            "is logged. Set based on network + sample format: "
            "Gigabit+8bit=50MHz, Gigabit+16bit=30MHz, 100Mbps+8bit=12MHz, "
            "100Mbps+16bit=6MHz (default), USB+8bit=30MHz, USB+16bit=15MHz."
        ),
    )
    parser.add_argument(
        "--update-config",
        action="store_true",
        help=(
            "Poll the PlutoSDR device and update hardware_config.json with detected "
            "capabilities before starting the server. This is equivalent to running "
            "'python config_hardware.py' first, but integrated into a single command."
        ),
    )
    args = parser.parse_args()

    # Declare globals at the top of main() to avoid SyntaxError
    global _hardware_config
    global IIO_BUFFER_SIZE, IIO_KERNEL_BUFFERS, IIO_MAX_SUSTAINABLE_RATE, STATS_LOG_INTERVAL
    global MIN_SAMPLE_RATE, MAX_SAMPLE_RATE, MIN_FREQ, MAX_FREQ, MIN_GAIN, MAX_GAIN
    global MIN_RF_BW, MAX_RF_BW, VALID_GAIN_MODES

    if args.debug:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Update hardware config from device if requested
    if args.update_config:
        update_hardware_config_from_device(args.pluto_uri, "hardware_config.json")
        # Reload the global config after updating
        _hardware_config = load_hardware_config()
        # Update constants from reloaded config
        IIO_BUFFER_SIZE = _hardware_config["iio_settings"]["buffer_size"]
        IIO_KERNEL_BUFFERS = _hardware_config["iio_settings"]["kernel_buffers"]
        IIO_MAX_SUSTAINABLE_RATE = _hardware_config["iio_settings"]["max_sustainable_rate"]
        STATS_LOG_INTERVAL = _hardware_config["iio_settings"]["stats_log_interval"]
        MIN_SAMPLE_RATE = _hardware_config["hardware_limits"]["min_sample_rate"]
        MAX_SAMPLE_RATE = _hardware_config["hardware_limits"]["max_sample_rate"]
        MIN_FREQ = _hardware_config["hardware_limits"]["min_freq"]
        MAX_FREQ = _hardware_config["hardware_limits"]["max_freq"]
        MIN_GAIN = _hardware_config["hardware_limits"]["min_gain"]
        MAX_GAIN = _hardware_config["hardware_limits"]["max_gain"]
        MIN_RF_BW = _hardware_config["hardware_limits"]["min_rf_bw"]
        MAX_RF_BW = _hardware_config["hardware_limits"]["max_rf_bw"]
        VALID_GAIN_MODES = tuple(_hardware_config["hardware_limits"]["valid_gain_modes"])

    # Update the global max rate if user specified one (overrides config file)
    if args.max_rate is not None:
        IIO_MAX_SUSTAINABLE_RATE = args.max_rate
        log.info("IIO max sustainable rate set to %d Hz via --max-rate", args.max_rate)

    run_server(args.pluto_uri, args.host, args.port, rx_channel=args.channel, 
               sample_bits=args.sample_bits, data_format=args.format)


if __name__ == "__main__":
    main()
