package android.os;

/** Host-only stand-in for exercising callbacks; no Android CPU/performance claim. */
public final class Debug {
    public static long threadCpuTimeNanos() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }
}
