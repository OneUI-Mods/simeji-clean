package net.ryuya_dev.simejiclean;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.view.inputmethod.EditorInfo;
import io.github.libxposed.api.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;

public final class HookEntry extends XposedModule {
  private Context context;
  private volatile boolean enabled, failed, protectedMode;
  private boolean installed;
  private long epoch;
  private WeakReference<InputMethodService> ime = new WeakReference<>(null);
  private final Handler main = new Handler(Looper.getMainLooper());
  private Class<?> popupType, managerType, serviceType;
  private String process;

  @Override
  public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
    ModernHook.attach(this);
    process = p.getProcessName();
  }

  @Override
  public void onPackageReady(XposedModuleInterface.PackageReadyParam p) {
    if (!p.isFirstPackage()
        || !ConfigProvider.HOST.equals(p.getPackageName())
        || !ConfigProvider.HOST.equals(process)) return;
    ModernHook.findAndHookMethod(
        Application.class,
        "attach",
        Context.class,
        new ModernHook.Callback() {
          @Override
          protected void afterHookedMethod(ModernHook.Param call) {
            if (context == null)
              initialize(
                  (Context) call.args[0], p.getClassLoader(), p.getApplicationInfo().sourceDir);
          }
        });
  }

  private void initialize(Context c, ClassLoader loader, String apk) {
    context = c;
    try {
      if (!refresh()) return;
      var guard =
          c.createDeviceProtectedStorageContext()
              .getSharedPreferences("net.ryuya_dev.simejiclean.guard", 0);
      long now = SystemClock.elapsedRealtime();
      long wall = System.currentTimeMillis(), boot = wall - now;
      boolean sameEpoch = guard.getLong("epoch", -1) == epoch;
      boolean recent =
          StartupGuard.recent(guard.getLong("boot", 0), boot, guard.getLong("start", -1), now);
      boolean crashed = false;
      if (sameEpoch && recent && !guard.getBoolean("blocked", false)) {
        var exits =
            c.getSystemService(ActivityManager.class)
                .getHistoricalProcessExitReasons(null, guard.getInt("pid", -1), 1);
        if (!exits.isEmpty()) {
          var exit = exits.get(0);
          crashed =
              ConfigProvider.HOST.equals(exit.getProcessName())
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
      c.getContentResolver()
          .registerContentObserver(
              ConfigProvider.URI,
              false,
              new ContentObserver(main) {
                @Override
                public void onChange(boolean self) {
                  refresh();
                }
              });
      if (protectedMode) {
        report("protected");
        return;
      }
      serviceType =
          Class.forName("com.adamrocker.android.input.simeji.OpenWnnSimeji", false, loader);
      if (!InputMethodService.class.isAssignableFrom(serviceType))
        throw new IllegalStateException("IME changed");
      popupType = Class.forName("jp.baidu.simeji.ad.sug.pop.SugPopup", false, loader);
      Class<?> contract = Class.forName("jp.baidu.simeji.ad.sug.SugContract$View", false, loader);
      Class<?> popup = Class.forName("jp.baidu.simejicore.popup.IPopup", false, loader);
      managerType = Class.forName("jp.baidu.simejicore.popup.PopupManager", false, loader);
      if (!contract.isAssignableFrom(popupType) || !popup.isAssignableFrom(popupType))
        throw new IllegalStateException("Popup contract changed");
      Method check = managerType.getMethod("checkPopup", popup);
      int cancel = managerType.getField("FLAG_CANCEL").getInt(null);
      if (check.getReturnType() != int.class || cancel != 3)
        throw new IllegalStateException("Cancel contract changed");
      managerType.getMethod("closePopup", Class.class);
      Method caller =
          Class.forName("jp.baidu.simejicore.event.EventManager", false, loader)
              .getMethod("popup", popup);
      verifyCaller(apk, check, caller);
      ModernHook.findAndHookMethod(
          InputMethodService.class,
          "onCreate",
          new ModernHook.Callback() {
            @Override
            protected void afterHookedMethod(ModernHook.Param p) {
              if (serviceType.isInstance(p.thisObject))
                ime = new WeakReference<>((InputMethodService) p.thisObject);
            }
          });
      Method inputStart =
          serviceType.getMethod("onStartInputView", EditorInfo.class, boolean.class);
      if (!ModernHook.deoptimize(inputStart))
        throw new IllegalStateException("IME callback changed");
      ModernHook.hookMethod(
          inputStart,
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              ime = new WeakReference<>((InputMethodService) p.thisObject);
              refresh();
            }
          });
      ModernHook.hookMethod(
          check,
          new ModernHook.Callback() {
            @Override
            protected void beforeHookedMethod(ModernHook.Param p) {
              if (failed
                  || protectedMode
                  || !enabled
                  || p.args[0] == null
                  || p.args[0].getClass() != popupType) return;
              try {
                InputMethodService service = ime.get();
                EditorInfo editor = service == null ? null : service.getCurrentInputEditorInfo();
                if (editor != null && "com.android.vending".equals(editor.packageName)) {
                  p.setResult(cancel);
                  report("blocked");
                }
              } catch (Throwable error) {
                failed = true;
                enabled = false;
                report("error");
                log("STOP " + error.getClass().getSimpleName());
              }
            }
          });
      installed = true;
      report(enabled ? "ready" : "off");
      log("READY api=102; checked popup contract and DEX caller");
    } catch (Throwable error) {
      failed = true;
      enabled = false;
      report("unsupported");
      log("UNSUPPORTED " + error.getClass().getSimpleName());
      android.util.Log.e("SimejiClean", "Initialization failed", error);
    }
  }

  private void verifyCaller(String apk, Method check, Method caller) throws Exception {
    String owner = "L" + caller.getDeclaringClass().getName().replace('.', '/') + ";";
    String target = "L" + check.getDeclaringClass().getName().replace('.', '/') + ";";
    boolean found = false;
    var dex = DexFileFactory.loadDexContainer(new java.io.File(apk), Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (var type : dex.getEntry(entry).getDexFile().getClasses()) {
        if (!owner.equals(type.getType())) continue;
        for (var method : type.getMethods())
          if (method.getName().equals(caller.getName()) && method.getImplementation() != null)
            for (var instruction : method.getImplementation().getInstructions())
              if (instruction instanceof ReferenceInstruction r
                  && r.getReference() instanceof MethodReference ref
                  && target.equals(ref.getDefiningClass())
                  && check.getName().equals(ref.getName())) found = true;
      }
    if (!found || !ModernHook.deoptimize(check) || !ModernHook.deoptimize(caller))
      throw new IllegalStateException("Popup call path changed");
  }

  private boolean refresh() {
    try (var cursor =
        context.getContentResolver().query(ConfigProvider.URI, null, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst())
        throw new IllegalStateException("Configuration unavailable");
      enabled = !failed && cursor.getInt(0) != 0;
      epoch = cursor.getLong(1);
      if (ime.get() != null && enabled && !protectedMode) {
        var editor = ime.get().getCurrentInputEditorInfo();
        if (editor != null
            && "com.android.vending".equals(editor.packageName)
            && managerType != null)
          managerType
              .getMethod("closePopup", Class.class)
              .invoke(managerType.getMethod("getInstance").invoke(null), popupType);
      }
      if (installed || protectedMode)
        report(protectedMode ? "protected" : failed ? "error" : enabled ? "ready" : "off");
      return true;
    } catch (Throwable error) {
      enabled = false;
      report("error");
      log("CONFIG_UNAVAILABLE: open Simeji Clean, then reload Simeji");
      return false;
    }
  }

  private String lastStatus = "";

  private void report(String state) {
    if (context == null || state.equals(lastStatus)) return;
    try {
      Bundle data = new Bundle();
      data.putString("state", state);
      context.getContentResolver().call(ConfigProvider.URI, "status", null, data);
      lastStatus = state;
      log("STATE " + state);
    } catch (RuntimeException ignored) {
    }
  }

  static void log(String text) {
    ModernHook.log(text);
    android.util.Log.i("SimejiClean", text);
  }
}
