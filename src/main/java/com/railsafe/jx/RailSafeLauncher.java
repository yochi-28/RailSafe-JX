package com.railsafe.jx;

import com.railsafe.jx.ui.RailSafeApplication;

/**
 * Plain Java entry point for packaged, non-modular JavaFX distributions.
 *
 * The native jpackage launcher starts this class rather than JavaFX's
 * Application subclass directly. That allows JavaFX's platform-specific jars
 * to be supplied on the packaged application's classpath while keeping the
 * Maven source-run entry point unchanged.
 */
public final class RailSafeLauncher {
    private RailSafeLauncher() {
    }

    public static void main(String[] args) {
        RailSafeApplication.main(args);
    }
}
