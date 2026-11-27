package net.ryuya_dev.simejiclean;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class SimejiCleanApplication extends Application {
  private static final String SYNCED = "vector_synced";
  private final Handler main = new Handler(Looper.getMainLooper());
  private SharedPreferences local;
  private SharedPreferences remote;
  private XposedService service;

  @Override
  public void onCreate() {
    super.onCreate();
    local = getSharedPreferences("config", MODE_PRIVATE);
    XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
      @Override
      public void onServiceBind(XposedService bound) {
        main.post(() -> connect(bound));
      }

      @Override
      public void onServiceDied(XposedService dead) {
        main.post(() -> {
          if (service == dead) {
            service = null;
            remote = null;
          }
        });
      }
    });
  }

  private void connect(XposedService bound) {
    service = bound;
    remote = null;
    try {
      if ((bound.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) return;
      SharedPreferences preferences = bound.getRemotePreferences(ConfigSnapshot.GROUP);
      ConfigSnapshot saved = ConfigSnapshot.remote(preferences);
      if (saved == null) {
        saved = ConfigSnapshot.local(local);
        if (!saved.publish(preferences)) return;
      }
      remote = preferences;
      storeLocal(saved);
      Log.i("SimejiClean", "CONFIG_SYNCED vector");
    } catch (RuntimeException error) {
      Log.w("SimejiClean", "Could not connect to Vector preferences", error);
    }
  }

  SharedPreferences localConfig() {
    return local;
  }

  boolean saveConfig(ConfigSnapshot next) {
    if (remote == null && local.getBoolean(SYNCED, false)) return false;
    try {
      if (remote != null && !next.publish(remote)) return false;
      storeLocal(next);
      return true;
    } catch (RuntimeException error) {
      Log.w("SimejiClean", "Could not save configuration", error);
      return false;
    }
  }

  private void storeLocal(ConfigSnapshot config) {
    local.edit().putBoolean("enabled", config.enabled()).putLong("epoch", config.epoch())
        .putBoolean(SYNCED, remote != null).apply();
    getContentResolver().notifyChange(ConfigProvider.URI, null);
  }
}
