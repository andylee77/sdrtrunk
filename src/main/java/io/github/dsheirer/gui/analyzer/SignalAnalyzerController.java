/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.gui.analyzer;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.gui.analyzer.characterization.SpectralClassifier;
import io.github.dsheirer.gui.analyzer.detection.DetectedSignal;
import io.github.dsheirer.gui.analyzer.detection.HarmonicAnalyzer;
import io.github.dsheirer.gui.analyzer.detection.SignalDetector;
import io.github.dsheirer.gui.analyzer.detection.SignalTracker;
import io.github.dsheirer.gui.analyzer.identification.IdentificationResult;
import io.github.dsheirer.gui.analyzer.identification.SignalIdentifier;
import io.github.dsheirer.gui.analyzer.ui.AnalysisLogPanel;
import io.github.dsheirer.gui.analyzer.ui.AnalyzerControlPanel;
import io.github.dsheirer.gui.analyzer.ui.SignalAnalysisTableModel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.spectrum.DFTResultsListener;
import io.github.dsheirer.spectrum.SpectralDisplayPanel;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main orchestrator for the signal analyzer. Manages the analysis state machine:
 * IDLE → SCANNING → DETECTING → VALIDATING → IDENTIFYING
 *
 * Phase 1: Detection and validation (FFT peak finding, harmonic/spur flagging)
 * Phase 2: Characterization (spectral shape classification — modulation/protocol heuristics)
 * Phase 3: Identification (actual decoder trials — creates temporary channels, runs decoders,
 *          counts valid messages to confirm protocol identification)
 *
 * Supports two modes:
 * - AUTO: Continuously scans, detects, and validates signals
 * - MANUAL: User triggers each step or selects individual signals to analyze
 */
public class SignalAnalyzerController implements DFTResultsListener
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalAnalyzerController.class);

    /**
     * Analyzer states.
     */
    public enum AnalyzerState
    {
        IDLE,
        SCANNING,
        STOPPED
    }

    /**
     * Analyzer operating modes.
     */
    public enum AnalyzerMode
    {
        AUTO,
        MANUAL
    }

    // Reference to spectral display for querying current tuner frequency/bandwidth
    private SpectralDisplayPanel mSpectralPanel;

    // Engine components
    private final SignalDetector mDetector;
    private final SignalTracker mTracker;
    private final HarmonicAnalyzer mHarmonicAnalyzer;
    private final SpectralClassifier mSpectralClassifier;
    private final SignalAnalyzerConfig mConfig;

    // Phase 3: Decoder trial identification engine (null if no ChannelProcessingManager available)
    private SignalIdentifier mSignalIdentifier;

    // UI components
    private final SignalAnalysisTableModel mTableModel;
    private final AnalyzerControlPanel mControlPanel;
    private final AnalysisLogPanel mLogPanel;

    // State
    private AnalyzerState mState = AnalyzerState.IDLE;
    private AnalyzerMode mMode = AnalyzerMode.AUTO;

    // Minimum detection count before auto-identification triggers (signal must be stable)
    private static final int AUTO_IDENTIFY_MIN_DETECTIONS = 2;

    // Track frequencies already submitted for identification to prevent re-identification
    // when signals disappear and reappear (tracker creates new DetectedSignal objects).
    // Frequency is rounded to nearest kHz (in Hz) to match within channel bandwidth.
    private final Set<Long> mIdentifiedFrequencies = new HashSet<>();
    private static final long FREQ_MATCH_TOLERANCE_HZ = 2500; // 2.5 kHz tolerance

    // Track whether we locked the tuner frequency so we can restore state on stop
    private boolean mFrequencyLockApplied = false;
    private boolean mPreviousFrequencyLockState = false;

    public SignalAnalyzerController(SignalAnalyzerConfig config, SignalDetector detector,
                                     SignalTracker tracker, HarmonicAnalyzer harmonicAnalyzer,
                                     SignalAnalysisTableModel tableModel, AnalyzerControlPanel controlPanel,
                                     AnalysisLogPanel logPanel)
    {
        mConfig = config;
        mDetector = detector;
        mTracker = tracker;
        mHarmonicAnalyzer = harmonicAnalyzer;
        mSpectralClassifier = new SpectralClassifier();
        mTableModel = tableModel;
        mControlPanel = controlPanel;
        mLogPanel = logPanel;

        // Wire the detector output to our processing method
        mDetector.setSignalListener(this::onSignalsDetected);

        // Wire the Export CSV button
        mLogPanel.getExportButton().addActionListener(e -> exportToCsv());
    }

    /**
     * Set the SpectralDisplayPanel reference so the analyzer can query the current
     * tuner frequency and bandwidth when starting a scan.
     */
    public void setSpectralPanel(SpectralDisplayPanel spectralPanel)
    {
        mSpectralPanel = spectralPanel;
    }

    /**
     * Query the current tuner's frequency and bandwidth from the spectral display
     * and push them to the detector and harmonic analyzer. Called once at scan start.
     */
    private void syncTunerParameters()
    {
        if(mSpectralPanel != null)
        {
            Tuner tuner = mSpectralPanel.getTuner();
            if(tuner != null)
            {
                try
                {
                    long freq = tuner.getTunerController().getFrequency();
                    int sampleRate = (int)tuner.getTunerController().getSampleRate();
                    mDetector.setCenterFrequency(freq);
                    mDetector.setBandwidth(sampleRate);
                    mHarmonicAnalyzer.setCenterFrequency(freq);
                }
                catch(Exception e)
                {
                    mLog.warn("Failed to read tuner parameters: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Lock the tuner's centre frequency to prevent the PolyphaseChannelSourceManager
     * from retuning when trial channels are started. Saves the previous lock state
     * so it can be restored on stop.
     */
    private void lockTunerFrequency()
    {
        if(mSpectralPanel != null)
        {
            Tuner tuner = mSpectralPanel.getTuner();
            if(tuner != null)
            {
                try
                {
                    mPreviousFrequencyLockState = tuner.getTunerController().isFrequencyLocked();
                    tuner.getTunerController().setFrequencyLocked(true);
                    mFrequencyLockApplied = true;
                    mLog.info("Tuner centre frequency locked for signal analysis");
                }
                catch(Exception e)
                {
                    mLog.warn("Failed to lock tuner frequency: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Restore the tuner's previous frequency lock state after analysis stops.
     */
    private void unlockTunerFrequency()
    {
        if(mFrequencyLockApplied && mSpectralPanel != null)
        {
            Tuner tuner = mSpectralPanel.getTuner();
            if(tuner != null)
            {
                try
                {
                    tuner.getTunerController().setFrequencyLocked(mPreviousFrequencyLockState);
                    mLog.info("Tuner centre frequency lock restored to: {}", mPreviousFrequencyLockState);
                }
                catch(Exception e)
                {
                    mLog.warn("Failed to restore tuner frequency lock state: {}", e.getMessage());
                }
            }
            mFrequencyLockApplied = false;
        }
    }

    /**
     * Set the ChannelProcessingManager for Phase 3 decoder trial identification.
     * This enables the "Identify Now" feature. Must be called after construction
     * since the CPM may not be available during panel construction.
     */
    public void setChannelProcessingManager(ChannelProcessingManager channelProcessingManager)
    {
        if(channelProcessingManager != null)
        {
            mSignalIdentifier = new SignalIdentifier(channelProcessingManager, mConfig);
            mLog.info("Signal identifier initialized — Phase 3 decoder trials available");
        }
    }

    /**
     * Check if Phase 3 identification (decoder trials) is available.
     */
    public boolean isIdentificationAvailable()
    {
        return mSignalIdentifier != null;
    }

    /**
     * Check if a decoder trial is currently in progress.
     */
    public boolean isTrialInProgress()
    {
        return mSignalIdentifier != null && mSignalIdentifier.isTrialInProgress();
    }

    /**
     * Phase 3: Identify a signal by running actual decoder trials.
     * Creates temporary channels, starts decoders, and counts valid messages
     * to confirm the protocol identification from spectral classification.
     *
     * This is an asynchronous operation. Results are logged and the signal's
     * status is updated as trials progress.
     */
    public void identifySignal(DetectedSignal signal)
    {
        if(mSignalIdentifier == null)
        {
            mLogPanel.logError("Identification not available — no channel processing manager");
            return;
        }

        if(signal.isArtifact())
        {
            mLogPanel.logWarning(String.format("%.4f MHz: Signal is flagged as artifact — skipping identification",
                signal.getFrequencyMHz()));
            return;
        }

        // Update status
        signal.setStatus(AnalysisStatus.IDENTIFYING);
        refreshTable();

        mLogPanel.logInfo(String.format("%.4f MHz: Starting decoder trial identification (dwell: %ds)...",
            signal.getFrequencyMHz(), mConfig.getDwellTimeMs() / 1000));

        // Log candidate decoders
        List<DecoderType> candidates = mSignalIdentifier.getCandidateDecoders(signal);
        mLogPanel.logInfo(String.format("%.4f MHz: Candidates: %s", signal.getFrequencyMHz(), candidates));

        // Run identification asynchronously
        mSignalIdentifier.identifySignal(signal, createIdentificationCallback());
    }

    /**
     * Cancel any in-progress decoder trial.
     */
    public void cancelIdentification()
    {
        if(mSignalIdentifier != null)
        {
            mSignalIdentifier.cancelTrial();
            mLogPanel.logWarning("Decoder trial cancelled");
        }
    }

    /**
     * Handle the result of a single decoder trial.
     * Called on EDT.
     */
    private void handleTrialResult(DetectedSignal signal, IdentificationResult result, boolean isFinalResult)
    {
        if(result.isSuccess())
        {
            if(result.isTentative())
            {
                // Tentative match — frames found but no valid CRC messages
                signal.setIdentifiedDecoder(result.getDecoderType());
                signal.setValidMessages(result.getDecodeEventCount());
                signal.setSampleMessages(result.getSampleMessages());
                signal.setIdentificationConfidence("Tentative");
                signal.setStatus(AnalysisStatus.TENTATIVE);

                String logMsg = String.format("%.4f MHz: ⚠ TENTATIVE — %s (CRC-failed frames found, likely encrypted/intermittent, %dms)",
                    signal.getFrequencyMHz(),
                    result.getDecoderType().getDisplayString(),
                    result.getTrialDurationMs());
                mLogPanel.logWarning(logMsg);

                // Log diagnostic info
                if(!result.getSampleMessages().isEmpty())
                {
                    mLogPanel.logInfo(String.format("  → %s", result.getSampleMessages().get(0)));
                }
            }
            else
            {
                // Confirmed match — valid CRC messages decoded
                signal.setIdentifiedDecoder(result.getDecoderType());
                signal.setValidMessages(result.getDecodeEventCount());
                signal.setSampleMessages(result.getSampleMessages());
                signal.setIdentificationConfidence(result.getConfidence().getLabel());
                signal.setStatus(AnalysisStatus.IDENTIFIED);

                // Store NAC if available
                if(result.getNac() != null)
                {
                    signal.setNac(result.getNac());
                }
                // Also try to extract NAC from sample messages for cases where
                // NAC didn't meet majority voting threshold but was observed
                else
                {
                    extractNacFromSamples(signal, result.getSampleMessages());
                }

                // Store system info (NAC, CC, etc.) from decoder trial
                if(result.getSystemInfo() != null)
                {
                    signal.setSystemInfo(result.getSystemInfo());
                }

                String sysInfoDisplay = result.getSystemInfo() != null ? " [" + result.getSystemInfo() + "]" :
                    (signal.getNac() != null ? " [NAC:" + signal.getNac() + "]" : "");
                String logMsg = String.format("%.4f MHz: %s — %s confidence, %d events%s",
                    signal.getFrequencyMHz(),
                    result.getDecoderType().getDisplayString(),
                    result.getConfidence().getLabel(),
                    result.getDecodeEventCount(),
                    sysInfoDisplay);
                mLogPanel.logSuccess(logMsg);

                // Log sample messages (only key ones)
                if(!result.getSampleMessages().isEmpty())
                {
                    int count = Math.min(3, result.getSampleMessages().size());
                    for(int i = 0; i < count; i++)
                    {
                        mLogPanel.logInfo(String.format("  → %s", result.getSampleMessages().get(i)));
                    }
                    if(result.getSampleMessages().size() > 3)
                    {
                        mLogPanel.logInfo(String.format("  ... and %d more events",
                            result.getSampleMessages().size() - 3));
                    }
                }
            }
        }
        else
        {
            // This decoder didn't match — accumulate diagnostic data from every trial
            signal.addTrialDiagnostic(result.getDiagnosticSummary());

            if(isFinalResult && signal.getStatus() == AnalysisStatus.IDENTIFYING)
            {
                signal.setStatus(AnalysisStatus.NO_MATCH);

                // Log diagnostic summary for NO_MATCH signals
                StringBuilder diagMsg = new StringBuilder();
                diagMsg.append(String.format("%.4f MHz: No match — %d decoders tried",
                    signal.getFrequencyMHz(), signal.getTrialDiagnostics().size()));

                // Show which decoders had any activity and build table summary
                boolean hasActivity = false;
                StringBuilder activitySummary = new StringBuilder();
                for(String diag : signal.getTrialDiagnostics())
                {
                    if(!diag.contains("0 crcFail, 0 syncLoss") && !diag.contains("0 events, 0 crcFail"))
                    {
                        if(!hasActivity)
                        {
                            diagMsg.append(" | Activity:");
                            hasActivity = true;
                        }
                        diagMsg.append(" [").append(diag).append("]");
                        if(activitySummary.length() > 0) activitySummary.append("; ");
                        activitySummary.append(diag);
                    }
                }
                if(!hasActivity)
                {
                    diagMsg.append(" — no decoder activity (unsupported protocol)");
                    signal.setSystemInfo("Unknown protocol — no decoder matched");
                }
                else
                {
                    signal.setSystemInfo("Activity: " + activitySummary);
                }

                mLogPanel.logWarning(diagMsg.toString());
            }
            // Intermediate failures are NOT logged to reduce noise
        }

        refreshTable();
    }

    /**
     * Try to extract a NAC from sample messages for signals where majority voting
     * didn't confirm a NAC but individual messages contain it.
     */
    private void extractNacFromSamples(DetectedSignal signal, List<String> samples)
    {
        for(String sample : samples)
        {
            // Look for patterns like [NAC:954/x3BA] or NAC: 954/x3BA
            int nacIdx = sample.indexOf("[NAC:");
            if(nacIdx >= 0)
            {
                int endIdx = sample.indexOf("]", nacIdx);
                if(endIdx > nacIdx)
                {
                    signal.setNac(sample.substring(nacIdx + 5, endIdx));
                    return;
                }
            }
            nacIdx = sample.indexOf("NAC: ");
            if(nacIdx >= 0 && sample.indexOf("(confirmed)") > nacIdx)
            {
                int endIdx = sample.indexOf(" (confirmed)", nacIdx);
                if(endIdx > nacIdx)
                {
                    signal.setNac(sample.substring(nacIdx + 5, endIdx));
                    return;
                }
            }
        }
    }

    /**
     * Create a shared IdentificationCallback for use by both manual and auto identification.
     * Log noise reduction: only logs the FIRST decoder trial per signal, not every intermediate attempt.
     */
    private SignalIdentifier.IdentificationCallback createIdentificationCallback()
    {
        return new SignalIdentifier.IdentificationCallback()
        {
            @Override
            public void onTrialStarted(DetectedSignal sig, DecoderType decoder)
            {
                // Only log the first decoder trial for each signal to reduce log noise.
                // For the first trial (P25 Phase 1), this is logged.
                // Subsequent trials (DMR, P25 Phase 2, LTR, etc.) are silent in the UI.
                if(decoder == DecoderType.P25_PHASE1)
                {
                    SwingUtilities.invokeLater(() -> {
                        mLogPanel.logInfo(String.format("%.4f MHz: Identifying...",
                            sig.getFrequencyMHz()));
                    });
                }
                // All trials are still logged at DEBUG level in SignalIdentifier
            }

            @Override
            public void onTrialComplete(DetectedSignal sig, IdentificationResult result, boolean isFinalResult)
            {
                SwingUtilities.invokeLater(() -> {
                    handleTrialResult(sig, result, isFinalResult);
                });
            }

            @Override
            public void onIdentificationError(DetectedSignal sig, String error)
            {
                SwingUtilities.invokeLater(() -> {
                    mLogPanel.logError(String.format("%.4f MHz: Identification error — %s",
                        sig.getFrequencyMHz(), error));
                    sig.setStatus(AnalysisStatus.NO_MATCH);
                    refreshTable();
                });
            }

            @Override
            public void onBatchComplete(int identified, int noMatch, int failed)
            {
                // Suppress all-zeros "Batch complete" spam when idle
                if(identified == 0 && noMatch == 0 && failed == 0)
                {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    mLogPanel.logInfo(String.format("Batch complete: %d identified, %d no match, %d failed",
                        identified, noMatch, failed));
                    refreshTable();
                });
            }
        };
    }

    /**
     * Refresh the signal table on EDT.
     */
    private void refreshTable()
    {
        SwingUtilities.invokeLater(() -> {
            mTableModel.updateSignals(mTracker.getTrackedSignals());
        });
    }

    /**
     * Feed detected signals to the continuous identification engine.
     * Signals that are ready for identification (detected enough times, not artifacts,
     * not already identified) are added to the engine which will open trial channels
     * for up to 20 signals simultaneously.
     *
     * ALL signals go through decoder trials regardless of spectral classification.
     * The FFT-based classifier cannot reliably distinguish idle digital channels from
     * analog FM — only actual decoder trials with CRC-valid messages confirm protocol.
     */
    private void feedSignalsToIdentifier(List<DetectedSignal> allSignals)
    {
        if(mSignalIdentifier == null)
        {
            return;
        }

        // Start the identification engine if not yet running
        if(!mSignalIdentifier.isRunning())
        {
            mSignalIdentifier.start(createIdentificationCallback());
        }

        // Collect all signals that are ready for identification
        List<DetectedSignal> readySignals = new ArrayList<>();
        for(DetectedSignal signal : allSignals)
        {
            if(signal.getStatus() == AnalysisStatus.CHARACTERIZING &&
               signal.getDetectionCount() >= AUTO_IDENTIFY_MIN_DETECTIONS &&
               !signal.isArtifact() &&
               !isFrequencyAlreadyTried(signal.getFrequencyHz()))
            {
                mIdentifiedFrequencies.add(signal.getFrequencyHz());
                signal.setStatus(AnalysisStatus.IDENTIFYING);
                readySignals.add(signal);
            }
        }

        if(!readySignals.isEmpty())
        {
            mSignalIdentifier.addSignals(readySignals);
        }
    }

    /**
     * Check if a frequency has already been submitted for identification.
     * Uses tolerance to match frequencies within a channel width.
     */
    private boolean isFrequencyAlreadyTried(long frequencyHz)
    {
        for(Long tried : mIdentifiedFrequencies)
        {
            if(Math.abs(tried - frequencyHz) <= FREQ_MATCH_TOLERANCE_HZ)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the signal detector (used for wiring DFT listener).
     */
    public SignalDetector getDetector()
    {
        return mDetector;
    }

    /**
     * Get the current analyzer state.
     */
    public AnalyzerState getState()
    {
        return mState;
    }

    /**
     * Start scanning. In AUTO mode, begins the full detection pipeline.
     * In MANUAL mode, starts detection only.
     */
    public void startScan()
    {
        if(mState == AnalyzerState.SCANNING)
        {
            mLog.warn("Signal analyzer is already scanning");
            return;
        }

        mMode = mControlPanel.isAutoMode() ? AnalyzerMode.AUTO : AnalyzerMode.MANUAL;
        mState = AnalyzerState.SCANNING;

        // Sync tuner frequency/bandwidth from spectral display before starting
        syncTunerParameters();

        // Lock the tuner's centre frequency to prevent trial channels from retuning
        lockTunerFrequency();

        mDetector.start();
        mControlPanel.setScanning(true);

        mLogPanel.logInfo("Signal analyzer started in " + mMode + " mode");
        mLogPanel.logInfo(String.format("Threshold: %.0f dB above noise floor, Min BW: %.1f kHz",
            mConfig.getThresholdDb(), mConfig.getMinBandwidthHz() / 1000.0));

        if(mDetector.getCenterFrequency() > 0)
        {
            mLogPanel.logInfo(String.format("Tuner: center=%.4f MHz, bandwidth=%.3f MHz",
                mDetector.getCenterFrequency() / 1_000_000.0,
                mDetector.getBandwidth() / 1_000_000.0));
        }
        else
        {
            mLogPanel.logWarning("No tuner frequency set — waiting for tuner data...");
        }

        mLog.info("Signal analyzer started: mode={}, threshold={} dB", mMode, mConfig.getThresholdDb());
    }

    /**
     * Stop scanning and any ongoing analysis.
     */
    public void stopScan()
    {
        if(mState != AnalyzerState.SCANNING)
        {
            return;
        }

        // Stop the identification engine first (closes all trial channels)
        if(mSignalIdentifier != null && mSignalIdentifier.isRunning())
        {
            mSignalIdentifier.stop();
        }

        // Restore the tuner frequency lock state
        unlockTunerFrequency();

        mDetector.stop();
        mState = AnalyzerState.STOPPED;
        mControlPanel.setScanning(false);

        int total = mTracker.getSignalCount();
        mLogPanel.logInfo("Signal analyzer stopped. " + total + " signals tracked.");
        mLog.info("Signal analyzer stopped: {} signals tracked", total);
    }

    /**
     * Clear all tracked signals and reset.
     */
    public void clearAll()
    {
        mTracker.clearAll();
        mTableModel.clear();
        mIdentifiedFrequencies.clear();
        mControlPanel.updateSignalCount(0, 0);
        mLogPanel.logInfo("All signals cleared");
    }

    /**
     * Called by SignalDetector when new signals are detected.
     * This runs on the DFT callback thread — must dispatch UI updates to EDT.
     */
    private void onSignalsDetected(List<DetectedSignal> rawDetections)
    {
        // 1. Merge with existing tracked signals
        List<DetectedSignal> allSignals = mTracker.processDetections(rawDetections);

        // 2. Run harmonic/image/DC analysis
        mHarmonicAnalyzer.analyze(allSignals);

        // 3. Run spectral classification (modulation/protocol identification from FFT shape)
        float[] spectrum = mDetector.getLastAveragedSpectrum();
        if(spectrum != null && mDetector.getCenterFrequency() > 0 && mDetector.getBandwidth() > 0)
        {
            mSpectralClassifier.classify(allSignals, spectrum,
                mDetector.getCenterFrequency(), mDetector.getBandwidth(), mDetector.getDftSize());
        }

        // 4. Auto-identification: feed signals to continuous identification engine (AUTO mode)
        if(mMode == AnalyzerMode.AUTO && mSignalIdentifier != null)
        {
            feedSignalsToIdentifier(allSignals);
        }

        // 5. Log new detections (only signals that were actually added to the tracker, not
        //    matched to existing tracked signals — check by verifying they're in allSignals)
        for(DetectedSignal detection : rawDetections)
        {
            if(allSignals.contains(detection) && detection.getDetectionCount() == 1)
            {
                logNewDetection(detection);
            }
        }

        // 6. Update UI on EDT
        final List<DetectedSignal> displaySignals;
        if(mConfig.isShowFlagged())
        {
            displaySignals = allSignals;
        }
        else
        {
            // Filter out flagged signals for display
            displaySignals = allSignals.stream()
                .filter(s -> !s.isArtifact())
                .collect(java.util.stream.Collectors.toList());
        }

        SwingUtilities.invokeLater(() -> {
            mTableModel.updateSignals(displaySignals);
            int total = allSignals.size();
            int valid = (int)allSignals.stream().filter(s -> !s.isArtifact()).count();
            mControlPanel.updateSignalCount(total, valid);
        });
    }

    /**
     * Log a new signal detection to the analysis log, including classification results.
     */
    private void logNewDetection(DetectedSignal signal)
    {
        String message = String.format("%.4f MHz: Detected — power %.1f dB, BW ~%.1f kHz",
            signal.getFrequencyMHz(), signal.getPowerDb(), signal.getBandwidthKHz());

        if(signal.isArtifact())
        {
            mLogPanel.logWarning(message + " [" + signal.getFlagsDisplayString() + "]");
        }
        else
        {
            mLogPanel.logDetection(message);
        }

        // Log classification results if available
        if(signal.getModulationType() != null &&
           signal.getModulationType() != io.github.dsheirer.gui.analyzer.ModulationType.UNKNOWN)
        {
            StringBuilder classMsg = new StringBuilder();
            classMsg.append(String.format("%.4f MHz: Classified — %s",
                signal.getFrequencyMHz(), signal.getModulationType().getDisplayName()));
            if(signal.getSymbolRate() > 0)
            {
                classMsg.append(String.format(", %d baud", signal.getSymbolRate()));
            }
            if(signal.getIdentifiedDecoder() != null)
            {
                classMsg.append(String.format(" → %s", signal.getIdentifiedDecoder().getDisplayString()));
            }
            mLogPanel.logInfo(classMsg.toString());
        }
    }

    /**
     * Manual mode: user selects a signal and triggers ignore.
     */
    public void ignoreSignal(DetectedSignal signal)
    {
        mTracker.ignoreSignal(signal);
        mLogPanel.logInfo(String.format("%.4f MHz: Ignored by user", signal.getFrequencyMHz()));

        // Refresh display
        SwingUtilities.invokeLater(() -> {
            mTableModel.updateSignals(mTracker.getTrackedSignals());
        });
    }

    /**
     * Remove a signal from tracking.
     */
    public void deleteSignal(DetectedSignal signal)
    {
        mTracker.removeSignal(signal);
        mLogPanel.logInfo(String.format("%.4f MHz: Removed", signal.getFrequencyMHz()));

        SwingUtilities.invokeLater(() -> {
            mTableModel.updateSignals(mTracker.getTrackedSignals());
        });
    }

    /**
     * Export all tracked signals to a CSV file.
     * Opens a file chooser dialog, then writes signal data as CSV.
     */
    public void exportToCsv()
    {
        List<DetectedSignal> signals = mTracker.getTrackedSignals();

        if(signals.isEmpty())
        {
            mLogPanel.logWarning("No signals to export");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Signals to CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));

        // Default filename with timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String defaultName = "signal_analysis_" + timestamp + ".csv";
        chooser.setSelectedFile(new File(defaultName));

        int result = chooser.showSaveDialog(mLogPanel);

        if(result == JFileChooser.APPROVE_OPTION)
        {
            File file = chooser.getSelectedFile();

            // Add .csv extension if not present
            if(!file.getName().toLowerCase().endsWith(".csv"))
            {
                file = new File(file.getAbsolutePath() + ".csv");
            }

            try(PrintWriter writer = new PrintWriter(new FileWriter(file)))
            {
                // Header
                writer.println("Frequency_MHz,Frequency_Hz,Power_dB,Bandwidth_kHz,Modulation,Symbol_Rate,Protocol,Confidence,NAC,System_Info,Flags,Status,Detection_Count,Persistent,Trial_Diagnostics");

                // Data rows
                for(DetectedSignal signal : signals)
                {
                    // Join trial diagnostics with semicolons for CSV
                    String diagStr = signal.getTrialDiagnostics().isEmpty() ? "" :
                        String.join("; ", signal.getTrialDiagnostics());

                    writer.printf("%.4f,%d,%.1f,%.1f,%s,%d,%s,%s,%s,\"%s\",\"%s\",%s,%d,%s,\"%s\"%n",
                        signal.getFrequencyMHz(),
                        signal.getFrequencyHz(),
                        signal.getPowerDb(),
                        signal.getBandwidthKHz(),
                        signal.getModulationType().getShortName(),
                        signal.getSymbolRate(),
                        signal.getIdentifiedDecoder() != null ?
                            signal.getIdentifiedDecoder().getShortDisplayString() : "",
                        signal.getIdentificationConfidence() != null ?
                            signal.getIdentificationConfidence() : "",
                        signal.getNac() != null ? signal.getNac() : "",
                        signal.getSystemInfoDisplay(),
                        signal.getFlagsDisplayString(),
                        signal.getStatus().getLabel(),
                        signal.getDetectionCount(),
                        signal.isPersistent(),
                        diagStr
                    );
                }

                mLogPanel.logSuccess("Exported " + signals.size() + " signals to: " + file.getName());
                mLog.info("Signal analysis exported to: {}", file.getAbsolutePath());
            }
            catch(IOException ex)
            {
                mLogPanel.logError("Export failed: " + ex.getMessage());
                mLog.error("Failed to export signal analysis CSV", ex);
            }
        }
    }

    // ---- DFTResultsListener implementation ----

    /**
     * Receives FFT results (dB values) from the spectral display pipeline.
     * Delegates to the SignalDetector for peak-finding.
     */
    @Override
    public void receive(float[] results)
    {
        mDetector.receive(results);
    }

}
