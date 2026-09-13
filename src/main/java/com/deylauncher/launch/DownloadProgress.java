package com.deylauncher.launch;

/**
 * Optional progress hook for the multi-file download phases of a launch (the Java runtime and the
 * game files). Implementations receive a 0.0 -> 1.0 fraction of how many bytes of that phase have
 * been written, which the UI turns into the real "how far through the launch are we" percentage.
 * Pass {@code null} where you don't care about reporting (the console path does exactly that).
 */
@FunctionalInterface
public interface DownloadProgress {
    void onProgress(double fraction);
}