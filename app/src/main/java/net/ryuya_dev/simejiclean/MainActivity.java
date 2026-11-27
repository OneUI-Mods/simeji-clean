package net.ryuya_dev.simejiclean;

import android.content.*;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TextView status, root, detail;
  private ImageView badge;
  private MaterialButton reload;
  private MaterialSwitch toggle;
  private SimejiCleanApplication application;
  private final SharedPreferences.OnSharedPreferenceChangeListener configChanges =
      (preferences, key) -> {
        if ("enabled".equals(key)) refreshToggle();
      };
  private final ContentObserver observer =
      new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean self) {
          updateStatus();
        }
      };

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle state) {
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(state);
    application = (SimejiCleanApplication) getApplication();
    EdgeToEdge.enable(this);
    ScrollView scroll = new ScrollView(this);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(22), dp(18), dp(22), dp(24));
    scroll.addView(page);
    setContentView(scroll);
    ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, insets) -> {
          var a =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(a.left, a.top, a.right, a.bottom);
          return insets;
        });
    TextView title = text(R.string.app_name, 32);
    title.setTypeface(null, Typeface.BOLD);
    page.addView(title);
    page.addView(text(R.string.description, 15));
    MaterialCardView card = new MaterialCardView(this);
    card.setRadius(dp(26));
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(24), dp(24), dp(24), dp(24));
    card.addView(box);
    LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
    margins.setMargins(0, dp(28), 0, dp(24));
    page.addView(card, margins);
    badge = new ImageView(this);
    badge.setImageResource(android.R.drawable.ic_dialog_info);
    box.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
    status = text(R.string.pending, 21);
    box.addView(status);
    detail = text(R.string.pending_note, 14);
    box.addView(detail);
    toggle = new MaterialSwitch(this);
    toggle.setText(R.string.enabled);
    toggle.setChecked(getSharedPreferences("config", 0).getBoolean("enabled", true));
    page.addView(toggle, new LinearLayout.LayoutParams(-1, dp(72)));
    page.addView(text(R.string.scope, 15));
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          ConfigSnapshot config = ConfigSnapshot.local(application.localConfig());
          if (config.enabled() == on) return;
          if (!application.saveConfig(new ConfigSnapshot(on, config.epoch()))) {
            refreshToggle();
            Toast.makeText(this, R.string.config_save_failed, Toast.LENGTH_LONG).show();
          } else {
            updateStatus();
          }
        });
    root = text(R.string.root_request, 14);
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.setMargins(0, dp(24), 0, dp(12));
    page.addView(root, rp);
    reload = button(R.string.reload, page);
    reload.setEnabled(false);
    reload.setOnClickListener(
        v ->
            new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reload_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reload, (d, w) -> runRoot(ReloadCommand.SCRIPT, true))
                .show());
    button(R.string.reset, page)
        .setOnClickListener(
            v -> {
              var current = ConfigSnapshot.local(application.localConfig());
              boolean saved = application.saveConfig(
                  new ConfigSnapshot(current.enabled(), System.currentTimeMillis()));
              Toast.makeText(this, saved ? R.string.reset_done : R.string.config_save_failed,
                  Toast.LENGTH_LONG).show();
            });
    if (Build.VERSION.SDK_INT >= 33)
      button(R.string.language, page)
          .setOnClickListener(
              v -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APP_LOCALE_SETTINGS,
                          Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ignored) {
                }
              });
    button(R.string.licenses, page)
        .setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    page.addView(text(R.string.privacy, 13));
    runRoot("id", false);
    updateStatus();
  }

  private TextView text(int id, int sp) {
    TextView t = new TextView(this);
    t.setText(id);
    t.setTextSize(sp);
    t.setPadding(0, dp(8), 0, dp(8));
    return t;
  }

  private MaterialButton button(int id, LinearLayout parent) {
    MaterialButton b = new MaterialButton(this);
    b.setText(id);
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    return b;
  }

  @Override
  protected void onResume() {
    super.onResume();
    getContentResolver().registerContentObserver(ConfigProvider.STATUS_URI, false, observer);
    application.localConfig().registerOnSharedPreferenceChangeListener(configChanges);
    refreshToggle();
  }

  @Override
  protected void onPause() {
    application.localConfig().unregisterOnSharedPreferenceChangeListener(configChanges);
    getContentResolver().unregisterContentObserver(observer);
    super.onPause();
  }

  private void refreshToggle() {
    if (toggle == null || isDestroyed()) return;
    toggle.setChecked(ConfigSnapshot.local(application.localConfig()).enabled());
    updateStatus();
  }

  private void updateStatus() {
    if (status == null || isDestroyed()) return;
    boolean on = getSharedPreferences("config", 0).getBoolean("enabled", true);
    var p = getSharedPreferences("status", 0);
    long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
    String state =
        Math.abs(p.getLong("boot", 0) - boot) < 10000
                && BuildConfig.BUILD_TOKEN.equals(p.getString("build", ""))
            ? p.getString("state", "pending")
            : "pending";
    if (!on) state = "off";
    int id =
        switch (state) {
          case "ready" -> R.string.ready;
          case "blocked" -> R.string.blocked;
          case "unsupported" -> R.string.unsupported;
          case "protected" -> R.string.guarded;
          case "off" -> R.string.off;
          case "error" -> R.string.error;
          default -> R.string.pending;
        };
    status.setText(id);
    boolean ok = state.equals("ready") || state.equals("blocked");
    detail.setText(ok ? R.string.scope : R.string.pending_note);
    badge.setImageResource(
        ok ? android.R.drawable.checkbox_on_background : android.R.drawable.ic_dialog_alert);
    badge.setColorFilter(ok ? Color.rgb(73, 103, 59) : Color.rgb(155, 105, 45));
  }

  private void runRoot(String command, boolean restart) {
    worker.submit(
        () -> {
          boolean ok = false;
          java.lang.Process process = null;
          try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (process.waitFor(12, TimeUnit.SECONDS)) ok = process.exitValue() == 0;
          } catch (Exception ignored) {
          } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
          }
          boolean result = ok;
          runOnUiThread(
              () -> {
                if (isDestroyed()) return;
                if (restart)
                  Toast.makeText(this, result ? R.string.done : R.string.failed, Toast.LENGTH_LONG)
                      .show();
                else {
                  root.setText(result ? R.string.root_ok : R.string.root_no);
                  reload.setEnabled(result);
                }
              });
        });
  }

  @Override
  protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
