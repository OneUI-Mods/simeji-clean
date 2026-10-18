package net.ryuya_dev.simejiclean;

/** Restore an active Simeji after force-stop without replacing another chosen keyboard. */
final class ReloadCommand {
  static final String SCRIPT =
      "simeji_clean_ime=$(settings get secure default_input_method) || exit $?; am force-stop"
          + " --user current com.adamrocker.android.input.simeji || exit $?; case"
          + " \"$simeji_clean_ime\" in "
          + "com.adamrocker.android.input.simeji/*)"
          + " ime set \"$simeji_clean_ime\";; esac";

  private ReloadCommand() {}
}
