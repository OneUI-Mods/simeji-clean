package net.ryuya_dev.simejiclean;

/**
 * Startup-only crash accounting. Deliberate stops are not crashes; protection is explicitly reset.
 */
final class StartupGuard {
  record State(int crashes, boolean blocked) {}

  static boolean recent(long oldBoot, long newBoot, long oldStart, long now) {
    return oldBoot > 0
        && newBoot > 0
        && Math.abs(oldBoot - newBoot) < 10000
        && oldStart >= 0
        && now >= oldStart
        && now - oldStart < 120000;
  }

  static State next(
      int previous, boolean blocked, boolean sameEpoch, boolean recent, boolean crash) {
    if (!sameEpoch) return new State(0, false);
    if (blocked) return new State(3, true);
    int count = recent && crash ? Math.min(3, Math.max(0, Math.min(3, previous)) + 1) : 0;
    return new State(count, count >= 3);
  }

  private StartupGuard() {}
}
