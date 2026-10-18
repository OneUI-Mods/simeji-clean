package net.ryuya_dev.simejiclean;

import android.os.Bundle;
import android.widget.*;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import java.nio.charset.StandardCharsets;

public final class LicensesActivity extends AppCompatActivity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    EdgeToEdge.enable(this);
    ScrollView scroll = new ScrollView(this);
    TextView text = new TextView(this);
    text.setTextIsSelectable(true);
    text.setPadding(32, 32, 32, 32);
    scroll.addView(text);
    setContentView(scroll);
    ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, i) -> {
          var b =
              i.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(b.left, b.top, b.right, b.bottom);
          return i;
        });
    try {
      StringBuilder s = new StringBuilder();
      for (String name : new String[] {"APP_LICENSE.txt", "THIRD_PARTY_NOTICES.txt"})
        try (var in = getAssets().open(name)) {
          java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
          byte[] buffer = new byte[8192];
          int count;
          while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
          s.append(new String(out.toByteArray(), StandardCharsets.UTF_8)).append("\n\n");
        }
      text.setText(s);
    } catch (Exception e) {
      text.setText(e.getClass().getSimpleName());
    }
  }
}
