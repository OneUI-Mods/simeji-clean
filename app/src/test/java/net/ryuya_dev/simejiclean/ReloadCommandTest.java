package net.ryuya_dev.simejiclean;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ReloadCommandTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private record Result(int code, String commands) {}

  private Result run(String keyboard, int settingsStatus, int stopStatus, int imeStatus)
      throws Exception {
    Path folder = temp.newFolder().toPath(), log = folder.resolve("commands");
    String[][] scripts = {
      {"settings", "printf '%s\\n' \"$TEST_KEYBOARD\"; exit " + settingsStatus},
      {"am", "printf 'am %s\\n' \"$*\" >> \"$TEST_LOG\"; exit " + stopStatus},
      {"ime", "printf 'ime %s\\n' \"$*\" >> \"$TEST_LOG\"; exit " + imeStatus}
    };
    for (String[] script : scripts) {
      Path p = folder.resolve(script[0]);
      Files.write(
          p, ("#!/bin/sh\n" + script[1] + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertTrue(p.toFile().setExecutable(true));
    }
    ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", ReloadCommand.SCRIPT);
    builder.environment().put("PATH", folder.toString());
    builder.environment().put("TEST_KEYBOARD", keyboard);
    builder.environment().put("TEST_LOG", log.toString());
    Process process = builder.start();
    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    return new Result(
        process.exitValue(),
        Files.exists(log)
            ? new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8)
            : "");
  }

  @Test
  public void anotherKeyboardIsNeverSelectedOver() throws Exception {
    var r = run("other.keyboard/.Ime", 0, 0, 0);
    assertEquals(0, r.code);
    assertFalse(r.commands.contains("ime set"));
    assertTrue(
        r.commands.contains("am force-stop --user current com.adamrocker.android.input.simeji"));
  }

  @Test
  public void activeSimejiIsRestored() throws Exception {
    var r = run("com.adamrocker.android.input.simeji/.OpenWnnSimeji", 0, 0, 0);
    assertEquals(0, r.code);
    assertTrue(r.commands.contains("ime set com.adamrocker.android.input.simeji/.OpenWnnSimeji"));
  }

  @Test
  public void fullComponentIsRestored() throws Exception {
    var r =
        run(
            "com.adamrocker.android.input.simeji/com.adamrocker.android.input.simeji.OpenWnnSimeji",
            0,
            0,
            0);
    assertTrue(r.commands.contains("ime set"));
  }

  @Test
  public void failedStopDoesNotReportSuccessOrChangeIme() throws Exception {
    var r = run("com.adamrocker.android.input.simeji/.OpenWnnSimeji", 0, 7, 0);
    assertEquals(7, r.code);
    assertFalse(r.commands.contains("ime set"));
  }

  @Test
  public void failedSettingsReadDoesNotStopKeyboard() throws Exception {
    var r = run("com.adamrocker.android.input.simeji/.OpenWnnSimeji", 9, 0, 0);
    assertEquals(9, r.code);
    assertEquals("", r.commands);
  }

  @Test
  public void failedRestoreIsReported() throws Exception {
    assertEquals(8, run("com.adamrocker.android.input.simeji/.OpenWnnSimeji", 0, 0, 8).code);
  }

  @Test
  public void updatedSimejiServiceIsRestored() throws Exception {
    var r = run("com.adamrocker.android.input.simeji/.Unknown", 0, 0, 0);
    assertTrue(r.commands.contains("ime set"));
  }
}
