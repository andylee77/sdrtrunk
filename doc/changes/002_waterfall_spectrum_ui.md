# Change 002: Waterfall / Spectrum UI Enhancements

**Date:** 2026-03-09  
**Phase:** 2  
**Branch:** `plutosdr`

## Summary

Migrated the waterfall and spectrum display improvements from the modified upstream source.
Two main features: a **Reference Level** control and a **dB conversion fix**.

## Changes

### 1. Reference Level Control (new)

**Files:**
- `spectrum/menu/ReferenceLevelItem.java` (new)
- `spectrum/SpectrumPanel.java` (modified)
- `spectrum/WaterfallPanel.java` (modified)
- `spectrum/SpectralDisplayPanel.java` (modified)

**What it does:**
A JSlider (-60 to +60 dB, default 0) accessible via right-click → Display → Reference Level.
Adjusting the slider shifts both the spectrum trace and waterfall color mapping in sync:

- **SpectrumPanel:** The offset is converted to pixels using the same dB-to-pixel scalor and
  added to each bin's rendered height in `drawSpectrum()`.
- **WaterfallPanel:** The offset is scaled from dB to color-index units (±60 dB → ±128 of the
  256-level color map) and added to each pixel value in `receive()`.
- Both panels provide `getReferenceLevelOffset()` / `setReferenceLevelOffset()` methods.

**Why:**
Different SDR hardware (RTL-SDR, HackRF, PlutoSDR, etc.) produce very different absolute
signal levels. Without a reference level control, the user has no way to shift the display
so that the noise floor sits at a reasonable position on screen. This is standard on most
spectrum analyzer applications.

### 2. dB Conversion Fix

**File:** `spectrum/converter/ComplexDecibelConverter.java`

**Before:**
```java
decibels = 10.0f * (float)FastMath.log10(temp * dftBinSizeScalor);
```
This computes `10*log10(power * scalor)` where `scalor = 1/(N*N)` and `temp` is the power
(magnitude squared). The result is in power-dB but uses a scalor that mixes amplitude and
power scaling.

**After:**
```java
decibels = 20.0f * (float)FastMath.log10((float)FastMath.sqrt(temp) * dftBinSizeScalor);
```
This computes `20*log10(amplitude / N)` which is the standard amplitude-domain dB calculation.
The scalor is now `1/N` (amplitude scaling, not power scaling).

**Why:**
`SpectrumPanel.setSampleSize()` computes the display dB range using `20*log10(2^(bits-1))`,
which is an amplitude-domain formula. The DFT output must use the same domain for the
spectrum trace to line up correctly with the dB scale. The old formula produced values that
were compressed by a factor of 2 in dB space, making the spectrum appear flatter than it
should.

## Testing

- Build: `gradlew clean build` (compilation verified)
- Runtime: Right-click spectrum area → Display → Reference Level → drag slider
- Verify: spectrum trace and waterfall respond in sync; 0 dB = no change from previous behavior
- Verify: spectrum amplitude range looks correct for known signals
