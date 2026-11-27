package net.ryuya_dev.simejiclean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ConfigSnapshotTest {
  @Test
  public void remoteSnapshotPreservesDisabledAndProtectionEpoch() {
    assertEquals(new ConfigSnapshot(false, 1795799999987L),
        ConfigSnapshot.decode("0:1795799999987"));
    assertEquals(new ConfigSnapshot(true, 0), ConfigSnapshot.decode("1:0"));
  }

  @Test
  public void uninitializedOrInvalidRemoteConfigurationFallsBack() {
    assertNull(ConfigSnapshot.decode(null));
    assertNull(ConfigSnapshot.decode("fake"));
    assertNull(ConfigSnapshot.decode("1:-1"));
    assertNull(ConfigSnapshot.decode("3:0"));
  }
}
