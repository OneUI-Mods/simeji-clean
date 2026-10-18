package net.ryuya_dev.simejiclean;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import io.github.libxposed.api.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;

public final class HookEntry extends XposedModule {
  private Context context;
  private volatile boolean enabled, failed, protectedMode;
  private boolean installed, initializing, observing;
  private long epoch;
  private WeakReference<InputMethodService> ime = new WeakReference<>(null);
  private final Handler main = new Handler(Looper.getMainLooper());
  private String process, lastStatus = "";
  private PopupDiscovery.Profile profile;

  @Override
  public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
    ModernHook.attach(this);
    process = p.getProcessName();
  }

  @Override
  public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
    if (!p.isFirstPackage() || !ConfigProvider.HOST.equals(p.getPackageName())) return;
    ModernHook.findAndHookMethod(
        Application.class,
        "attach",
        Context.class,
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param call) {
            if (context == null)
              bootstrap((Context) call.args[0], p.getClassLoader(), p.getApplicationInfo());
          }
        });
  }

  private void bootstrap(Context c, ClassLoader loader, ApplicationInfo app) {
    try {
      var info =
          c.getPackageManager().getPackageInfo(ConfigProvider.HOST, PackageManager.GET_SERVICES);
      List<ServiceInfo> candidates = new ArrayList<>();
      if (info.services != null)
        for (ServiceInfo service : info.services)
          if ("android.permission.BIND_INPUT_METHOD".equals(service.permission))
            candidates.add(service);
      ComponentName current =
          ComponentName.unflattenFromString(
              Objects.toString(
                  Settings.Secure.getString(
                      c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD),
                  ""));
      if (candidates.size() > 1
          && current != null
          && ConfigProvider.HOST.equals(current.getPackageName()))
        candidates.removeIf(service -> !service.name.equals(current.getClassName()));
      if (candidates.size() != 1)
        throw new IllegalStateException("IME service candidates=" + candidates.size());
      ServiceInfo selected = candidates.get(0);
      if (!Objects.equals(process, selected.processName)) return;
      context = c;
      Class<?> service = Class.forName(selected.name, false, loader);
      if (!InputMethodService.class.isAssignableFrom(service))
        throw new IllegalStateException("IME service changed");
      Method start = service.getMethod("onStartInputView", EditorInfo.class, boolean.class);
      if (!ModernHook.deoptimize(start))
        throw new IllegalStateException("IME callback unavailable");
      ModernHook.hookMethod(
          start,
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              ime = new WeakReference<>((InputMethodService) p.thisObject);
              // Retry setup on a real input-view event if a newly installed module had not been
              // opened.
              if (!installed && !failed && !protectedMode) initialize(loader, app);
              else refresh();
            }
          });
      initialize(loader, app);
    } catch (Throwable error) {
      failed = true;
      enabled = false;
      report("unsupported");
      log("BOOTSTRAP_STOP " + error.getClass().getSimpleName());
    }
  }

  private void initialize(ClassLoader loader, ApplicationInfo app) {
    if (initializing || installed || failed || protectedMode) return;
    initializing = true;
    try {
      if (!refresh()) return;
      if (!observing) {
        try {
          context
              .getContentResolver()
              .registerContentObserver(
                  ConfigProvider.URI,
                  false,
                  new ContentObserver(main) {
                    @Override
                    public void onChange(boolean self) {
                      refresh();
                    }
                  });
          observing = true;
        } catch (RuntimeException unavailable) {
          enabled = false;
          log("CONFIG_UNAVAILABLE: open Simeji Clean, then show the keyboard again");
          return;
        }
      }
      var guard =
          context
              .createDeviceProtectedStorageContext()
              .getSharedPreferences("net.ryuya_dev.simejiclean.guard", 0);
      long now = SystemClock.elapsedRealtime(),
          wall = System.currentTimeMillis(),
          boot = wall - now;
      boolean sameEpoch = guard.getLong("epoch", -1) == epoch;
      boolean
          recent =
              StartupGuard.recent(guard.getLong("boot", 0), boot, guard.getLong("start", -1), now),
          crashed = false;
      if (sameEpoch && recent && !guard.getBoolean("blocked", false)) {
        var exits =
            context
                .getSystemService(ActivityManager.class)
                .getHistoricalProcessExitReasons(null, guard.getInt("pid", -1), 1);
        if (!exits.isEmpty()) {
          var exit = exits.get(0);
          crashed =
              process.equals(exit.getProcessName())
                  && exit.getTimestamp() >= guard.getLong("wall", Long.MAX_VALUE)
                  && (exit.getReason() == ApplicationExitInfo.REASON_CRASH
                      || exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE);
        }
      }
      var startup =
          StartupGuard.next(
              guard.getInt("crashes", 0),
              guard.getBoolean("blocked", false),
              sameEpoch,
              recent,
              crashed);
      protectedMode = startup.blocked();
      guard
          .edit()
          .putInt("crashes", startup.crashes())
          .putBoolean("blocked", startup.blocked())
          .putInt("pid", android.os.Process.myPid())
          .putLong("start", now)
          .putLong("wall", wall)
          .putLong("boot", boot)
          .putLong("epoch", epoch)
          .apply();
      if (protectedMode) {
        report("protected");
        return;
      }
      profile = PopupDiscovery.load(context, loader, app);
      if (!ModernHook.deoptimize(profile.gate()))
        throw new IllegalStateException("Gate deoptimization unavailable");
      for (var caller : profile.callers())
        if (!ModernHook.deoptimize(caller))
          throw new IllegalStateException("Caller deoptimization unavailable");
      ModernHook.hookMethod(
          profile.gate(),
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              if (p.args[0] == null || p.args[0].getClass() != profile.popup()) return;
              if (failed || protectedMode || !enabled) return;
              try {
                InputMethodService service = ime.get();
                EditorInfo editor = service == null ? null : service.getCurrentInputEditorInfo();
                if (editor != null && "com.android.vending".equals(editor.packageName)) {
                  p.setResult(profile.cancel());
                  report("blocked");
                }
              } catch (Throwable error) {
                failed = true;
                enabled = false;
                report("error");
                log("HOOK_STOP " + error.getClass().getSimpleName());
              }
            }
          });
      installed = true;
      report(enabled ? "ready" : "off");
      log("READY api=102 build=" + BuildConfig.BUILD_TOKEN);
    } catch (Throwable error) {
      failed = true;
      enabled = false;
      report("unsupported");
      log("DISCOVERY_STOP " + error.getClass().getSimpleName() + ": " + error.getMessage());
    } finally {
      initializing = false;
    }
  }

  private boolean refresh() {
    if (context == null) return false;
    try (var cursor =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst())
        throw new IllegalStateException("Configuration unavailable");
      boolean wasEnabled = enabled;
      enabled = !failed && cursor.getInt(0) != 0;
      epoch = cursor.getLong(1);
      InputMethodService service = ime.get();
      if (!wasEnabled && enabled && !protectedMode && profile != null && service != null) {
        var editor = service.getCurrentInputEditorInfo();
        if (editor != null && "com.android.vending".equals(editor.packageName))
          profile.closer().invoke(profile.accessor().invoke(null), profile.popup());
      }
      if (installed || protectedMode)
        report(protectedMode ? "protected" : failed ? "error" : enabled ? "ready" : "off");
      return true;
    } catch (Throwable error) {
      enabled = false;
      report("error");
      log("CONFIG_UNAVAILABLE: open Simeji Clean, then show the keyboard again");
      return false;
    }
  }

  private void report(String state) {
    if (context == null || state.equals(lastStatus)) return;
    try {
      Bundle data = new Bundle();
      data.putString("state", state);
      data.putString("build", BuildConfig.BUILD_TOKEN);
      context.getContentResolver().call(ConfigProvider.URI, "status", null, data);
      lastStatus = state;
      log("STATE " + state);
    } catch (RuntimeException ignored) {
    }
  }

  static void log(String message) {
    ModernHook.log(message);
    android.util.Log.i("SimejiClean", message);
  }
}
