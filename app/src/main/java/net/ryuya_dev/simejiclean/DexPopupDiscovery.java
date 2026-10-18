package net.ryuya_dev.simejiclean;

import java.lang.reflect.Modifier;
import java.util.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.iface.value.IntEncodedValue;

/**
 * Finds the one advertising popup and its native cancellation gate from independent DEX anchors.
 */
final class DexPopupDiscovery {
  record Signature(String owner, String name, List<String> parameters, String returns) {
    static Signature of(MethodReference method) {
      List<String> parameters = new ArrayList<>();
      for (CharSequence p : method.getParameterTypes()) parameters.add(p.toString());
      return new Signature(
          method.getDefiningClass(),
          method.getName(),
          List.copyOf(parameters),
          method.getReturnType());
    }
  }

  record Evidence(
      String popup,
      Signature gate,
      String cancelField,
      Signature closer,
      Signature accessor,
      List<Signature> callers) {}

  static Evidence find(Collection<? extends ClassDef> classes, int layout) {
    if (layout == 0) throw new IllegalStateException("Advertising layout unavailable");
    Map<String, ClassDef> types = new HashMap<>();
    List<ClassDef> popups = new ArrayList<>(), managers = new ArrayList<>();
    for (ClassDef type : classes) {
      if (types.put(type.getType(), type) != null)
        throw new IllegalStateException("Duplicate DEX class");
      boolean list = false, stack = false;
      for (Field field : type.getFields()) {
        list |= field.getType().equals("Landroid/widget/ListView;");
        stack |= field.getType().equals("Ljava/util/Stack;");
      }
      if (stack) managers.add(type);
      if (!list) continue;
      boolean matched = false;
      for (Method method : type.getMethods()) {
        if (!method.getReturnType().equals("Z")
            || !method.getParameterTypes().isEmpty()
            || method.getImplementation() == null) continue;
        boolean resource = false, inflate = false, show = false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
          if (instruction instanceof NarrowLiteralInstruction literal
              && literal.getNarrowLiteral() == layout) resource = true;
          if (instruction instanceof ReferenceInstruction r
              && r.getReference() instanceof MethodReference ref) {
            inflate |=
                ref.getDefiningClass().equals("Landroid/view/LayoutInflater;")
                    && ref.getName().equals("inflate");
            show |=
                ref.getName().equals("showAtLocation")
                    && Signature.of(ref)
                        .parameters()
                        .equals(List.of("Landroid/view/View;", "I", "I", "I"))
                    && ref.getReturnType().equals("V");
          }
        }
        matched |= resource && inflate && show;
      }
      if (matched) popups.add(type);
    }
    ClassDef popup = one(popups, "advertising popup");
    List<Map.Entry<ClassDef, Method>> gates = new ArrayList<>();
    for (ClassDef type : managers)
      for (Method method : type.getMethods()) {
        if (!method.getReturnType().equals("I")
            || method.getParameterTypes().size() != 1
            || method.getImplementation() == null) continue;
        String parameter = method.getParameterTypes().get(0).toString();
        ClassDef contract = types.get(parameter);
        if (contract == null
            || !Modifier.isInterface(contract.getAccessFlags())
            || !assignable(types, popup.getType(), parameter)) continue;
        Set<String> strings = strings(method);
        if (strings.contains("mPopups empty.") && strings.contains("priority FLAG_CANCEL."))
          gates.add(Map.entry(type, method));
      }
    var selected = one(gates, "cancellation gate");
    ClassDef manager = selected.getKey();
    Method gate = selected.getValue();
    String contract = gate.getParameterTypes().get(0).toString();
    List<String> cancelFields = new ArrayList<>();
    for (Field field : manager.getStaticFields())
      if (Modifier.isFinal(field.getAccessFlags())
          && field.getType().equals("I")
          && field.getInitialValue() instanceof IntEncodedValue value
          && value.getValue() == 3) cancelFields.add(field.getName());
    String cancel = one(cancelFields, "cancel constant");
    List<Signature> closers = new ArrayList<>(), accessors = new ArrayList<>();
    for (Method method : manager.getMethods()) {
      Signature sig = Signature.of(method);
      if (Modifier.isStatic(method.getAccessFlags())
          && sig.parameters().isEmpty()
          && sig.returns().equals(manager.getType())) accessors.add(sig);
      if (!sig.parameters().equals(List.of("Ljava/lang/Class;"))
          || !sig.returns().equals("V")
          || method.getImplementation() == null) continue;
      boolean close = false, remove = false;
      for (Instruction instruction : method.getImplementation().getInstructions())
        if (instruction instanceof ReferenceInstruction r
            && r.getReference() instanceof MethodReference ref) {
          close |=
              ref.getDefiningClass().equals(contract)
                  && ref.getParameterTypes().isEmpty()
                  && ref.getReturnType().equals("V");
          remove |=
              Set.of(
                          "Ljava/util/Stack;",
                          "Ljava/util/Vector;",
                          "Ljava/util/AbstractCollection;",
                          "Ljava/util/AbstractList;",
                          "Ljava/util/List;",
                          "Ljava/util/Collection;")
                      .contains(ref.getDefiningClass())
                  && ref.getName().equals("remove");
        }
      if (close && remove) closers.add(sig);
    }
    Signature key = Signature.of(gate);
    Set<Signature> callers = new LinkedHashSet<>();
    for (ClassDef type : classes)
      for (Method method : type.getMethods()) {
        if (method.getImplementation() == null) continue;
        for (Instruction instruction : method.getImplementation().getInstructions())
          if (instruction instanceof ReferenceInstruction r
              && r.getReference() instanceof MethodReference ref
              && ref.getDefiningClass().equals(key.owner())
              && ref.getName().equals(key.name())
              && Signature.of(ref).equals(key)) {
            callers.add(Signature.of(method));
            break;
          }
      }
    if (callers.isEmpty() || callers.size() > 32)
      throw new IllegalStateException("Ambiguous gate callers");
    return new Evidence(
        popup.getType(),
        key,
        cancel,
        one(closers, "popup closer"),
        one(accessors, "manager accessor"),
        List.copyOf(callers));
  }

  private static Set<String> strings(Method method) {
    Set<String> strings = new HashSet<>();
    for (Instruction instruction : method.getImplementation().getInstructions())
      if (instruction instanceof ReferenceInstruction r
          && r.getReference() instanceof StringReference ref) strings.add(ref.getString());
    return strings;
  }

  private static boolean assignable(Map<String, ClassDef> types, String child, String target) {
    Set<String> seen = new HashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    pending.add(child);
    while (!pending.isEmpty() && seen.size() <= 128) {
      String current = pending.remove();
      if (current.equals(target)) return true;
      if (!seen.add(current)) continue;
      ClassDef type = types.get(current);
      if (type == null) continue;
      if (type.getSuperclass() != null) pending.add(type.getSuperclass());
      pending.addAll(type.getInterfaces());
    }
    return false;
  }

  private static <T> T one(List<T> candidates, String label) {
    if (candidates.size() != 1)
      throw new IllegalStateException(label + " candidates=" + candidates.size());
    return candidates.get(0);
  }

  private DexPopupDiscovery() {}
}
