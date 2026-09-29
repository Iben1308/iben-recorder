package ua.iben.recorder;

/** OS-dependent decisions shared by UI, receivers and the recording service. */
final class PlatformPolicy {
    static boolean publicStorage(int sdk) { return sdk <= 28; }
    static boolean needsArmedService(int sdk) { return sdk >= 30; }
    static boolean mayStartMicrophone(int sdk, boolean visible, boolean runningService) {
        return !needsArmedService(sdk) || visible || runningService;
    }
    static boolean keepStandby(int sdk, boolean enabled, boolean permitted, boolean paused) {
        return needsArmedService(sdk) && enabled && permitted && !paused;
    }
}
