package net.ryuya_dev.simejiclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class StartupGuardTest {
  @Test
  public void threeConfirmedCrashesLatchProtection() {
    var a = StartupGuard.next(0, false, true, true, true);
    var b = StartupGuard.next(a.crashes(), a.blocked(), true, true, true);
    var c = StartupGuard.next(b.crashes(), b.blocked(), true, true, true);
    assertFalse(a.blocked());
    assertFalse(b.blocked());
    assertTrue(c.blocked());
  }

  @Test
  public void deliberateStopResetsOnlyUnlatchedCounter() {
    assertEquals(0, StartupGuard.next(2, false, true, true, false).crashes());
    assertTrue(StartupGuard.next(3, true, true, true, false).blocked());
  }

  @Test
  public void resetEpochClearsLatchedProtection() {
    assertFalse(StartupGuard.next(3, true, false, true, true).blocked());
  }

  @Test
  public void rebootDoesNotCountOldCrash() {
    assertFalse(StartupGuard.recent(1000000, 2000000, 2000, 2500));
    assertEquals(0, StartupGuard.next(2, false, true, false, true).crashes());
  }

  @Test
  public void futureStartAndMissingMetadataCannotCount() {
    assertFalse(StartupGuard.recent(1000000, 1000000, 1000, 500));
    assertFalse(StartupGuard.recent(0, 1000000, 100, 500));
  }

  @Test
  public void twoMinuteBoundaryDoesNotCount() {
    assertFalse(StartupGuard.recent(1000000, 1000000, 1000, 121000));
    assertTrue(StartupGuard.recent(1000000, 1000000, 1000, 120999));
  }

  @Test
  public void corruptCounterCannotOverflow() {
    assertEquals(3, StartupGuard.next(Integer.MAX_VALUE, false, true, true, true).crashes());
    assertEquals(1, StartupGuard.next(-2, false, true, true, true).crashes());
  }

  @Test
  public void latchedProtectionSurvivesStableSessionAndReboot() {
    assertTrue(StartupGuard.next(3, true, true, false, false).blocked());
  }
}
