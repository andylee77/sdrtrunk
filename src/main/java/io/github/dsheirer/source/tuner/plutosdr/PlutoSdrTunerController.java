/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.source.tuner.plutosdr;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.SignedShortNativeBuffer;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.IBandwidthAdjustableTunerController;
import org.slf4j.Logger;
import java.util.Arrays;
import java.util.List;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tuner controller for a PlutoSDR accessed via a companion TCP IQ-stream server.
 *
 * <h3>Protocol</h3>
 * <ol>
 *   <li>Client connects to the server TCP socket.</li>
 *   <li>Client sends a single UTF-8 JSON command line terminated with '\n' that configures the radio:
 *       <pre>{"freq":101100000,"sample_rate":2500000,"gain":30,"agc":false,"rf_bandwidth":0}\n</pre>
 *       {@code rf_bandwidth} = 0 means "auto" (driver default, ~0.75 × sample_rate).
 *   </li>
 *   <li>Server responds with a single UTF-8 JSON status line terminated with '\n':
 *       <pre>{"status":"ok","sample_rate":2500000,"rf_bandwidth":1875000,"hw_model":"...","hw_serial":"...","fw_version":"...","temperature":42.5}</pre>
 *       or an error:
 *       <pre>{"status":"error","message":"..."}</pre>
 *   </li>
 *   <li>Server then streams raw interleaved signed 16-bit little-endian IQ samples continuously
 *       until the socket is closed.</li>
 *   <li>To retune, the client closes the socket and reconnects with a new command.</li>
 * </ol>
 *
 * <h3>Device info polling</h3>
 * After connecting, the controller periodically sends a separate short-lived TCP connection
 * with command {@code {"command":"status"}} to retrieve live temperature and RSSI from the server.
 * The server responds with a JSON object and closes the connection.
 *
 * <h3>Companion server</h3>
 * See {@code docs/plutosdr/pluto_server.py} for the reference Python server implementation.
 */
public class PlutoSdrTunerController extends TunerController implements IBandwidthAdjustableTunerController
{
    private static final Logger mLog = LoggerFactory.getLogger(PlutoSdrTunerController.class);

    /**
     * Fragment size used by SignedShortNativeBuffer – each fragment is 2048 complex samples.
     * The read buffer length must be an exact multiple of this value.
     */
    private static final int FRAGMENT_SIZE = 2048;

    /**
     * Fixed number of complex samples per TCP read buffer.
     *
     * <p>This must match the server's {@code IIO_BUFFER_SIZE} (65 536 complex samples per
     * {@code sdr.rx()} call in {@code pluto_server.py}).  The server always sends exactly
     * {@code IIO_BUFFER_SIZE × 4} bytes per {@code sendall()} call.  If the client reads
     * a different amount, the TCP socket buffer acts as a reservoir and the stream thread
     * drains it at memory speed rather than at the hardware sample rate — causing the
     * channelizer to receive data far faster than real-time and flooding the pipeline.</p>
     *
     * <p>By reading exactly one server IIO buffer per TCP read, the client naturally
     * rate-limits itself to the hardware sample rate: each read blocks until the server
     * has produced the next IIO buffer (~8 ms at 8 MHz, ~26 ms at 2.5 MHz).</p>
     *
     * <p>65 536 is also a multiple of {@link #FRAGMENT_SIZE} (2048), so
     * {@link io.github.dsheirer.buffer.SignedShortNativeBuffer} never throws.</p>
     *
     * At any sample rate: 65 536 samples = 32 fragments of 2048 samples each.
     */
    private static final int SAMPLES_PER_BUFFER = 65_536;

    /** Each complex sample = 2 shorts × 2 bytes = 4 bytes */
    private static final int BYTES_PER_SAMPLE = 4;

    /** DC half-bandwidth to exclude (PlutoSDR has a small DC spike) */
    public static final int DC_HALF_BANDWIDTH = 5_000;
    /** Usable bandwidth fraction */
    public static final double USABLE_BANDWIDTH = 0.90;

    private static final int SOCKET_CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_READ_TIMEOUT_MS    = 10_000;

    /**
     * How often to poll the server for live device status (temperature, RSSI).
     * Increased from 10s to 30s to reduce the number of short-lived TCP connections
     * visible in the Python server logs.  Each poll opens a new connection, so a
     * lower interval creates unnecessary noise on both sides.
     */
    private static final int STATUS_POLL_INTERVAL_SECONDS = 30;

    private final Gson mGson = new Gson();

    /**
     * Lock protecting all socket/stream fields (mSocket, mInputStream, mOutputStream).
     * Using a dedicated lock (separate from the TunerController's getLock()) avoids
     * deadlocks between the stream thread and the UI/channel-allocation threads.
     */
    private final ReentrantLock mSocketLock = new ReentrantLock();

    // Per-sample-rate buffer sizing (recomputed whenever the sample rate changes)
    private int mSamplesPerBuffer;
    private int mBufferBytes;

    // Connection state
    private String mHost;
    private int mPort;
    private Socket mSocket;
    private DataInputStream mInputStream;
    private OutputStream mOutputStream;

    /**
     * The frequency (PPM-corrected) that the hardware is actually tuned to.
     * Updated only when a TCP reconnect succeeds.  Used by the debounced retune
     * to compare against the target frequency — NOT mFrequencyController.getTunedFrequency()
     * which is updated by FrequencyController.setFrequency() before the hardware moves.
     */
    private volatile long mHardwareFrequency = 0;

    // Radio state
    private int mSampleRate;
    private int mRfGain;
    private boolean mAgcEnabled;
    private int mRfBandwidth;   // 0 = auto

    // Device info received from the server (updated on connect + periodic poll)
    private final AtomicReference<PlutoSdrDeviceInfo> mDeviceInfo = new AtomicReference<>(new PlutoSdrDeviceInfo());

    // DC offset correction state (separate managers for I and Q channels)
    private float mIAverageDc = 0.0f;
    private float mQAverageDc = 0.0f;
    private int mDcCalculationsRemaining = 5;  // initial coarse correction
    private long mLastDcCalculationTimestamp = 0;
    private static final long DC_PROCESSING_INTERVAL_MS = 60_000L;  // recalculate every 60 s
    private static final int DC_CALCULATIONS_PER_INTERVAL = 5;
    private static final float DC_FILTER_GAIN = 0.05f;
    private static final float TARGET_DC_OFFSET_REMAINING = 0.0002f;

    /**
     * When true, {@link #setTunedFrequency(long)} will not reconnect to the server even if the
     * requested frequency differs from the current tuned frequency.  This prevents the
     * PolyphaseChannelManager from retuning the centre frequency when channels are activated,
     * which would cause the waterfall overlay to shift and decoders to lose sync.
     *
     * <p>Set via {@link #setFrequencyLocked(boolean)} from the tuner editor checkbox.</p>
     */
    private volatile boolean mFrequencyLocked = false;

    /**
     * Debounce delay for retune requests (milliseconds).
     *
     * <p>When multiple channels auto-start in rapid succession, the PolyphaseChannelSourceManager
     * calls {@link #setTunedFrequency(long)} once per channel as it recalculates the optimal
     * centre frequency for the growing channel set.  Without debouncing, each call triggers a
     * full TCP reconnect — closing the socket, reopening it, and resetting the PolyphaseChannelManager's
     * ChannelCalculator — before the next channel has even been allocated.  The result is that
     * channels allocated against earlier centre frequencies end up pointing at wrong DDC offsets.</p>
     *
     * <p>By deferring the actual reconnect by {@value} ms, all rapid-fire retune requests within
     * that window are collapsed into a single reconnect at the final optimal centre frequency.
     * The PolyphaseChannelManager then allocates all channels against the correct, stable centre.</p>
     */
    private static final long RETUNE_DEBOUNCE_MS = 250;

    /**
     * The target frequency for the pending debounced retune, or 0 if no retune is pending.
     * Written by {@link #setTunedFrequency(long)} and read by the debounce executor.
     */
    private volatile long mPendingRetuneFrequency = 0;

    /**
     * The target nominal (non-PPM-corrected) frequency for the pending debounced setFrequency() call.
     * Written by {@link #setFrequency(long)} and read by the debounce executor.
     */
    private volatile long mPendingNominalFrequency = 0;

    /**
     * Scheduled future for the pending debounced retune.  Cancelled and replaced whenever
     * a new {@link #setTunedFrequency(long)} call arrives before the delay expires.
     */
    private ScheduledFuture<?> mRetuneFuture;

    /**
     * Single-thread executor used to schedule the debounced retune.
     * Separate from the status-poll executor so the two don't interfere.
     */
    private ScheduledExecutorService mRetuneExecutor;

    // Streaming thread
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private Thread mStreamThread;

    /** How many buffers between stream-stats log lines (logged at INFO level). */
    private static final int STREAM_STATS_LOG_INTERVAL_BUFFERS = 200;

    // Periodic status polling
    private ScheduledExecutorService mStatusPollExecutor;
    private ScheduledFuture<?> mStatusPollFuture;

    /**
     * Constructs an instance.
     *
     * @param host              hostname or IP address of the companion server
     * @param port              TCP port of the companion server
     * @param tunerErrorListener listener to receive fatal errors from this controller
     */
    public PlutoSdrTunerController(String host, int port, ITunerErrorListener tunerErrorListener)
    {
        super(tunerErrorListener);
        mHost = host;
        mPort = port;

        setMinimumFrequency(PlutoSdrTunerConfiguration.MINIMUM_FREQUENCY_HZ);
        setMaximumFrequency(PlutoSdrTunerConfiguration.MAXIMUM_FREQUENCY_HZ);
        setMiddleUnusableHalfBandwidth(DC_HALF_BANDWIDTH);
        setUsableBandwidthPercentage(USABLE_BANDWIDTH);

        // Initialise buffer sizing for the default sample rate (will be recomputed in applyConfiguration)
        updateBufferSizing(PlutoSdrTunerConfiguration.DEFAULT_SAMPLE_RATE);
    }

    // =========================================================================
    // Buffer sizing helpers
    // =========================================================================

    /**
     * Recomputes and caches {@link #mSamplesPerBuffer} and {@link #mBufferBytes}.
     *
     * <p>The buffer size is fixed at {@link #SAMPLES_PER_BUFFER} regardless of sample rate,
     * so that each TCP read consumes exactly one server IIO buffer and the stream thread
     * naturally rate-limits to the hardware sample rate.</p>
     *
     * @param sampleRate new sample rate in Hz (used only for logging the buffer duration)
     */
    private void updateBufferSizing(int sampleRate)
    {
        mSamplesPerBuffer = SAMPLES_PER_BUFFER;
        mBufferBytes      = mSamplesPerBuffer * BYTES_PER_SAMPLE;
        mLog.info("PlutoSDR buffer sizing: sampleRate={} Hz, samplesPerBuffer={} ({} ms), bufferBytes={}",
                sampleRate, mSamplesPerBuffer,
                String.format("%.1f", (double) mSamplesPerBuffer / sampleRate * 1000.0),
                mBufferBytes);
    }

    // =========================================================================
    // TunerController lifecycle
    // =========================================================================

    @Override
    public void start() throws SourceException
    {
        if(mRunning.get())
        {
            return;
        }

        try
        {
            connect();
        }
        catch(IOException e)
        {
            throw new SourceException("PlutoSDR - unable to connect to server at " +
                    mHost + ":" + mPort + " - " + e.getMessage(), e);
        }

        mRunning.set(true);
        mStreamThread = new Thread(this::streamLoop, "PlutoSDR-Stream-" + mHost + ":" + mPort);
        mStreamThread.setDaemon(true);
        mStreamThread.start();

        // Start the debounce executor for retune requests
        mRetuneExecutor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "PlutoSDR-Retune-" + mHost + ":" + mPort);
            t.setDaemon(true);
            return t;
        });

        // Start periodic status polling
        //startStatusPolling();

        //mLog.info("PlutoSDR controller started - connected to {}:{}", mHost, mPort);
    }

    @Override
    public void stop()
    {
        mRunning.set(false);

        // Cancel any pending debounced retune
        if(mRetuneFuture != null)
        {
            mRetuneFuture.cancel(false);
            mRetuneFuture = null;
        }
        if(mRetuneExecutor != null)
        {
            mRetuneExecutor.shutdownNow();
            mRetuneExecutor = null;
        }

      //  stopStatusPolling();

        if(mStreamThread != null)
        {
            mStreamThread.interrupt();
            mStreamThread = null;
        }

        disconnect();
        dispose();
        mLog.info("PlutoSDR controller stopped");
    }

    // =========================================================================
    // TunerController abstract implementations
    // =========================================================================

    @Override
    public TunerType getTunerType()
    {
        return TunerType.PLUTO_SDR;
    }

    @Override
    public int getBufferSampleCount()
    {
        return mSamplesPerBuffer;
    }

    @Override
    public long getTunedFrequency() throws SourceException
    {
        return mFrequencyController.getTunedFrequency();
    }

    /**
     * Overrides the base-class setFrequency() to immediately update the FrequencyController
     * (so the ChannelCalculator gets the new centre frequency for channel allocation) while
     * deferring the actual hardware TCP reconnect via a debounce timer.
     *
     * <h3>Why we need both an immediate FrequencyController update AND a debounced reconnect</h3>
     *
     * <p>When multiple channels auto-start in rapid succession, the PolyphaseChannelSourceManager
     * calls setFrequency() once per channel as it recalculates the optimal centre frequency.
     * After each setFrequency() call it immediately calls PolyphaseChannelManager.getChannel()
     * to allocate the channel.  getChannel() calls ChannelCalculator.getChannelIndexes() which
     * uses the ChannelCalculator's centre frequency to compute DDC bin offsets.</p>
     *
     * <p>The ChannelCalculator is updated by the PolyphaseChannelManager when it receives a
     * NOTIFICATION_FREQUENCY_CHANGE source event from the FrequencyController.  If we defer
     * the FrequencyController update until after the hardware reconnect (250 ms later), the
     * ChannelCalculator still has the OLD centre frequency when getChannel() is called, causing
     * "Requested channel exceeds current channelizer frequency range" errors and the channel
     * allocation fails — the second tuner never gets any channels assigned to it.</p>
     *
     * <p>Fix: call super.setFrequency() immediately so the FrequencyController broadcasts
     * NOTIFICATION_FREQUENCY_CHANGE right away, updating the ChannelCalculator before
     * getChannel() is called.  The actual hardware reconnect is still debounced so that
     * rapid-fire retune requests collapse into a single TCP reconnect.</p>
     *
     * <p>The debounce window (250 ms) collapses all rapid-fire setFrequency() calls from
     * multiple channels auto-starting into a single hardware reconnect.</p>
     */
    @Override
    public void setFrequency(long frequency) throws SourceException
    {
        // If frequency lock is enabled, skip entirely (PolyphaseChannelSourceManager already
        // checks this via isFrequencyLocked(), but guard here too for safety).
        if(mFrequencyLocked)
        {
            mLog.debug("PlutoSDR - setFrequency({}) skipped (frequency locked)", frequency);
            return;
        }

        // If the hardware is already at this nominal frequency, nothing to do.
        // Convert nominal to tuned (PPM-corrected) for comparison.
        long tunedTarget = (long)((double)frequency / (1.0 + (mFrequencyController.getFrequencyCorrection() / 1_000_000.0)));
        if(tunedTarget == mHardwareFrequency)
        {
            mLog.debug("PlutoSDR - setFrequency({}) skipped (hardware already at tuned={})", frequency, tunedTarget);
            return;
        }

        mLog.info("PlutoSDR - setFrequency({}) → debouncing retune to tuned={} Hz (hwFreq={})",
                frequency, tunedTarget, mHardwareFrequency);

        if(!mRunning.get())
        {
            // Not running yet — use the base-class implementation directly
            super.setFrequency(frequency);
            return;
        }

        // IMMEDIATELY update the FrequencyController so that:
        //   1. The ChannelCalculator (in PolyphaseChannelManager) gets the new centre frequency
        //      via the NOTIFICATION_FREQUENCY_CHANGE event BEFORE getChannel() is called.
        //   2. Channel allocation in PolyphaseChannelSourceManager.getSource() succeeds because
        //      the ChannelCalculator knows the correct centre frequency.
        // Without this, the ChannelCalculator still has the old centre frequency when
        // getChannel() is called immediately after setFrequency(), causing
        // "Requested channel exceeds current channelizer frequency range" errors.
        try
        {
            super.setFrequency(frequency);
        }
        catch(SourceException se)
        {
            mLog.warn("PlutoSDR - could not update FrequencyController immediately: {}", se.getMessage());
        }

        // Debounce: store the target nominal frequency and schedule a deferred hardware reconnect.
        // All rapid-fire setFrequency() calls within the debounce window collapse into a single
        // TCP reconnect at the final optimal centre frequency.
        mPendingNominalFrequency = frequency;

        ScheduledExecutorService executor = mRetuneExecutor;
        if(executor != null && !executor.isShutdown())
        {
            if(mRetuneFuture != null && !mRetuneFuture.isDone())
            {
                mRetuneFuture.cancel(false);
            }

            mRetuneFuture = executor.schedule(() ->
            {
                long nominalFreq = mPendingNominalFrequency;
                // Clear the pending flag BEFORE the reconnect so that setTunedFrequency()
                // can do direct reconnects again once the debounce window has closed.
                mPendingNominalFrequency = 0;

                if(nominalFreq == 0 || !mRunning.get())
                {
                    return;
                }
                // Recompute the PPM-corrected tuned frequency
                long tunedFreq = (long)((double)nominalFreq /
                        (1.0 + (mFrequencyController.getFrequencyCorrection() / 1_000_000.0)));
                if(tunedFreq == mHardwareFrequency)
                {
                    mLog.debug("PlutoSDR - debounced retune to {} Hz skipped (hardware already there)", tunedFreq);
                    return;
                }
                mLog.info("PlutoSDR - debounced retune: hardware {} Hz → {} Hz (nominal {} Hz)",
                        mHardwareFrequency, tunedFreq, nominalFreq);
                try
                {
                    // Reconnect the hardware to the new frequency.
                    // The FrequencyController was already updated above (immediately), so we
                    // do NOT call super.setFrequency() again here — that would double-apply
                    // PPM correction and shift the spectrum display a second time.
                    reconnect(tunedFreq, mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
                }
                catch(IOException e)
                {
                    mLog.error("PlutoSDR - debounced retune failed: {}", e.getMessage());
                }
            }, RETUNE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
        else
        {
            // Executor not available — hardware reconnect already handled by setTunedFrequency()
            // which is called by super.setFrequency() above via FrequencyController.
        }
    }

    @Override
    public void setTunedFrequency(long frequency) throws SourceException
    {
        // setTunedFrequency() is called by FrequencyController after it updates mTunedFrequency.
        //
        // With the new setFrequency() override, super.setFrequency() is called IMMEDIATELY
        // (to update the ChannelCalculator), which means FrequencyController calls
        // setTunedFrequency() right away — before the debounced hardware reconnect fires.
        //
        // We must NOT do a hardware reconnect here when a debounced retune is already pending,
        // because:
        //   1. The debounce task will do the reconnect at the correct final frequency.
        //   2. Reconnecting here would cause an extra TCP disconnect/reconnect cycle for every
        //      intermediate frequency during rapid channel auto-start.
        //   3. The stream thread would see "Socket closed" and enter its reconnect loop,
        //      racing with the debounce task.
        //
        // Detection: if mPendingNominalFrequency is non-zero, a debounced retune is in flight.
        // In that case, skip the hardware reconnect here — the debounce task handles it.
        if(mFrequencyLocked)
        {
            mLog.debug("PlutoSDR - setTunedFrequency({}) skipped (frequency locked)", frequency);
            return;
        }

        if(mRunning.get())
        {
            if(frequency == mHardwareFrequency)
            {
                mLog.debug("PlutoSDR - setTunedFrequency({}) skipped (hardware already at this frequency)", frequency);
                return;
            }

            // Guard against the double-reconnect that occurs when setFrequencyCorrection() triggers
            // FrequencyController.setFrequencyCorrection() → setFrequency(nominal, true) →
            // setTunedFrequency(ppmCorrected) [first reconnect, correct] AND THEN
            // broadcastFrequencyChange() causes a second setTunedFrequency() call with the
            // NOMINAL (uncorrected) frequency.
            //
            // If the PPM-corrected version of the incoming nominal frequency equals mHardwareFrequency,
            // the hardware is already at the right place — skip the reconnect.
            double correction = mFrequencyController.getFrequencyCorrection();
            if(correction != 0.0)
            {
                long ppmCorrectedVersion = (long)((double)frequency / (1.0 + (correction / 1_000_000.0)));
                if(ppmCorrectedVersion == mHardwareFrequency)
                {
                    mLog.debug("PlutoSDR - setTunedFrequency({}) skipped (PPM-corrected {} == mHardwareFrequency {})",
                            frequency, ppmCorrectedVersion, mHardwareFrequency);
                    return;
                }
            }

            // If a debounced retune is pending (mPendingNominalFrequency != 0), skip the
            // immediate hardware reconnect — the debounce task will handle it.
            if(mPendingNominalFrequency != 0)
            {
                mLog.debug("PlutoSDR - setTunedFrequency({}) skipped (debounced retune pending to nominal={})",
                        frequency, mPendingNominalFrequency);
                return;
            }

            // No debounced retune pending — this is a direct retune call (e.g. from the UI
            // or from a path that doesn't go through setFrequency()).  Reconnect immediately.
            mLog.info("PlutoSDR - setTunedFrequency: hardware {} Hz → {} Hz (delta={} Hz)",
                    mHardwareFrequency, frequency, (frequency - mHardwareFrequency));
            try
            {
                reconnect(frequency, mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
            }
            catch(IOException e)
            {
                throw new SourceException("PlutoSDR - error retuning to " + frequency + " Hz: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Overrides the base-class setFrequencyCorrection() to:
     * <ol>
     *   <li>Reset the auto-PPM cooldown timer so the correction manager starts observing the
     *       new error level immediately (rather than waiting out the 30-second post-correction
     *       cooldown that was set by the previous auto-correction).</li>
     *   <li>Log the full Hz-precision frequency that will be sent to the server after the
     *       PPM-corrected reconnect, so the operator can verify the correction is being applied.</li>
     * </ol>
     *
     * <p>The actual hardware reconnect is handled by the base-class chain:
     * {@code setFrequencyCorrection()} → {@code FrequencyController.setFrequencyCorrection()}
     * → {@code FrequencyController.setFrequency()} → {@code setTunedFrequency()} → {@code reconnect()}.</p>
     */
    @Override
    public void setFrequencyCorrection(double correction) throws SourceException
    {
        double oldCorrection = mFrequencyController.getFrequencyCorrection();
        long nominalFreq = mFrequencyController.getFrequency();
        long newTunedFreq = (long)((double)nominalFreq / (1.0 + (correction / 1_000_000.0)));
        long oldTunedFreq = mHardwareFrequency;

        mLog.info("PlutoSDR - PPM correction change: {} → {} ppm | nominal={} Hz | tuned: {} Hz → {} Hz (delta={} Hz)",
                String.format("%.4f", oldCorrection),
                String.format("%.4f", correction),
                nominalFreq,
                oldTunedFreq,
                newTunedFreq,
                (newTunedFreq - oldTunedFreq));

        // Reset the auto-PPM cooldown so the correction manager can start observing the new
        // error level immediately after this manual (or auto) PPM change.
        getFrequencyErrorCorrectionManager().resetCooldown();

        // Delegate to the base class which updates FrequencyController and triggers the
        // hardware reconnect via setTunedFrequency().
        super.setFrequencyCorrection(correction);
    }

    @Override
    public double getCurrentSampleRate() throws SourceException
    {
        return mSampleRate;
    }

    /**
     * Applies the PlutoSDR-specific configuration fields (host, port, sample rate, gain, AGC,
     * RF bandwidth) to this controller's internal state WITHOUT connecting to the server or retuning.
     *
     * <p>This method is intended to be called by {@link DiscoveredPlutoSdrTuner#start()} BEFORE
     * {@link #start()} is invoked, so that the initial {@link #connect()} call uses the correct
     * frequency, sample rate and gain values rather than the zero/default values that would
     * otherwise be sent to the server.</p>
     *
     * @param config the PlutoSDR tuner configuration to apply
     * @throws SourceException if the configuration type is wrong or the frequency is invalid
     */
    public void applyConfiguration(PlutoSdrTunerConfiguration config) throws SourceException
    {
        if(config == null)
        {
            throw new IllegalArgumentException("PlutoSDR configuration must not be null");
        }

        mHost        = config.getHost();
        mPort        = config.getPort();
        mSampleRate  = config.getSampleRate();
        mRfGain      = config.getRfGain();
        mAgcEnabled  = config.isAgcEnabled();
        mRfBandwidth = config.getRfBandwidth();

        // Recompute buffer sizing for the configured sample rate BEFORE connecting.
        updateBufferSizing(mSampleRate);

        // Seed the frequency controller with the persisted frequency so that
        // the initial connect() sends the correct LO frequency to the server.
        mFrequencyController.setSampleRate(mSampleRate);

        // Apply base-class fields: frequency, frequency correction, auto-PPM.
        // We call super.apply() here so that the frequency controller is fully
        // initialised before start() opens the TCP connection.
        super.apply(config);
    }

    @Override
    public void apply(TunerConfiguration config) throws SourceException
    {
        // Let the base class handle frequency, frequency correction, and auto-PPM
        super.apply(config);

        if(config instanceof PlutoSdrTunerConfiguration plutoConfig)
        {
            mHost        = plutoConfig.getHost();
            mPort        = plutoConfig.getPort();
            mSampleRate  = plutoConfig.getSampleRate();
            mRfGain      = plutoConfig.getRfGain();
            mAgcEnabled  = plutoConfig.isAgcEnabled();
            mRfBandwidth = plutoConfig.getRfBandwidth();

            // Recompute buffer sizing whenever the sample rate changes via apply()
            updateBufferSizing(mSampleRate);
            mFrequencyController.setSampleRate(mSampleRate);

            // If already running, reconnect with the new settings.
            // Use getTunedFrequency() (PPM-corrected) so we don't revert PPM correction.
            if(mRunning.get())
            {
                try
                {
                    reconnect(mFrequencyController.getTunedFrequency(), mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
                }
                catch(IOException e)
                {
                    throw new SourceException("PlutoSDR - error applying configuration: " + e.getMessage(), e);
                }
            }
        }
        else
        {
            throw new IllegalArgumentException("Invalid tuner configuration type [" + config.getClass() + "]");
        }
    }

    // =========================================================================
    // Network helpers
    // =========================================================================

    /**
     * Opens a TCP connection to the companion server and sends the initial configuration command.
     *
     * <p>Uses the PPM-corrected tuned frequency ({@code getTunedFrequency()}) rather than the
     * nominal frequency ({@code getFrequency()}) so that background reconnects (e.g. from
     * {@link #attemptReconnect()}) preserve the current PPM correction.  Previously this used
     * {@code getFrequency()} which sent the uncorrected nominal frequency to the server,
     * effectively reverting any PPM correction every time the stream thread reconnected.</p>
     */
    private void connect() throws IOException
    {
        connect(mFrequencyController.getTunedFrequency(), mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
    }

    /**
     * Opens a TCP connection and sends a configuration command with the given parameters.
     */
    private void connect(long frequency, int sampleRate, int gain, boolean agc, int rfBandwidth) throws IOException
    {
        mSocket = new Socket();
        // Constrain the TCP receive buffer to exactly ONE IIO buffer worth of data.
        // This creates backpressure that prevents the server from sending ahead of
        // real-time.  Without this, the OS TCP receive buffer (typically 256 KB–4 MB)
        // allows the server to pre-fill many buffers, causing the stream thread to
        // drain them at memory speed rather than at the hardware sample rate.
        // One buffer (262 144 bytes) means the server can only have one buffer in
        // flight at a time, so the Java read() naturally blocks until the server's
        // next sdr.rx() call completes — pacing the stream to the hardware rate.
        mSocket.setReceiveBufferSize(SAMPLES_PER_BUFFER * BYTES_PER_SAMPLE);
        mSocket.connect(new InetSocketAddress(mHost, mPort), SOCKET_CONNECT_TIMEOUT_MS);
        mSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        mSocket.setTcpNoDelay(true);

        mInputStream  = new DataInputStream(mSocket.getInputStream());
        mOutputStream = mSocket.getOutputStream();

        // Build and send the JSON configuration command
        JsonObject cmd = new JsonObject();
        cmd.addProperty("freq",         frequency);
        cmd.addProperty("sample_rate",  sampleRate);
        cmd.addProperty("gain",         gain);
        cmd.addProperty("agc",          agc);
        cmd.addProperty("rf_bandwidth", rfBandwidth);  // 0 = auto
        String cmdLine = mGson.toJson(cmd) + "\n";
        mOutputStream.write(cmdLine.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mOutputStream.flush();

        // Read the server's response line (terminated by '\n')
        StringBuilder sb = new StringBuilder();
        int b;
        while((b = mInputStream.read()) != -1 && b != '\n')
        {
            sb.append((char) b);
        }

        String responseLine = sb.toString().trim();
        mLog.debug("PlutoSDR server response: [{}]", responseLine);

        // Parse the JSON response robustly — a non-JSON response means we connected
        // to the wrong service on this host:port.
        JsonObject response;
        try
        {
            com.google.gson.JsonElement element = mGson.fromJson(responseLine, com.google.gson.JsonElement.class);
            if(element == null || !element.isJsonObject())
            {
                throw new IOException("PlutoSDR server at " + mHost + ":" + mPort +
                        " returned a non-JSON response (wrong service on this port?): [" + responseLine + "]");
            }
            response = element.getAsJsonObject();
        }
        catch(com.google.gson.JsonSyntaxException jse)
        {
            throw new IOException("PlutoSDR server at " + mHost + ":" + mPort +
                    " returned an invalid JSON response (wrong service on this port?): [" + responseLine + "]", jse);
        }

        if(!"ok".equals(response.has("status") ? response.get("status").getAsString() : null))
        {
            String msg = response.has("message")
                    ? response.get("message").getAsString()
                    : "status=" + (response.has("status") ? response.get("status").getAsString() : "missing");
            throw new IOException("PlutoSDR server returned error: " + msg);
        }

        // Update the sample rate from the server's confirmed value.
        // NOTE: We update mSampleRate directly here WITHOUT calling mFrequencyController.setSampleRate()
        // because connect() may be called while the TunerController lock is already held (e.g. from
        // setTunedFrequency() → reconnect() called inside PolyphaseChannelSourceManager.getSource()).
        // mFrequencyController.setSampleRate() broadcasts a sample-rate-change event which tries to
        // re-acquire the same lock, causing a deadlock.  The sample rate is already set correctly in
        // the frequency controller from the initial applyConfiguration() / setSampleRate() call;
        // we only need to sync mSampleRate if the server reports a different value.
        if(response.has("sample_rate"))
        {
            int confirmedRate = response.get("sample_rate").getAsInt();
            if(confirmedRate != mSampleRate)
            {
                mLog.warn("PlutoSDR - server confirmed sample rate {} differs from requested {}; using server value",
                        confirmedRate, mSampleRate);
                mSampleRate = confirmedRate;
                // Update the frequency controller's sample rate field directly (no broadcast needed here
                // because the channelizer was already set up for the requested rate, and a mismatch
                // would require a full reconnect anyway).
                try
                {
                    mFrequencyController.setSampleRate(confirmedRate);
                }
                catch(Exception se)
                {
                    mLog.warn("PlutoSDR - could not update sample rate from server response", se);
                }
            }
        }

        // Parse device info fields from the connect response
        PlutoSdrDeviceInfo info = new PlutoSdrDeviceInfo();
        if(response.has("rf_bandwidth"))
        {
            info.setActualRfBandwidth(response.get("rf_bandwidth").getAsInt());
        }
        if(response.has("hw_model"))
        {
            info.setHwModel(response.get("hw_model").getAsString());
        }
        if(response.has("hw_serial"))
        {
            info.setHwSerial(response.get("hw_serial").getAsString());
        }
        if(response.has("fw_version"))
        {
            info.setFwVersion(response.get("fw_version").getAsString());
        }
        if(response.has("temperature"))
        {
            info.setTemperatureCelsius(response.get("temperature").getAsDouble());
        }
        if(response.has("rssi"))
        {
            info.setRssi(response.get("rssi").getAsString());
        }
        // Store the tuned frequency and sample rate in the device info so the editor can display them
        info.setTunedFrequency(frequency);
        info.setSampleRate(sampleRate);
        mDeviceInfo.set(info);

        // Record the frequency the hardware is now actually tuned to.
        // This is used by the debounced retune to detect whether the hardware
        // needs to move, independently of FrequencyController.mTunedFrequency
        // which may have been updated ahead of the actual hardware reconnect.
        mHardwareFrequency = frequency;

        mLog.info("PlutoSDR connected to {}:{} freq={} Hz sampleRate={} rfBw={} gain={} agc={}",
                mHost, mPort, frequency, sampleRate,
                rfBandwidth == 0 ? "auto" : rfBandwidth + " Hz",
                gain, agc);
        mLog.info("PlutoSDR device: model={} serial={} fw={} temp={}°C",
                info.getHwModel(), info.getHwSerial(), info.getFwVersion(),
                String.format("%.1f", info.getTemperatureCelsius()));
    }

    /**
     * Closes the current connection, then opens a new one with the given parameters.
     * The streaming thread will detect the socket closure via IOException and loop back
     * to pick up the new socket on its next iteration.
     *
     * <p>This method acquires {@link #mSocketLock} so that only one reconnect can happen
     * at a time.  Any in-progress {@link #attemptReconnect()} background reconnect is
     * interrupted first (via thread interrupt) so that it stops sleeping and returns,
     * allowing this caller to take over the socket immediately.</p>
     *
     * <p><b>Important:</b> We do NOT interrupt the stream thread when it is actively
     * reading from the socket.  Interrupting a thread blocked on socket I/O closes the
     * underlying socket on some JVMs, which causes the stream thread to throw
     * "Socket closed", call attemptReconnect(), and race with this method.  Instead we
     * just close the socket directly — the stream thread's {@code in.read()} will throw
     * an IOException naturally, and it will loop back to pick up the new socket.</p>
     */
    private void reconnect(long frequency, int sampleRate, int gain, boolean agc, int rfBandwidth)
            throws IOException
    {
        // Only interrupt the stream thread if it is sleeping inside attemptReconnect().
        // We detect this by checking whether mInputStream is null (set to null by disconnect()
        // before attemptReconnect() is called).  If mInputStream is null the stream thread is
        // in the back-off sleep and needs to be woken up.  If mInputStream is non-null the
        // stream thread is actively reading and must NOT be interrupted.
        mSocketLock.lock();
        boolean streamIsReconnecting;
        try
        {
            streamIsReconnecting = (mInputStream == null);
        }
        finally
        {
            mSocketLock.unlock();
        }

        if(streamIsReconnecting)
        {
            // Stream thread is sleeping in attemptReconnect() — interrupt it so it returns
            // immediately and lets us take over the socket.
            Thread streamThread = mStreamThread;
            if(streamThread != null)
            {
                streamThread.interrupt();
            }
        }
        // If the stream thread is actively reading (mInputStream != null), do NOT interrupt it.
        // Just close the socket below; the read() will throw IOException and the stream thread
        // will loop back to pick up the new socket we are about to open.

        mSocketLock.lock();
        try
        {
            disconnect();
            connect(frequency, sampleRate, gain, agc, rfBandwidth);
            // NOTE: Do NOT call mFrequencyController.setFrequency() here.
            // The frequency passed to reconnect() is already the PPM-corrected tuned frequency
            // (set by FrequencyController before it calls setTunedFrequency() on us).
            // Calling setFrequency() again would apply PPM correction a second time,
            // causing a ~1 MHz offset at 858 MHz with typical PlutoSDR PPM values.
            // The frequency controller already has the correct nominal frequency in mFrequency
            // and the correct tuned frequency in mTunedFrequency — no update needed here.
        }
        finally
        {
            mSocketLock.unlock();
        }
    }

    /**
     * Closes the TCP socket and associated streams.
     * Caller must hold {@link #mSocketLock}.
     */
    private void disconnect()
    {
        try
        {
            if(mInputStream != null)
            {
                mInputStream.close();
                mInputStream = null;
            }
        }
        catch(IOException e)
        {
            // ignore
        }

        try
        {
            if(mOutputStream != null)
            {
                mOutputStream.close();
                mOutputStream = null;
            }
        }
        catch(IOException e)
        {
            // ignore
        }

        try
        {
            if(mSocket != null && !mSocket.isClosed())
            {
                mSocket.close();
                mSocket = null;
            }
        }
        catch(IOException e)
        {
            // ignore
        }
    }

    /**
     * Broadcasts a frequency-change source event so that downstream consumers
     * (e.g. the {@link io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager}'s
     * {@code ChannelCalculator}) are updated with the current tuned frequency.
     *
     * <p>This must be called after any reconnect that changes the tuner's center frequency
     * without going through {@link io.github.dsheirer.source.tuner.frequency.FrequencyController#setFrequency(long)},
     * such as the background {@link #attemptReconnect()} path.</p>
     */
    private void broadcastCurrentFrequency()
    {
        try
        {
            mFrequencyController.broadcast(
                    io.github.dsheirer.source.SourceEvent.frequencyChange(null, mFrequencyController.getFrequency()));
        }
        catch(Exception e)
        {
            mLog.warn("PlutoSDR - could not broadcast frequency change after reconnect: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Device status polling
    // =========================================================================

    /**
     * Starts a background thread that periodically sends a {@code {"command":"status"}} request
     * to the server on a fresh short-lived TCP connection and updates {@link #mDeviceInfo}.
     */
    private void startStatusPolling()
    {
        if(mStatusPollExecutor == null || mStatusPollExecutor.isShutdown())
        {
            mStatusPollExecutor = Executors.newSingleThreadScheduledExecutor(r ->
            {
                Thread t = new Thread(r, "PlutoSDR-StatusPoll-" + mHost + ":" + mPort);
                t.setDaemon(true);
                return t;
            });
        }

        mStatusPollFuture = mStatusPollExecutor.scheduleWithFixedDelay(
                this::pollDeviceStatus,
                STATUS_POLL_INTERVAL_SECONDS,
                STATUS_POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private void stopStatusPolling()
    {
        if(mStatusPollFuture != null)
        {
            mStatusPollFuture.cancel(false);
            mStatusPollFuture = null;
        }
        if(mStatusPollExecutor != null)
        {
            mStatusPollExecutor.shutdownNow();
            mStatusPollExecutor = null;
        }
    }

    /**
     * Opens a short-lived TCP connection to the server, sends a status command, reads the
     * JSON response, and updates {@link #mDeviceInfo}.  Errors are logged but not propagated.
     */
    private void pollDeviceStatus()
    {
        if(!mRunning.get())
        {
            return;
        }

        try(Socket sock = new Socket())
        {
            sock.connect(new InetSocketAddress(mHost, mPort), SOCKET_CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

            // Send status command
            String cmd = mGson.toJson(buildStatusCommand()) + "\n";
            sock.getOutputStream().write(cmd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sock.getOutputStream().flush();

            // Read response line
            DataInputStream in = new DataInputStream(sock.getInputStream());
            StringBuilder sb = new StringBuilder();
            int b;
            while((b = in.read()) != -1 && b != '\n')
            {
                sb.append((char) b);
            }

            String line = sb.toString().trim();
            if(line.isEmpty())
            {
                return;
            }

            com.google.gson.JsonElement el = mGson.fromJson(line, com.google.gson.JsonElement.class);
            if(el == null || !el.isJsonObject())
            {
                return;
            }

            JsonObject resp = el.getAsJsonObject();

            // Merge updated fields into the existing device info
            PlutoSdrDeviceInfo current = mDeviceInfo.get();
            PlutoSdrDeviceInfo updated = new PlutoSdrDeviceInfo(current);

            if(resp.has("temperature"))
            {
                updated.setTemperatureCelsius(resp.get("temperature").getAsDouble());
            }
            if(resp.has("rssi"))
            {
                updated.setRssi(resp.get("rssi").getAsString());
            }
            if(resp.has("rf_bandwidth"))
            {
                updated.setActualRfBandwidth(resp.get("rf_bandwidth").getAsInt());
            }
            if(resp.has("hw_model"))
            {
                updated.setHwModel(resp.get("hw_model").getAsString());
            }
            if(resp.has("hw_serial"))
            {
                updated.setHwSerial(resp.get("hw_serial").getAsString());
            }
            if(resp.has("fw_version"))
            {
                updated.setFwVersion(resp.get("fw_version").getAsString());
            }

            // Refresh tuned frequency and sample rate from the controller's live state
            updated.setTunedFrequency(mHardwareFrequency);
            updated.setSampleRate(mSampleRate);

            mDeviceInfo.set(updated);
            mLog.debug("PlutoSDR status poll: temp={}°C rssi={}", updated.getTemperatureCelsius(), updated.getRssi());
        }
        catch(Exception e)
        {
            mLog.debug("PlutoSDR status poll failed (server may not support status command): {}", e.getMessage());
        }
    }

    private JsonObject buildStatusCommand()
    {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "status");
        return cmd;
    }

    // =========================================================================
    // Sample streaming
    // =========================================================================

    /**
     * Main streaming loop – runs on a dedicated daemon thread.
     * Reads raw interleaved int16 IQ bytes from the server and broadcasts them
     * as {@link SignedShortNativeBuffer} instances to all registered listeners.
     *
     * <p><b>Startup reconnects:</b> It is normal to see a few "stream error – attempting reconnect"
     * messages at startup.  The Python server configures the AD9361 hardware (which takes ~1–2 s)
     * before it starts streaming IQ data.  During that window the server may close the socket if
     * the hardware is not yet ready, causing the stream thread to reconnect.  Once the hardware is
     * stable the stream runs continuously.  These reconnects are harmless and expected.</p>
     *
     * <p><b>Stream stats:</b> Every {@link #STREAM_STATS_LOG_INTERVAL_BUFFERS} buffers the loop
     * logs throughput (buffers/s, MB/s, total MB received) at INFO level so you can verify the
     * data pipeline is healthy without enabling DEBUG logging.</p>
     *
     * <p><b>Rate control:</b> The server's {@code sdr.rx()} call blocks until the IIO DMA kernel
     * driver has a full buffer ready, which naturally paces the TCP stream to the hardware sample
     * rate.  The client reads as fast as possible; no client-side sleep is needed or desired.</p>
     */
    private void streamLoop()
    {
        // Snapshot the buffer sizes at stream-start.  If the sample rate changes mid-stream
        // (via reconnect) the stream loop will be restarted, so it is safe to use locals here.
        int samplesPerBuffer = mSamplesPerBuffer;
        int bufferBytes      = mBufferBytes;
        byte[] rawBytes      = new byte[bufferBytes];

        // Stream throughput stats
        long statsBufferCount  = 0;
        long statsTotalBytes   = 0;
        long statsWindowStart  = System.currentTimeMillis();

        while(mRunning.get())
        {
            // Re-snapshot in case a reconnect changed the sample rate
            if(mBufferBytes != bufferBytes)
            {
                samplesPerBuffer = mSamplesPerBuffer;
                bufferBytes      = mBufferBytes;
                rawBytes         = new byte[bufferBytes];
                mLog.info("PlutoSDR stream loop: buffer resized to {} bytes ({} samples)",
                        bufferBytes, samplesPerBuffer);
                // Reset stats window after a sample-rate change so the first window isn't skewed
                statsBufferCount = 0;
                statsWindowStart = System.currentTimeMillis();
            }

            try
            {
                // Clear any pending interrupt from a reconnect() call before we start reading.
                // The interrupt was used to wake us out of a sleep in attemptReconnect(); now
                // that we have a live socket again we must clear it so the read() below doesn't
                // immediately throw InterruptedException.
                Thread.interrupted();

                DataInputStream in;
                mSocketLock.lock();
                try
                {
                    in = mInputStream;
                }
                finally
                {
                    mSocketLock.unlock();
                }

                if(in == null)
                {
                    Thread.sleep(100);
                    continue;
                }

                // Read a full buffer of raw bytes.
                // The server's sdr.rx() blocks until the IIO DMA driver has a full buffer
                // ready, so this read() naturally blocks at the hardware sample rate.
                // No client-side sleep is needed; adding one would cause TCP backpressure
                // that slows the server's send loop to half speed.
                int offset = 0;
                while(offset < bufferBytes)
                {
                    int read = in.read(rawBytes, offset, bufferBytes - offset);
                    if(read < 0)
                    {
                        throw new IOException("Server closed the connection");
                    }
                    offset += read;
                }

                // Update throughput counters
                statsBufferCount++;
                statsTotalBytes += bufferBytes;

                // Log stream stats every N buffers - DISABLED to reduce log noise
                // if(statsBufferCount % STREAM_STATS_LOG_INTERVAL_BUFFERS == 0)
                // {
                //     long nowMs        = System.currentTimeMillis();
                //     long elapsedMs    = nowMs - statsWindowStart;
                //     double bufsPerSec = elapsedMs > 0 ? (STREAM_STATS_LOG_INTERVAL_BUFFERS * 1000.0 / elapsedMs) : 0;
                //     double mbPerSec   = elapsedMs > 0
                //             ? (STREAM_STATS_LOG_INTERVAL_BUFFERS * (double) bufferBytes / 1_048_576.0 / (elapsedMs / 1000.0))
                //             : 0;
                //     double totalMb    = statsTotalBytes / 1_048_576.0;
                //     PlutoSdrDeviceInfo info = mDeviceInfo.get();
                //     // Pre-format floating-point values; SLF4J {} placeholders don't support format specifiers
                //     mLog.trace("PlutoSDR stream stats: {} bufs/s  {} MB/s  total={} MB  " +
                //                     "sampleRate={} Hz  rfBw={}  temp={}  rssi={}",
                //             String.format("%.1f", bufsPerSec),
                //             String.format("%.2f", mbPerSec),
                //             String.format("%.1f", totalMb),
                //             mSampleRate,
                //             info.getActualRfBandwidth() > 0
                //                     ? info.getActualRfBandwidthString()
                //                     : "auto",
                //             info.getTemperatureString(),
                //             info.getRssi());
                //     statsWindowStart = nowMs;
                // }

                if(hasBufferListeners())
                {
                    // Wrap as a short buffer (little-endian int16 IQ pairs)
                    ByteBuffer byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
                    ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
                    short[] samples = new short[samplesPerBuffer * 2]; // interleaved I,Q
                    shortBuffer.get(samples);

                    // Periodically calculate and update DC offset correction values
                    if(shouldCalculateDc())
                    {
                        calculateDc(samples);
                    }

                    INativeBuffer nativeBuffer = new SignedShortNativeBuffer(samples,
                            System.currentTimeMillis(), (float) mSampleRate / 1000.0f,
                            mIAverageDc, mQAverageDc);
                    broadcast(nativeBuffer);
                }
            }
            catch(InterruptedException ie)
            {
                // An interrupt here means reconnect() wants us to stop sleeping in
                // attemptReconnect() so it can take over the socket.  Clear the flag
                // and loop back to the top; the socket will have been replaced by
                // reconnect() before we get there.
                Thread.interrupted();
                continue;
            }
            catch(IOException e)
            {
                if(mRunning.get())
                {
                    // Check if another thread (e.g. reconnect() from setTunedFrequency or
                    // setFrequencyCorrection) has already replaced the socket.  If so, the
                    // "Socket closed" error is expected — just loop back and pick up the new
                    // socket without entering the slow attemptReconnect() back-off loop.
                    // This prevents the stream thread from racing with the PPM-correction
                    // reconnect and opening a SECOND connection with the wrong frequency.
                    mSocketLock.lock();
                    boolean alreadyReconnected;
                    try
                    {
                        alreadyReconnected = (mInputStream != null);
                    }
                    finally
                    {
                        mSocketLock.unlock();
                    }

                    if(alreadyReconnected)
                    {
                        mLog.info("PlutoSDR stream error ({}), but socket already replaced by another thread — resuming",
                                e.getMessage());
                        // Reset stats window so the next window starts clean
                        statsBufferCount = 0;
                        statsWindowStart = System.currentTimeMillis();
                        continue;
                    }

                    // NOTE: "Socket closed" errors at startup are expected.  The Python server
                    // configures the AD9361 hardware (~1-2 s) before streaming begins.  During
                    // that window it may close the socket if the hardware is not yet ready.
                    // The reconnect loop will retry with back-off until the server is stable.
                    mLog.error("PlutoSDR stream error - attempting reconnect: {}", e.getMessage());
                    // Reset stats window so the next window starts clean after reconnect
                    statsBufferCount = 0;
                    statsWindowStart = System.currentTimeMillis();
                    // Close the dead socket and clear mInputStream BEFORE calling attemptReconnect().
                    // Without this, attemptReconnect() sees mInputStream != null and immediately
                    // returns thinking another thread already reconnected, causing an infinite
                    // "socket already reconnected by another thread" / "Server closed the connection"
                    // loop that never actually reconnects.
                    mSocketLock.lock();
                    try
                    {
                        disconnect();
                    }
                    finally
                    {
                        mSocketLock.unlock();
                    }
                    attemptReconnect();
                }
            }
        }

        mLog.debug("PlutoSDR stream loop exited (total received: {} MB)",
                String.format("%.1f", statsTotalBytes / 1_048_576.0));
    }

    /**
     * Attempts to reconnect to the server after a stream error, with exponential back-off.
     *
     * <p>This method runs on the stream thread.  It can be interrupted by {@link #reconnect}
     * (called from the UI or channel-allocation thread) so that the caller can take over
     * the socket immediately.  When interrupted, this method returns without reconnecting,
     * and the stream loop will pick up the new socket on its next iteration.</p>
     */
    private void attemptReconnect()
    {
        int delayMs = 1_000;
        final int maxDelayMs = 30_000;

        while(mRunning.get())
        {
            try
            {
                Thread.sleep(delayMs);
            }
            catch(InterruptedException ie)
            {
                // reconnect() interrupted us – it will handle the reconnect itself.
                // Clear the flag and return so the stream loop can pick up the new socket.
                Thread.interrupted();
                return;
            }

            // If reconnect() grabbed the lock while we were sleeping, the socket has
            // already been replaced.  Check whether we still need to reconnect.
            mSocketLock.lock();
            boolean needsConnect;
            try
            {
                needsConnect = (mInputStream == null);
            }
            finally
            {
                mSocketLock.unlock();
            }

            if(!needsConnect)
            {
                mLog.info("PlutoSDR - socket already reconnected by another thread");
                return;
            }

            // Check for interrupt one more time before attempting the (potentially slow) connect.
            if(Thread.interrupted())
            {
                return;
            }

            try
            {
                mLog.info("PlutoSDR - attempting reconnect to {}:{}", mHost, mPort);
                mSocketLock.lock();
                try
                {
                    connect();
                }
                finally
                {
                    mSocketLock.unlock();
                }
                // Notify downstream consumers (e.g. ChannelCalculator) of the current frequency
                // because this reconnect path does not go through FrequencyController.setFrequency().
                broadcastCurrentFrequency();
                mLog.info("PlutoSDR - reconnected successfully");
                return;
            }
            catch(IOException e)
            {
                // If we were interrupted during the connect (reconnect() closed the socket under us),
                // treat it as a hand-off rather than a failure.
                if(Thread.interrupted())
                {
                    return;
                }
                mLog.warn("PlutoSDR - reconnect failed: {} - retrying in {} ms", e.getMessage(), delayMs);
                delayMs = Math.min(delayMs * 2, maxDelayMs);
            }
        }
    }

    // =========================================================================
    // DC offset correction helpers
    // =========================================================================

    /**
     * Indicates whether a DC offset calculation should be performed for the current buffer.
     * Runs an initial burst of 5 calculations on startup, then recalculates every 60 seconds.
     */
    private boolean shouldCalculateDc()
    {
        if(System.currentTimeMillis() > (mLastDcCalculationTimestamp + DC_PROCESSING_INTERVAL_MS))
        {
            if(mDcCalculationsRemaining > 0)
            {
                return true;
            }
            else
            {
                // Reset for the next interval
                mDcCalculationsRemaining = DC_CALCULATIONS_PER_INTERVAL;
                mLastDcCalculationTimestamp = System.currentTimeMillis();
            }
        }

        return false;
    }

    /**
     * Calculates the average DC offset in the I and Q channels of the given interleaved short
     * sample array and updates the running DC correction values using a low-pass filter.
     *
     * <p>The AD9361 normalisation scale is 2048 (12-bit ADC), so we divide the raw short
     * accumulator by 2048 to get the DC offset in normalised [-1,1] units.</p>
     *
     * @param samples interleaved signed 16-bit IQ samples (I0, Q0, I1, Q1, …)
     */
    private void calculateDc(short[] samples)
    {
        double iAccumulator = 0;
        double qAccumulator = 0;

        for(int x = 0; x < samples.length; x += 2)
        {
            iAccumulator += samples[x];
            qAccumulator += samples[x + 1];
        }

        int sampleCount = samples.length / 2;

        // Convert raw short average to normalised units (AD9361 12-bit range = ±2048)
        float iDc = (float)(iAccumulator / sampleCount) / 2048.0f;
        float qDc = (float)(qAccumulator / sampleCount) / 2048.0f;

        // Low-pass filter: blend the new measurement into the running average
        float iResidual = iDc - mIAverageDc;
        float qResidual = qDc - mQAverageDc;

        mIAverageDc += iResidual * DC_FILTER_GAIN;
        mQAverageDc += qResidual * DC_FILTER_GAIN;

        // If both channels are close enough to zero, stop calculating for this interval
        if(Math.abs(iResidual) < TARGET_DC_OFFSET_REMAINING &&
           Math.abs(qResidual) < TARGET_DC_OFFSET_REMAINING)
        {
            mDcCalculationsRemaining--;
        }

    }

    // =========================================================================
    // Accessors used by the editor
    // =========================================================================

    public String getHost()
    {
        return mHost;
    }

    public int getPort()
    {
        return mPort;
    }

    public int getRfGain()
    {
        return mRfGain;
    }

    public void setRfGain(int gain) throws SourceException
    {
        mRfGain = gain;
        if(mRunning.get())
        {
            try
            {
                // Use the PPM-corrected tuned frequency (mHardwareFrequency) so we don't
                // accidentally re-apply PPM correction a second time via getFrequency().
                long tunedFreq = mHardwareFrequency > 0 ? mHardwareFrequency : mFrequencyController.getTunedFrequency();
                mLog.info("PlutoSDR - setRfGain({}): reconnecting at tuned={} Hz", gain, tunedFreq);
                reconnect(tunedFreq, mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
            }
            catch(IOException e)
            {
                throw new SourceException("PlutoSDR - error setting gain: " + e.getMessage(), e);
            }
        }
    }

    public boolean isAgcEnabled()
    {
        return mAgcEnabled;
    }

    public void setAgcEnabled(boolean enabled) throws SourceException
    {
        mAgcEnabled = enabled;
        if(mRunning.get())
        {
            try
            {
                // Use the PPM-corrected tuned frequency (mHardwareFrequency) so we don't
                // accidentally re-apply PPM correction a second time via getFrequency().
                long tunedFreq = mHardwareFrequency > 0 ? mHardwareFrequency : mFrequencyController.getTunedFrequency();
                mLog.info("PlutoSDR - setAgcEnabled({}): reconnecting at tuned={} Hz", enabled, tunedFreq);
                reconnect(tunedFreq, mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
            }
            catch(IOException e)
            {
                throw new SourceException("PlutoSDR - error setting AGC: " + e.getMessage(), e);
            }
        }
    }

    public void setSampleRate(int sampleRate) throws SourceException
    {
        mSampleRate = sampleRate;
        updateBufferSizing(sampleRate);

        // FrequencyController.setSampleRate() silently ignores the new rate when the sample rate is
        // locked (mSampleRateLocked = true).  The lock is set by PolyphaseChannelSourceManager when
        // channels are active, to prevent the USER from changing the rate via the UI.  However, when
        // setSampleRate() is called programmatically (e.g. from setSampleRateForBandwidth() during
        // auto-bandwidth expansion), we MUST update the frequency controller regardless of the lock
        // state — otherwise the overlay panel's bandwidth stays at the old value and channel labels
        // no longer line up with the actual signals in the waterfall.
        //
        // Fix: temporarily unlock, update, then restore the previous lock state.
        boolean wasLocked = mFrequencyController.isSampleRateLocked();
        if(wasLocked)
        {
            try { mFrequencyController.setSampleRateLocked(false); } catch(SourceException ignored) {}
        }
        mFrequencyController.setSampleRate(sampleRate);
        if(wasLocked)
        {
            try { mFrequencyController.setSampleRateLocked(true); } catch(SourceException ignored) {}
        }

        if(mRunning.get())
        {
            try
            {
                // Use getTunedFrequency() (PPM-corrected) rather than getFrequency() (nominal)
                // because setSampleRate() → setSampleRate() → PolyphaseChannelManager may have
                // already called setTunedFrequency() with a new center frequency for the new
                // bandwidth.  Using the tuned frequency ensures we send the correct center to
                // the server after a sample rate change.
                reconnect(mFrequencyController.getTunedFrequency(), mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
            }
            catch(IOException e)
            {
                throw new SourceException("PlutoSDR - error setting sample rate: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Returns the configured RF bandwidth in Hz, or 0 for auto.
     */
    public int getRfBandwidth()
    {
        return mRfBandwidth;
    }

    /**
     * Sets the RF bandwidth in Hz (200 kHz – 56 MHz), or 0 for auto (driver default).
     * If the controller is running, reconnects immediately with the new bandwidth.
     */
    public void setRfBandwidth(int rfBandwidth) throws SourceException
    {
        mRfBandwidth = rfBandwidth;
        if(mRunning.get())
        {
            try
            {
                // Use the PPM-corrected tuned frequency (mHardwareFrequency) so we don't
                // accidentally re-apply PPM correction a second time via getFrequency().
                long tunedFreq = mHardwareFrequency > 0 ? mHardwareFrequency : mFrequencyController.getTunedFrequency();
                mLog.info("PlutoSDR - setRfBandwidth({}): reconnecting at tuned={} Hz",
                        rfBandwidth == 0 ? "auto" : rfBandwidth + " Hz", tunedFreq);
                reconnect(tunedFreq, mSampleRate, mRfGain, mAgcEnabled, mRfBandwidth);
            }
            catch(IOException e)
            {
                throw new SourceException("PlutoSDR - error setting RF bandwidth: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Returns the latest device info snapshot received from the server.
     * Never null; fields may be "unknown" / NaN if the server has not yet responded.
     */
    public PlutoSdrDeviceInfo getDeviceInfo()
    {
        return mDeviceInfo.get();
    }

    public boolean isRunning()
    {
        return mRunning.get();
    }

    /**
     * Returns true if the tuner's centre frequency is locked (will not retune when channels are activated).
     */
    public boolean isFrequencyLocked()
    {
        return mFrequencyLocked;
    }

    /**
     * Sets the frequency lock state.
     *
     * <p>When {@code true}, {@link #setTunedFrequency(long)} will silently ignore all retune
     * requests from the PolyphaseChannelManager.  This keeps the waterfall overlay aligned with
     * the actual signals even when channels are activated at different frequencies.</p>
     *
     * @param locked {@code true} to lock the centre frequency, {@code false} to allow retuning
     */
    public void setFrequencyLocked(boolean locked)
    {
        mFrequencyLocked = locked;
        mLog.info("PlutoSDR - frequency lock {}", locked ? "ENABLED (centre frequency will not change when channels are activated)"
                : "DISABLED (centre frequency will follow channel assignments)");
    }

    // =========================================================================
    // IBandwidthAdjustableTunerController implementation
    // =========================================================================

    /**
     * Supported AD9361 sample rates in ascending order (samples per second).
     * These match the options shown in the tuner editor.
     */
    private static final List<Integer> SUPPORTED_SAMPLE_RATES = Arrays.asList(
            521_000,
            1_000_000,
            1_500_000,
            2_000_000,
            2_500_000,
            3_000_000,
            4_000_000,
            5_000_000,
            6_000_000,
            8_000_000,
            10_000_000,
            12_000_000,
            15_000_000,
            20_000_000,
            30_000_000,
            40_000_000,
            56_000_000,
            61_440_000
    );

    /**
     * Selects and applies the smallest AD9361 sample rate whose usable bandwidth
     * (sample_rate × {@link #USABLE_BANDWIDTH}) is at least {@code requiredBandwidthHz} wide.
     *
     * <p>Called by {@link io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager}
     * when a requested channel set does not fit within the current usable bandwidth and the
     * sample rate is not locked.</p>
     *
     * @param requiredBandwidthHz minimum usable bandwidth needed in Hertz
     * @throws SourceException if no supported sample rate can provide the required bandwidth
     */
    @Override
    public void setSampleRateForBandwidth(long requiredBandwidthHz) throws SourceException
    {
        for(int rate : SUPPORTED_SAMPLE_RATES)
        {
            long usable = (long)(rate * USABLE_BANDWIDTH);
            if(usable >= requiredBandwidthHz)
            {
                if(rate != mSampleRate)
                {
                    mLog.info("PlutoSDR - auto-adjusting sample rate from {} to {} Hz to fit channel set " +
                            "(required usable bandwidth: {} Hz)", mSampleRate, rate, requiredBandwidthHz);
                    setSampleRate(rate);
                }
                return;
            }
        }

        throw new SourceException("PlutoSDR - no supported sample rate provides the required usable bandwidth of " +
                requiredBandwidthHz + " Hz (maximum is " +
                (long)(SUPPORTED_SAMPLE_RATES.get(SUPPORTED_SAMPLE_RATES.size() - 1) * USABLE_BANDWIDTH) + " Hz)");
    }
}
