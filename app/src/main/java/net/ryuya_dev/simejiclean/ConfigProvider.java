package net.ryuya_dev.simejiclean;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;

public final class ConfigProvider extends ContentProvider {
  public static final Uri URI = Uri.parse("content://net.ryuya_dev.simejiclean.config");
  static final Uri STATUS_URI = Uri.withAppendedPath(URI, "status");
  static final String HOST = "com.adamrocker.android.input.simeji";

  @Override
  public boolean onCreate() {
    return true;
  }

  private void authorize() {
    int caller = Binder.getCallingUid();
    if (caller == android.os.Process.myUid()) return;
    try {
      if (caller == getContext().getPackageManager().getApplicationInfo(HOST, 0).uid) return;
    } catch (Exception ignored) {
    }
    throw new SecurityException("Simeji or module app only");
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
    authorize();
    var prefs = getContext().getSharedPreferences("config", 0);
    MatrixCursor c = new MatrixCursor(new String[] {"enabled", "epoch"});
    c.addRow(new Object[] {prefs.getBoolean("enabled", true) ? 1 : 0, prefs.getLong("epoch", 0)});
    return c;
  }

  @Override
  public Bundle call(String method, String arg, Bundle data) {
    authorize();
    if (!"status".equals(method) || data == null)
      throw new IllegalArgumentException("Unknown method");
    var p = getContext().getSharedPreferences("status", 0);
    String value = data.getString("state", "error");
    if (!java.util.Set.of("ready", "blocked", "off", "unsupported", "error", "protected")
        .contains(value)) throw new IllegalArgumentException("Unknown status");
    p.edit()
        .putString("state", value)
        .putString("build", data.getString("build", ""))
        .putLong("boot", System.currentTimeMillis() - SystemClock.elapsedRealtime())
        .putLong("time", SystemClock.elapsedRealtime())
        .putInt("pid", Binder.getCallingPid())
        .apply();
    long identity = Binder.clearCallingIdentity();
    try {
      getContext().getContentResolver().notifyChange(STATUS_URI, null);
    } finally {
      Binder.restoreCallingIdentity(identity);
    }
    return Bundle.EMPTY;
  }

  @Override
  public String getType(Uri u) {
    return "vnd.android.cursor.item/vnd.simejiclean.config";
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
