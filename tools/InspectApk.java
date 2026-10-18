package net.ryuya_dev.simejiclean;

import java.io.File;
import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.ClassDef;

/** Offline inspection. Supply APK paths and the current advertising layout resource ID. */
public final class InspectApk {
  public static void main(String[] args) throws Exception {
    if (args.length < 2)
      throw new IllegalArgumentException(
          "Usage: InspectApk layout-resource-id apk [split-apk ...]");
    List<ClassDef> types = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      var container = DexFileFactory.loadDexContainer(new File(args[i]), Opcodes.getDefault());
      for (String entry : container.getDexEntryNames())
        types.addAll(container.getEntry(entry).getDexFile().getClasses());
    }
    long start = System.nanoTime();
    System.out.println(DexPopupDiscovery.find(types, Integer.decode(args[0])));
    System.out.println(
        "Discovery took "
            + (System.nanoTime() - start) / 1_000_000
            + " ms; classes="
            + types.size());
  }
}
