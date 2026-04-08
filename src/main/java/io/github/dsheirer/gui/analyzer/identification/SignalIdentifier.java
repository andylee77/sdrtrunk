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
package io.github.dsheirer.gui.analyzer.identification;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.gui.analyzer.SignalAnalyzerConfig;
import io.github.dsheirer.gui.analyzer.detection.DetectedSignal;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.ltrnet.DecodeConfigLTRNet;
import io.github.dsheirer.module.decode.ltrstandard.DecodeConfigLTRStandard;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.passport.DecodeConfigPassport;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuous decoder trial engine for the signal analyzer.
 *
 * Architecture:
 * - Maintains up to MAX_CONCURRENT_CHANNELS active trial channels simultaneously
 * - New signals can be added at any time via addSignals() — no batch locking
 * - A periodic check task runs every dwell period to evaluate results:
 *   - Channels with decode events → IDENTIFIED, channel closed, slot freed
 *   - Channels with no events → channel closed, next candidate decoder tried
 *   - All candidates exhausted → NO_MATCH
 * - Digital decoders are ALWAYS tried first (P25 Phase 1 → DMR → P25 Phase 2)
 * - NBFM/AM are only tried as last resort after all digital decoders fail
 *
 * Decoder priority order (always):
 *   1. P25 Phase 1 (most common trunked digital)
 *   2. DMR
 *   3. P25 Phase 2
 *   4. NBFM (analog fallback)
 *   5. AM (if applicable)
 */
public class SignalIdentifier
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalIdentifier.class);

    /** Maximum number of trial channels running simultaneously */
    private static final int MAX_CONCURRENT_CHANNELS = 20;

    // Confidence thresholds — require CRC-valid messages for real confirmation
    private static final int HIGH_CONFIDENCE_EVENTS = 10;
    private static final int MEDIUM_CONFIDENCE_EVENTS = 3;
    private static final int LOW_CONFIDENCE_EVENTS = 1;
    /** Minimum NAC observations for consistent NAC confirmation */
    private static final int MIN_NAC_OBSERVATIONS = 3;
    /** Minimum ratio of dominant NAC to total NAC observations (e.g. 0.8 = 80%) */
    private static final double NAC_CONSISTENCY_RATIO = 0.75;

    /** Standard decoder candidate list — all digital decoders, no analog fallback */
    private static final List<DecoderType> DIGITAL_FIRST_CANDIDATES = Arrays.asList(
        DecoderType.P25_PHASE1,
        DecoderType.DMR,
        DecoderType.P25_PHASE2,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.PASSPORT
    );

    private final ChannelProcessingManager mChannelProcessingManager;
    private final SignalAnalyzerConfig mConfig;
    private final ScheduledExecutorService mExecutor;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private ScheduledFuture<?> mCheckTask;

    /** Signals waiting to be assigned a trial channel (pending queue) */
    private final ConcurrentLinkedQueue<DetectedSignal> mPendingSignals = new ConcurrentLinkedQueue<>();

    /** Active trial channels — signal → trial state */
    private final Map<DetectedSignal, TrialState> mActiveTrials = new ConcurrentHashMap<>();

    /** Callback for reporting results */
    private volatile IdentificationCallback mCallback;

    /**
     * Holds the state for a single signal being identified.
     * Tracks which decoder candidate we're currently trying and the event counter.
     */
    /** Minimum CRC-failed frames to consider a tentative protocol match */
    private static final int TENTATIVE_MIN_CRC_FAILS = 3;
    /** Minimum sync loss events to consider tentative match (sync was found repeatedly) */
    private static final int TENTATIVE_MIN_SYNC_LOSS = 5;

    private static class TrialState
    {
        final DetectedSignal signal;
        final List<DecoderType> candidates;
        int candidateIndex;
        Channel channel;
        DecoderType currentDecoder;
        TrialCounter trialCounter;
        boolean channelStarted;
        long trialStartTime;
        long overallStartTime;

        // Track the best-performing decoder across ALL candidates for tentative match
        DecoderType bestTentativeDecoder;
        int bestTentativeCrcFails;
        int bestTentativeSyncLoss;
        List<String> bestTentativeSamples;

        TrialState(DetectedSignal signal, List<DecoderType> candidates)
        {
            this.signal = signal;
            this.candidates = candidates;
            this.candidateIndex = 0;
            this.channelStarted = false;
            this.overallStartTime = System.currentTimeMillis();
        }

        /**
         * Record stats from a failed trial for potential tentative match fallback.
         * Keeps track of which decoder produced the most frame activity.
         */
        void recordTrialStats(DecoderType decoder, TrialCounter counter)
        {
            int crcFails = counter.getInvalidMessageCount();
            int syncLoss = counter.getSyncLossCount();
            int activity = crcFails + syncLoss;
            int bestActivity = bestTentativeCrcFails + bestTentativeSyncLoss;

            if(activity > bestActivity)
            {
                bestTentativeDecoder = decoder;
                bestTentativeCrcFails = crcFails;
                bestTentativeSyncLoss = syncLoss;
                bestTentativeSamples = counter.getSampleMessages();
            }
        }

        /**
         * Check if we have enough evidence for a tentative (unconfirmed) protocol match.
         * This indicates actual frames were detected but CRC validation failed
         * (likely encrypted traffic, weak signal, or intermittent channel).
         *
         * IMPORTANT: Sync losses alone are NOT sufficient — P25 Phase 2 (and others)
         * produce false sync matches on random noise. We REQUIRE actual CRC-failed frames
         * which means the decoder found real frame structure, not just sync patterns.
         */
        boolean hasTentativeMatch()
        {
            return bestTentativeDecoder != null &&
                bestTentativeCrcFails >= TENTATIVE_MIN_CRC_FAILS;
        }

        boolean hasMoreCandidates()
        {
            return candidateIndex < candidates.size();
        }

        DecoderType nextCandidate()
        {
            if(hasMoreCandidates())
            {
                return candidates.get(candidateIndex++);
            }
            return null;
        }
    }

    /**
     * Callback interface for identification results.
     */
    public interface IdentificationCallback
    {
        void onTrialStarted(DetectedSignal signal, DecoderType decoder);
        void onTrialComplete(DetectedSignal signal, IdentificationResult result, boolean isFinalResult);
        void onIdentificationError(DetectedSignal signal, String error);
        void onBatchComplete(int identified, int noMatch, int failed);
    }

    public SignalIdentifier(ChannelProcessingManager channelProcessingManager, SignalAnalyzerConfig config)
    {
        mChannelProcessingManager = channelProcessingManager;
        mConfig = config;
        mExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "signal-identifier");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the continuous identification engine.
     * Begins periodic check cycle at dwell time intervals.
     */
    public void start(IdentificationCallback callback)
    {
        if(mRunning.compareAndSet(false, true))
        {
            mCallback = callback;
            int dwellMs = mConfig.getDwellTimeMs();
            // Schedule periodic check — first check after dwellMs, then every dwellMs
            mCheckTask = mExecutor.scheduleAtFixedRate(
                this::checkAndCycle, dwellMs, dwellMs, TimeUnit.MILLISECONDS);
            mLog.info("Signal identifier started: max {} channels, {}ms dwell cycle",
                MAX_CONCURRENT_CHANNELS, dwellMs);
        }
    }

    /**
     * Stop the identification engine and close all trial channels.
     */
    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mCheckTask != null)
            {
                mCheckTask.cancel(false);
                mCheckTask = null;
            }
            stopAllTrials();
            mPendingSignals.clear();
            mLog.info("Signal identifier stopped");
        }
    }

    public boolean isRunning()
    {
        return mRunning.get();
    }

    public boolean isTrialInProgress()
    {
        return !mActiveTrials.isEmpty() || !mPendingSignals.isEmpty();
    }

    /**
     * Add signals for identification. Can be called at any time while engine is running.
     * Signals are queued and will be assigned trial channels as slots become available.
     */
    public void addSignals(List<DetectedSignal> signals)
    {
        for(DetectedSignal signal : signals)
        {
            // Don't add duplicates — check if already active or pending
            if(!mActiveTrials.containsKey(signal) && !mPendingSignals.contains(signal))
            {
                mPendingSignals.add(signal);
            }
        }

        // If running, immediately try to fill available slots
        if(mRunning.get())
        {
            mExecutor.submit(this::fillSlots);
        }
    }

    /**
     * Add a single signal for identification (manual/right-click).
     */
    public void identifySignal(DetectedSignal signal, IdentificationCallback callback)
    {
        mCallback = callback;
        List<DetectedSignal> list = new ArrayList<>();
        list.add(signal);
        addSignals(list);

        // If not running, start the engine
        if(!mRunning.get())
        {
            start(callback);
        }
    }

    /**
     * Cancel identification for all signals.
     */
    public void cancelTrial()
    {
        stop();
    }

    public void shutdown()
    {
        stop();
        mExecutor.shutdown();
        try
        {
            if(!mExecutor.awaitTermination(5, TimeUnit.SECONDS))
            {
                mExecutor.shutdownNow();
            }
        }
        catch(InterruptedException e)
        {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the ordered candidate decoder list for a signal.
     * ALWAYS digital first: P25_P1 → DMR → P25_P2 → NBFM
     */
    public List<DecoderType> getCandidateDecoders(DetectedSignal signal)
    {
        // Always use digital-first ordering
        return new ArrayList<>(DIGITAL_FIRST_CANDIDATES);
    }

    // ---- Internal engine methods ----

    /**
     * Periodic check: evaluate all active trials, cycle decoders, fill slots.
     * Called by the scheduled executor at each dwell interval.
     */
    private void checkAndCycle()
    {
        if(!mRunning.get())
        {
            return;
        }

        try
        {
            int identified = 0;
            int noMatch = 0;
            int cycled = 0;

            // 1. Check all active trials for results
            Iterator<Map.Entry<DetectedSignal, TrialState>> it = mActiveTrials.entrySet().iterator();
            while(it.hasNext())
            {
                Map.Entry<DetectedSignal, TrialState> entry = it.next();
                DetectedSignal signal = entry.getKey();
                TrialState trial = entry.getValue();

                if(!trial.channelStarted)
                {
                    // Channel failed to start — try next candidate or give up
                    stopTrialChannel(trial);
                    if(trial.hasMoreCandidates())
                    {
                        startNextDecoder(trial);
                        cycled++;
                    }
                    else
                    {
                        it.remove();
                        reportNoMatch(signal, trial);
                        noMatch++;
                    }
                    continue;
                }

                int eventCount = trial.trialCounter.getEventCount();
                int msgCount = trial.trialCounter.getMessageCount();
                int decEvtCount = trial.trialCounter.getDecodeEventCount();
                long elapsed = System.currentTimeMillis() - trial.trialStartTime;

                if(eventCount >= LOW_CONFIDENCE_EVENTS)
                {
                    // SUCCESS — this decoder is producing events
                    IdentificationResult.Confidence confidence;
                    if(eventCount >= HIGH_CONFIDENCE_EVENTS)
                    {
                        confidence = IdentificationResult.Confidence.HIGH;
                    }
                    else if(eventCount >= MEDIUM_CONFIDENCE_EVENTS)
                    {
                        confidence = IdentificationResult.Confidence.MEDIUM;
                    }
                    else
                    {
                        confidence = IdentificationResult.Confidence.LOW;
                    }

                    List<String> samples = trial.trialCounter.getSampleMessages();
                    String nac = trial.trialCounter.getDetectedNAC();
                    String nacSummary = trial.trialCounter.getNacSummary();
                    int syncLoss = trial.trialCounter.getSyncLossCount();
                    int crcFail = trial.trialCounter.getInvalidMessageCount();
                    mLog.info("IDENTIFIED: {} at {} MHz — {} valid, {} crcFail, {} syncLoss, {} events, {}ms | {}{}",
                        trial.currentDecoder.getShortDisplayString(),
                        String.format("%.4f", trial.signal.getFrequencyMHz()),
                        msgCount, crcFail, syncLoss, decEvtCount, elapsed,
                        nacSummary,
                        nac != null ? " → confirmed NAC:" + nac : "");

                    // Build system info from extracted identifiers (NAC, CC, etc.)
                    String systemInfo = trial.trialCounter.buildSystemInfo(trial.currentDecoder);

                    // Prepend system info, NAC and CRC stats to sample messages
                    if(nac != null)
                    {
                        samples.add(0, "NAC: " + nac + " (confirmed)");
                    }
                    if(systemInfo != null && !systemInfo.startsWith("NAC:"))
                    {
                        // Add non-NAC system info (e.g., DMR CC) to samples
                        samples.add(0, "System: " + systemInfo);
                    }
                    samples.add(0, String.format("CRC: %d valid, %d failed, %d syncLoss",
                        msgCount, crcFail, syncLoss));

                    IdentificationResult result = IdentificationResult.success(
                        trial.currentDecoder, confidence, eventCount, elapsed, samples, nac, systemInfo);

                    stopTrialChannel(trial);
                    it.remove();

                    if(mCallback != null)
                    {
                        mCallback.onTrialComplete(signal, result, true);
                    }
                    identified++;
                }
                else
                {
                    // No valid events — record stats for tentative match, then cycle
                    trial.recordTrialStats(trial.currentDecoder, trial.trialCounter);

                    int crcFail = trial.trialCounter.getInvalidMessageCount();
                    int syncLoss = trial.trialCounter.getSyncLossCount();
                    mLog.debug("Trial failed: {} at {} MHz — 0 valid, {} crcFail, {} syncLoss, {}ms",
                        trial.currentDecoder.getShortDisplayString(),
                        String.format("%.4f", trial.signal.getFrequencyMHz()),
                        crcFail, syncLoss, elapsed);

                    stopTrialChannel(trial);

                    if(trial.hasMoreCandidates())
                    {
                        // Report this decoder failed with diagnostics, try next
                        IdentificationResult failResult = IdentificationResult.noMatchWithDiagnostics(
                            trial.currentDecoder, elapsed, syncLoss, crcFail);
                        if(mCallback != null)
                        {
                            mCallback.onTrialComplete(signal, failResult, false);
                        }

                        startNextDecoder(trial);
                        cycled++;
                    }
                    else
                    {
                        // All candidates exhausted — check for tentative match
                        it.remove();
                        reportNoMatch(signal, trial);
                        noMatch++;
                    }
                }
            }

            // 2. Fill any freed slots with pending signals
            fillSlots();

            // 3. Log summary if anything happened
            if(identified > 0 || noMatch > 0 || cycled > 0)
            {
                mLog.info("Check cycle: {} identified, {} no-match, {} cycled, {} active, {} pending",
                    identified, noMatch, cycled, mActiveTrials.size(), mPendingSignals.size());
            }

            // 4. If nothing left to do, stop the engine
            if(mActiveTrials.isEmpty() && mPendingSignals.isEmpty())
            {
                mLog.info("All signals processed — identifier idle");
                if(mCallback != null)
                {
                    mCallback.onBatchComplete(identified, noMatch, 0);
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("Error in check cycle", e);
        }
    }

    /**
     * Fill available channel slots with pending signals.
     */
    private void fillSlots()
    {
        while(mActiveTrials.size() < MAX_CONCURRENT_CHANNELS && !mPendingSignals.isEmpty())
        {
            DetectedSignal signal = mPendingSignals.poll();
            if(signal == null)
            {
                break;
            }

            // Skip if already being processed
            if(mActiveTrials.containsKey(signal))
            {
                continue;
            }

            List<DecoderType> candidates = getCandidateDecoders(signal);
            if(candidates.isEmpty())
            {
                if(mCallback != null)
                {
                    mCallback.onIdentificationError(signal, "No candidate decoders");
                }
                continue;
            }

            TrialState trial = new TrialState(signal, candidates);
            mActiveTrials.put(signal, trial);
            startNextDecoder(trial);
        }
    }

    /**
     * Start the next decoder candidate for a trial.
     */
    private void startNextDecoder(TrialState trial)
    {
        DecoderType decoder = trial.nextCandidate();
        if(decoder == null)
        {
            return;
        }

        trial.currentDecoder = decoder;
        trial.trialCounter = new TrialCounter();
        trial.trialStartTime = System.currentTimeMillis();
        trial.channelStarted = false;

        if(mCallback != null)
        {
            mCallback.onTrialStarted(trial.signal, decoder);
        }

        // Create channel
        Channel channel = new Channel("Analyzer-" + decoder.getShortDisplayString() + "-" +
            String.format("%.4f", trial.signal.getFrequencyMHz()));

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(trial.signal.getFrequencyHz());
        channel.setSourceConfiguration(sourceConfig);

        DecodeConfiguration decodeConfig = createDecodeConfiguration(decoder);
        if(decodeConfig == null)
        {
            mLog.warn("No decode config for {}", decoder);
            return;
        }
        channel.setDecodeConfiguration(decodeConfig);
        trial.channel = channel;

        try
        {
            mChannelProcessingManager.start(channel);
            trial.channelStarted = true;

            ProcessingChain chain = mChannelProcessingManager.getProcessingChain(channel);
            if(chain != null)
            {
                // Listen for BOTH decoded messages and decode events
                chain.addMessageListener(trial.trialCounter.getMessageListener());
                chain.addDecodeEventListener(trial.trialCounter.getDecodeEventListener());
                boolean hasSource = chain.getSource() != null;
                mLog.info("Trial started: {} at {} MHz (hasSource={})",
                    decoder.getShortDisplayString(),
                    String.format("%.4f", trial.signal.getFrequencyMHz()),
                    hasSource);
            }
            else
            {
                mLog.warn("No processing chain for {} at {} MHz",
                    decoder, String.format("%.4f", trial.signal.getFrequencyMHz()));
                trial.channelStarted = false;
            }
        }
        catch(ChannelException e)
        {
            mLog.warn("Failed to start {} at {} MHz: {}",
                decoder, String.format("%.4f", trial.signal.getFrequencyMHz()), e.getMessage());
            trial.channelStarted = false;
        }
        catch(Exception e)
        {
            mLog.error("Unexpected error starting {} at {} MHz",
                decoder, String.format("%.4f", trial.signal.getFrequencyMHz()), e);
            trial.channelStarted = false;
        }
    }

    /**
     * Stop a single trial channel and remove listener.
     */
    private void stopTrialChannel(TrialState trial)
    {
        if(trial.channel != null && trial.channelStarted)
        {
            try
            {
                ProcessingChain chain = mChannelProcessingManager.getProcessingChain(trial.channel);
                if(chain != null && trial.trialCounter != null)
                {
                    chain.removeMessageListener(trial.trialCounter.getMessageListener());
                    chain.removeDecodeEventListener(trial.trialCounter.getDecodeEventListener());
                }
                mChannelProcessingManager.stop(trial.channel);
            }
            catch(Exception e)
            {
                mLog.warn("Error stopping trial channel: {}", e.getMessage());
            }
        }
        trial.channelStarted = false;
        trial.channel = null;
    }

    /**
     * Report that all candidates were exhausted for a signal.
     * If a decoder showed significant frame activity (CRC fails or sync losses),
     * report a TENTATIVE match instead of plain "No Match".
     */
    private void reportNoMatch(DetectedSignal signal, TrialState trial)
    {
        long totalElapsed = System.currentTimeMillis() - trial.overallStartTime;

        if(trial.hasTentativeMatch())
        {
            // A decoder found sync and/or frames but couldn't get CRC-valid messages
            // This is strong evidence the signal IS that protocol (likely encrypted/intermittent)
            mLog.info("TENTATIVE: {} at {} MHz — {} crcFail, {} syncLoss (likely encrypted/weak/intermittent)",
                trial.bestTentativeDecoder.getShortDisplayString(),
                String.format("%.4f", trial.signal.getFrequencyMHz()),
                trial.bestTentativeCrcFails, trial.bestTentativeSyncLoss);

            List<String> samples = trial.bestTentativeSamples != null ?
                new ArrayList<>(trial.bestTentativeSamples) : new ArrayList<>();
            samples.add(0, String.format("Tentative: %d CRC-failed, %d syncLoss — no valid frames",
                trial.bestTentativeCrcFails, trial.bestTentativeSyncLoss));

            IdentificationResult result = IdentificationResult.tentative(
                trial.bestTentativeDecoder, totalElapsed,
                trial.bestTentativeCrcFails, trial.bestTentativeSyncLoss, samples);

            if(mCallback != null)
            {
                mCallback.onTrialComplete(signal, result, true);
            }
        }
        else
        {
            // Truly no match — no decoder showed any activity
            mLog.info("NO MATCH: {} MHz — all {} candidates exhausted, no protocol activity detected",
                String.format("%.4f", trial.signal.getFrequencyMHz()), trial.candidates.size());

            IdentificationResult result = IdentificationResult.noMatch(
                trial.currentDecoder != null ? trial.currentDecoder : DecoderType.P25_PHASE1, totalElapsed);
            if(mCallback != null)
            {
                mCallback.onTrialComplete(signal, result, true);
            }
        }
    }

    /**
     * Stop all active trial channels.
     */
    private void stopAllTrials()
    {
        for(TrialState trial : mActiveTrials.values())
        {
            stopTrialChannel(trial);
        }
        mActiveTrials.clear();
    }

    private DecodeConfiguration createDecodeConfiguration(DecoderType decoderType)
    {
        switch(decoderType)
        {
            case P25_PHASE1: return new DecodeConfigP25Phase1();
            case P25_PHASE2: return new DecodeConfigP25Phase2();
            case DMR: return new DecodeConfigDMR();
            case NBFM: return new DecodeConfigNBFM();
            case AM: return new DecodeConfigAM();
            case LTR: return new DecodeConfigLTRStandard();
            case LTR_NET: return new DecodeConfigLTRNet();
            case MPT1327: return new DecodeConfigMPT1327();
            case PASSPORT: return new DecodeConfigPassport();
            default:
                mLog.warn("No decode config for: {}", decoderType);
                return null;
        }
    }

    /**
     * Robust trial counter with CRC validation and NAC majority voting.
     *
     * - Only counts CRC-valid messages (isValid()==true) as confirmed decodes
     * - Tracks CRC-failed messages separately (noise indicator)
     * - Collects all observed NAC values and does majority voting
     * - Filters out SyncLossMessage entirely (decoder sync errors)
     */
    private static class TrialCounter
    {
        private final AtomicInteger mValidMessageCount = new AtomicInteger(0);
        private final AtomicInteger mInvalidMessageCount = new AtomicInteger(0);
        private final AtomicInteger mDecodeEventCount = new AtomicInteger(0);
        private final AtomicInteger mSyncLossCount = new AtomicInteger(0);
        private final CopyOnWriteArrayList<String> mSampleMessages = new CopyOnWriteArrayList<>();
        /** NAC → observation count for majority voting (P25) */
        private final ConcurrentHashMap<String, AtomicInteger> mNacCounts = new ConcurrentHashMap<>();
        /** Color Code → observation count for majority voting (DMR) */
        private final ConcurrentHashMap<Integer, AtomicInteger> mColorCodeCounts = new ConcurrentHashMap<>();
        private static final int MAX_SAMPLES = 15;

        private final Listener<IMessage> mMessageListener = message -> {
            // Skip SyncLossMessage — decoder sync error, not a real frame
            if(message instanceof SyncLossMessage)
            {
                mSyncLossCount.incrementAndGet();
                return;
            }

            // CRC validation — only count valid messages as confirmed decodes
            if(!message.isValid())
            {
                mInvalidMessageCount.incrementAndGet();

                // Still log CRC failures in samples for diagnostics
                if(mSampleMessages.size() < MAX_SAMPLES)
                {
                    mSampleMessages.add("[CRC-FAIL] " + message.getClass().getSimpleName());
                }
                return;
            }

            // Valid message — count it
            mValidMessageCount.incrementAndGet();

            // Extract and tally NAC from P25 Phase 1 messages
            if(message instanceof P25P1Message p25msg)
            {
                try
                {
                    if(p25msg.getNAC() != null)
                    {
                        String nac = p25msg.getNAC().toString();
                        mNacCounts.computeIfAbsent(nac, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                }
                catch(Exception ignored) {}
            }

            // Extract and tally Color Code from DMR data messages
            if(message instanceof DataMessage dmrMsg)
            {
                try
                {
                    int cc = dmrMsg.getSlotType().getColorCode();
                    mColorCodeCounts.computeIfAbsent(cc, k -> new AtomicInteger(0)).incrementAndGet();
                }
                catch(Exception ignored) {}
            }

            if(mSampleMessages.size() < MAX_SAMPLES)
            {
                StringBuilder sb = new StringBuilder();
                if(message.getProtocol() != null)
                {
                    sb.append(message.getProtocol()).append(": ");
                }
                sb.append(message.getClass().getSimpleName());

                // Add NAC for P25 messages
                if(message instanceof P25P1Message p25msg && p25msg.getNAC() != null)
                {
                    sb.append(" [NAC:").append(p25msg.getNAC()).append("]");
                }

                // Add Color Code for DMR data messages
                if(message instanceof DataMessage dmrMsg)
                {
                    try { sb.append(" [CC:").append(dmrMsg.getSlotType().getColorCode()).append("]"); }
                    catch(Exception ignored) {}
                }

                mSampleMessages.add(sb.toString());
            }
        };

        private final Listener<IDecodeEvent> mDecodeEventListener = event -> {
            mDecodeEventCount.incrementAndGet();
            if(mSampleMessages.size() < MAX_SAMPLES)
            {
                try
                {
                    StringBuilder sb = new StringBuilder();
                    if(event.getProtocol() != null) sb.append(event.getProtocol());
                    if(event.getDetails() != null)
                    {
                        if(sb.length() > 0) sb.append(": ");
                        sb.append(event.getDetails());
                    }
                    if(sb.length() > 0)
                    {
                        mSampleMessages.add("[EVENT] " + sb);
                    }
                }
                catch(Exception ignored) {}
            }
        };

        public Listener<IMessage> getMessageListener() { return mMessageListener; }
        public Listener<IDecodeEvent> getDecodeEventListener() { return mDecodeEventListener; }

        /** Valid messages + decode events (CRC-valid only, SyncLoss excluded) */
        public int getEventCount() { return mValidMessageCount.get() + mDecodeEventCount.get(); }
        /** CRC-valid message count only */
        public int getMessageCount() { return mValidMessageCount.get(); }
        /** CRC-failed message count */
        public int getInvalidMessageCount() { return mInvalidMessageCount.get(); }
        public int getDecodeEventCount() { return mDecodeEventCount.get(); }
        public int getSyncLossCount() { return mSyncLossCount.get(); }
        public List<String> getSampleMessages() { return new ArrayList<>(mSampleMessages); }

        /**
         * Get the dominant NAC via majority voting.
         * Returns null if no NACs observed, or if the dominant NAC doesn't meet
         * the consistency threshold (MIN_NAC_OBSERVATIONS and NAC_CONSISTENCY_RATIO).
         */
        public String getDetectedNAC()
        {
            if(mNacCounts.isEmpty()) return null;

            // Find the NAC with the most observations
            String bestNac = null;
            int bestCount = 0;
            int totalNacObs = 0;

            for(Map.Entry<String, AtomicInteger> entry : mNacCounts.entrySet())
            {
                int count = entry.getValue().get();
                totalNacObs += count;
                if(count > bestCount)
                {
                    bestCount = count;
                    bestNac = entry.getKey();
                }
            }

            // Require minimum observations
            if(bestCount < MIN_NAC_OBSERVATIONS) return null;

            // Require consistency ratio (dominant NAC must be >= 75% of all NAC observations)
            if(totalNacObs > 0 && (double) bestCount / totalNacObs < NAC_CONSISTENCY_RATIO) return null;

            return bestNac;
        }

        /**
         * Get the dominant DMR Color Code via majority voting.
         * Returns null if no Color Codes observed. Uses same consistency logic as NAC.
         */
        public Integer getDetectedColorCode()
        {
            if(mColorCodeCounts.isEmpty()) return null;

            Integer bestCC = null;
            int bestCount = 0;
            int totalObs = 0;

            for(Map.Entry<Integer, AtomicInteger> entry : mColorCodeCounts.entrySet())
            {
                int count = entry.getValue().get();
                totalObs += count;
                if(count > bestCount)
                {
                    bestCount = count;
                    bestCC = entry.getKey();
                }
            }

            // Even 1 observation is useful for CC (it's embedded in every frame)
            return bestCC;
        }

        /**
         * Build a protocol-specific system info string from all extracted identifiers.
         * Returns null if no identifiers were found.
         */
        public String buildSystemInfo(DecoderType decoder)
        {
            StringBuilder sb = new StringBuilder();

            // P25 NAC
            String nac = getDetectedNAC();
            if(nac != null)
            {
                sb.append("NAC:").append(nac);
            }
            else if(!mNacCounts.isEmpty())
            {
                // NAC observed but didn't meet majority threshold — show best guess
                String bestNac = null;
                int bestCount = 0;
                for(Map.Entry<String, AtomicInteger> entry : mNacCounts.entrySet())
                {
                    if(entry.getValue().get() > bestCount)
                    {
                        bestCount = entry.getValue().get();
                        bestNac = entry.getKey();
                    }
                }
                if(bestNac != null)
                {
                    sb.append("NAC:").append(bestNac).append("(").append(bestCount).append("x)");
                }
            }

            // DMR Color Code
            Integer cc = getDetectedColorCode();
            if(cc != null)
            {
                if(sb.length() > 0) sb.append(" ");
                sb.append("CC:").append(cc);
            }

            return sb.length() > 0 ? sb.toString() : null;
        }

        /**
         * Get NAC statistics summary for logging.
         * Shows all observed NACs and their counts, plus consistency assessment.
         */
        public String getNacSummary()
        {
            if(mNacCounts.isEmpty()) return "no NACs observed";

            StringBuilder sb = new StringBuilder();
            int totalObs = 0;
            String bestNac = null;
            int bestCount = 0;

            for(Map.Entry<String, AtomicInteger> entry : mNacCounts.entrySet())
            {
                int count = entry.getValue().get();
                totalObs += count;
                if(count > bestCount) { bestCount = count; bestNac = entry.getKey(); }
                if(sb.length() > 0) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(count);
            }

            String consistency;
            if(mNacCounts.size() == 1 && bestCount >= MIN_NAC_OBSERVATIONS)
            {
                consistency = "CONSISTENT";
            }
            else if(bestCount >= MIN_NAC_OBSERVATIONS && totalObs > 0 &&
                    (double) bestCount / totalObs >= NAC_CONSISTENCY_RATIO)
            {
                consistency = "DOMINANT(" + String.format("%.0f%%", 100.0 * bestCount / totalObs) + ")";
            }
            else if(bestCount < MIN_NAC_OBSERVATIONS)
            {
                consistency = "INSUFFICIENT(" + bestCount + "/" + MIN_NAC_OBSERVATIONS + ")";
            }
            else
            {
                consistency = "INCONSISTENT";
            }

            return "NACs{" + sb + "} " + consistency;
        }
    }
}
