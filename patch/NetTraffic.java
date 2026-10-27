package com.gathin.tvhome;

import android.net.TrafficStats;

import java.util.Locale;

/**
 * Download and upload rates for the TV's connection, sampled from the system's traffic counters.
 *
 * The launcher shows this in the status line under the clock, where the prayer reminder used to be.
 * A TV on this firmware has one network up at a time (Wi-Fi or Ethernet), so the device-wide counters
 * are the connected network's counters; no permission is needed for them, unlike per-network stats.
 */
final class NetTraffic {

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastAt;
    private double rxRate;
    private double txRate;

    /** Reads the counters and turns the change since the last call into bytes per second. */
    void sample() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = System.currentTimeMillis();
        if (rx < 0 || tx < 0) {          // counters unavailable on this device
            rxRate = 0;
            txRate = 0;
            return;
        }
        if (lastRx >= 0 && now > lastAt) {
            double seconds = (now - lastAt) / 1000.0;
            rxRate = Math.max(0, (rx - lastRx) / seconds);
            txRate = Math.max(0, (tx - lastTx) / seconds);
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;
    }

    double rxRate() {
        return rxRate;
    }

    double txRate() {
        return txRate;
    }

    /** "↓ 1.2 MB/s   ↑ 240 KB/s" */
    String formatted() {
        return "\u2193 " + rate(rxRate) + "   \u2191 " + rate(txRate);
    }

    static String rate(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        }
        if (bytesPerSecond >= 1024) {
            return String.format(Locale.getDefault(), "%.0f KB/s", bytesPerSecond / 1024);
        }
        return "0 KB/s";
    }
}
