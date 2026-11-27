package net.ryuya_dev.simejiclean;

import android.content.SharedPreferences;

record ConfigSnapshot(boolean enabled, long epoch) {
  static final String GROUP = "simeji_clean";
  static final String KEY = "configuration";

  static ConfigSnapshot local(SharedPreferences preferences) {
    return new ConfigSnapshot(preferences.getBoolean("enabled", true), preferences.getLong("epoch", 0));
  }

  static ConfigSnapshot remote(SharedPreferences preferences) {
    return decode(preferences.getString(KEY, null));
  }

  static ConfigSnapshot decode(String value) {
    if (value == null || value.length() < 3 || value.charAt(1) != ':') return null;
    if (value.charAt(0) != '0' && value.charAt(0) != '1') return null;
    try {
      long epoch = Long.parseLong(value.substring(2));
      return epoch >= 0 ? new ConfigSnapshot(value.charAt(0) == '1', epoch) : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  boolean publish(SharedPreferences preferences) {
    // Both fields change together in the host's remote preference cache.
    return preferences.edit().putString(KEY, (enabled ? "1:" : "0:") + epoch).commit();
  }
}
