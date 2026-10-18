package net.ryuya_dev.simejiclean;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.widget.PopupWindow;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;
import org.json.*;

/** APK-directory-keyed discovery cache; class names are results, not built-in hook targets. */
final class PopupDiscovery {
  record Profile(
      Class<?> popup,
      Method gate,
      Method closer,
      Method accessor,
      int cancel,
      List<Executable> callers) {}

  static Profile load(Context context, ClassLoader loader, ApplicationInfo app) throws Exception {
    int layout =
        context
            .getResources()
            .getIdentifier("layout_sug_view_container", "layout", app.packageName);
    if (layout == 0) throw new IllegalStateException("Advertising layout unavailable");
    List<String> apks = new ArrayList<>();
    apks.add(app.sourceDir);
    if (app.splitSourceDirs != null) apks.addAll(Arrays.asList(app.splitSourceDirs));
    String key = key(apks, layout);
    var cache =
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("net.ryuya_dev.simejiclean.discovery", 0);
    if (key.equals(cache.getString("key", ""))) {
      try {
        Profile profile = resolve(decode(new JSONObject(cache.getString("evidence", ""))), loader);
        HookEntry.log("DISCOVERY_CACHE_HIT");
        return profile;
      } catch (Exception error) {
        cache.edit().clear().apply();
        HookEntry.log("DISCOVERY_CACHE_REJECTED");
      }
    }
    List<ClassDef> classes = new ArrayList<>();
    for (String apk : apks) {
      boolean dex;
      try (ZipFile zip = new ZipFile(apk)) {
        dex =
            zip.stream()
                .anyMatch(e -> e.getName().matches("classes(?:[2-9][0-9]*|1[0-9]+)?\\.dex"));
      }
      if (!dex) continue;
      var container = DexFileFactory.loadDexContainer(new File(apk), Opcodes.getDefault());
      for (String entry : container.getDexEntryNames())
        classes.addAll(container.getEntry(entry).getDexFile().getClasses());
    }
    var evidence = DexPopupDiscovery.find(classes, layout);
    Profile profile = resolve(evidence, loader);
    cache.edit().putString("key", key).putString("evidence", encode(evidence).toString()).apply();
    HookEntry.log(
        "DISCOVERY_FOUND popup="
            + evidence.popup()
            + " gate="
            + evidence.gate().owner()
            + "#"
            + evidence.gate().name()
            + " callers="
            + profile.callers().size());
    return profile;
  }

  private static String key(List<String> apks, int layout) throws Exception {
    StringBuilder text = new StringBuilder("schema=1;layout=").append(layout);
    for (String path : apks) {
      File file = new File(path);
      text.append('\n')
          .append(path)
          .append(':')
          .append(file.length())
          .append(':')
          .append(file.lastModified());
      try (ZipFile zip = new ZipFile(file)) {
        var entries =
            zip.stream()
                .filter(e -> e.getName().endsWith(".dex"))
                .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
                .collect(java.util.stream.Collectors.toList());
        for (var e : entries)
          text.append(':')
              .append(e.getName())
              .append(':')
              .append(e.getCrc())
              .append(':')
              .append(e.getSize());
      }
    }
    return android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256")
            .digest(text.toString().getBytes(StandardCharsets.UTF_8)),
        android.util.Base64.NO_WRAP);
  }

  private static Profile resolve(DexPopupDiscovery.Evidence evidence, ClassLoader loader)
      throws Exception {
    Class<?> popup = type(evidence.popup(), loader);
    Method gate = (Method) executable(evidence.gate(), loader),
        closer = (Method) executable(evidence.closer(), loader),
        accessor = (Method) executable(evidence.accessor(), loader);
    if (gate.getReturnType() != int.class
        || gate.getParameterCount() != 1
        || !gate.getParameterTypes()[0].isInterface()
        || !gate.getParameterTypes()[0].isAssignableFrom(popup))
      throw new IllegalStateException("Gate contract changed");
    boolean window = false;
    for (Field field : popup.getDeclaredFields())
      window |= PopupWindow.class.isAssignableFrom(field.getType());
    if (!window
        || !Arrays.equals(closer.getParameterTypes(), new Class<?>[] {Class.class})
        || closer.getReturnType() != void.class
        || !Modifier.isStatic(accessor.getModifiers())
        || accessor.getParameterCount() != 0
        || accessor.getReturnType() != gate.getDeclaringClass()
        || closer.getDeclaringClass() != gate.getDeclaringClass())
      throw new IllegalStateException("Popup manager contract changed");
    Field cancel = gate.getDeclaringClass().getDeclaredField(evidence.cancelField());
    cancel.setAccessible(true);
    if (cancel.getType() != int.class
        || !Modifier.isStatic(cancel.getModifiers())
        || !Modifier.isFinal(cancel.getModifiers())
        || cancel.getInt(null) != 3) throw new IllegalStateException("Native cancellation changed");
    List<Executable> callers = new ArrayList<>();
    for (var signature : evidence.callers()) callers.add(executable(signature, loader));
    if (callers.isEmpty() || callers.size() > 32)
      throw new IllegalStateException("Caller contract changed");
    return new Profile(popup, gate, closer, accessor, 3, List.copyOf(callers));
  }

  private static Executable executable(DexPopupDiscovery.Signature sig, ClassLoader loader)
      throws Exception {
    Class<?> owner = type(sig.owner(), loader);
    Class<?>[] params = new Class<?>[sig.parameters().size()];
    for (int i = 0; i < params.length; i++) params[i] = type(sig.parameters().get(i), loader);
    Executable result;
    if (sig.name().equals("<init>")) result = owner.getDeclaredConstructor(params);
    else {
      Method method = owner.getDeclaredMethod(sig.name(), params);
      if (method.getReturnType() != type(sig.returns(), loader))
        throw new IllegalStateException("Return type changed");
      result = method;
    }
    result.setAccessible(true);
    return result;
  }

  private static Class<?> type(String descriptor, ClassLoader loader)
      throws ClassNotFoundException {
    return switch (descriptor) {
      case "V" -> void.class;
      case "Z" -> boolean.class;
      case "B" -> byte.class;
      case "C" -> char.class;
      case "S" -> short.class;
      case "I" -> int.class;
      case "J" -> long.class;
      case "F" -> float.class;
      case "D" -> double.class;
      default ->
          Class.forName(
              descriptor.startsWith("L")
                  ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                  : descriptor.replace('/', '.'),
              false,
              loader);
    };
  }

  private static JSONObject signature(DexPopupDiscovery.Signature sig) throws JSONException {
    return new JSONObject()
        .put("owner", sig.owner())
        .put("name", sig.name())
        .put("parameters", new JSONArray(sig.parameters()))
        .put("returns", sig.returns());
  }

  private static DexPopupDiscovery.Signature signature(JSONObject object) throws JSONException {
    List<String> parameters = new ArrayList<>();
    JSONArray a = object.getJSONArray("parameters");
    for (int i = 0; i < a.length(); i++) parameters.add(a.getString(i));
    return new DexPopupDiscovery.Signature(
        object.getString("owner"),
        object.getString("name"),
        List.copyOf(parameters),
        object.getString("returns"));
  }

  private static JSONObject encode(DexPopupDiscovery.Evidence e) throws JSONException {
    JSONArray callers = new JSONArray();
    for (var s : e.callers()) callers.put(signature(s));
    return new JSONObject()
        .put("popup", e.popup())
        .put("gate", signature(e.gate()))
        .put("cancel", e.cancelField())
        .put("closer", signature(e.closer()))
        .put("accessor", signature(e.accessor()))
        .put("callers", callers);
  }

  private static DexPopupDiscovery.Evidence decode(JSONObject o) throws JSONException {
    List<DexPopupDiscovery.Signature> callers = new ArrayList<>();
    JSONArray a = o.getJSONArray("callers");
    for (int i = 0; i < a.length(); i++) callers.add(signature(a.getJSONObject(i)));
    return new DexPopupDiscovery.Evidence(
        o.getString("popup"),
        signature(o.getJSONObject("gate")),
        o.getString("cancel"),
        signature(o.getJSONObject("closer")),
        signature(o.getJSONObject("accessor")),
        List.copyOf(callers));
  }

  private PopupDiscovery() {}
}
