package net.ryuya_dev.simejiclean;

import static org.junit.Assert.*;

import java.lang.reflect.Modifier;
import java.util.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.immutable.value.ImmutableIntEncodedValue;
import org.junit.Test;

public class DexPopupDiscoveryTest {
  private static final int LAYOUT = 0x7f0d1234;
  private static final String CONTRACT = "Lobfuscated/c;",
      POPUP = "Lobfuscated/p;",
      MANAGER = "Lobfuscated/m;",
      CALLER = "Lobfuscated/e;";

  private static Instruction call(String owner, String name, List<String> params, String ret) {
    return new ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        0,
        0,
        0,
        0,
        0,
        new ImmutableMethodReference(owner, name, params, ret));
  }

  private static Instruction text(String value) {
    return new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(value));
  }

  private static Method method(
      String owner, String name, List<String> params, String ret, int access, Instruction... code) {
    List<ImmutableMethodParameter> parameters = new ArrayList<>();
    for (String p : params) parameters.add(new ImmutableMethodParameter(p, Set.of(), null));
    List<Instruction> body = new ArrayList<>(Arrays.asList(code));
    if (ret.equals("V")) body.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
    else {
      body.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 3));
      body.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
    }
    return new ImmutableMethod(
        owner,
        name,
        parameters,
        ret,
        access,
        Set.of(),
        Set.of(),
        new ImmutableMethodImplementation(8, body, List.of(), List.of()));
  }

  private static Field field(String owner, String name, String type, int access, Integer initial) {
    return new ImmutableField(
        owner,
        name,
        type,
        access,
        initial == null ? null : new ImmutableIntEncodedValue(initial),
        Set.of(),
        Set.of());
  }

  private static ClassDef type(
      String owner,
      String parent,
      List<String> interfaces,
      int access,
      List<Field> fields,
      List<Method> methods) {
    return new ImmutableClassDef(
        owner, access, parent, interfaces, null, Set.of(), fields, methods);
  }

  private static ClassDef popup(String name, int layout, boolean window) {
    List<Instruction> instructions = new ArrayList<>();
    instructions.add(new ImmutableInstruction31i(Opcode.CONST, 0, layout));
    instructions.add(
        call(
            "Landroid/view/LayoutInflater;",
            "inflate",
            List.of("I", "Landroid/view/ViewGroup;"),
            "Landroid/view/View;"));
    if (window)
      instructions.add(
          call(
              "Lrenamed/window;",
              "showAtLocation",
              List.of("Landroid/view/View;", "I", "I", "I"),
              "V"));
    return type(
        name,
        "Ljava/lang/Object;",
        List.of(CONTRACT),
        Modifier.PUBLIC,
        List.of(field(name, "renamedList", "Landroid/widget/ListView;", 0, null)),
        List.of(
            method(
                name,
                "renamedShow",
                List.of(),
                "Z",
                Modifier.PUBLIC,
                instructions.toArray(new Instruction[0]))));
  }

  private static ClassDef manager(boolean marker, boolean extraCloser) {
    Method gate =
        method(
            MANAGER,
            "renamedGate",
            List.of(CONTRACT),
            "I",
            Modifier.PUBLIC,
            text("mPopups empty."),
            text(marker ? "priority FLAG_CANCEL." : "different behavior"));
    Method close =
        method(
            MANAGER,
            "renamedClose",
            List.of("Ljava/lang/Class;"),
            "V",
            Modifier.PUBLIC,
            call(CONTRACT, "renamedCloseWindow", List.of(), "V"),
            call("Ljava/util/AbstractCollection;", "remove", List.of("Ljava/lang/Object;"), "Z"));
    List<Method> methods =
        new ArrayList<>(
            List.of(
                gate,
                close,
                method(
                    MANAGER,
                    "renamedInstance",
                    List.of(),
                    MANAGER,
                    Modifier.PUBLIC | Modifier.STATIC)));
    if (extraCloser)
      methods.add(
          method(
              MANAGER,
              "secondClose",
              List.of("Ljava/lang/Class;"),
              "V",
              Modifier.PUBLIC,
              call(CONTRACT, "renamedCloseWindow", List.of(), "V"),
              call("Ljava/util/Vector;", "remove", List.of("Ljava/lang/Object;"), "Z")));
    return type(
        MANAGER,
        "Ljava/lang/Object;",
        List.of(),
        Modifier.PUBLIC,
        List.of(
            field(MANAGER, "renamedStack", "Ljava/util/Stack;", 0, null),
            field(
                MANAGER,
                "renamedCancel",
                "I",
                Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL,
                3)),
        methods);
  }

  private static List<ClassDef> fixture(int layout) {
    return new ArrayList<>(
        List.of(
            type(
                CONTRACT,
                "Ljava/lang/Object;",
                List.of(),
                Modifier.PUBLIC | Modifier.INTERFACE | Modifier.ABSTRACT,
                List.of(),
                List.of()),
            popup(POPUP, layout, true),
            manager(true, false),
            type(
                CALLER,
                "Ljava/lang/Object;",
                List.of(),
                Modifier.PUBLIC,
                List.of(),
                List.of(
                    method(
                        CALLER,
                        "renamedDispatch",
                        List.of(CONTRACT),
                        "V",
                        Modifier.PUBLIC,
                        call(MANAGER, "renamedGate", List.of(CONTRACT), "I"))))));
  }

  @Test
  public void renamedClassesMethodsAndFieldsAreFound() {
    var e = DexPopupDiscovery.find(fixture(LAYOUT), LAYOUT);
    assertEquals(POPUP, e.popup());
    assertEquals("renamedGate", e.gate().name());
    assertEquals("renamedClose", e.closer().name());
    assertEquals("renamedInstance", e.accessor().name());
    assertEquals("renamedCancel", e.cancelField());
    assertEquals("renamedDispatch", e.callers().get(0).name());
  }

  @Test
  public void changedResourceIdIsResolvedWithoutPinningNumericId() {
    assertEquals(POPUP, DexPopupDiscovery.find(fixture(0x7f0d5678), 0x7f0d5678).popup());
  }

  @Test
  public void normalCandidateListIsNotMatched() {
    var types = fixture(LAYOUT);
    types.add(popup("Lordinary/list;", LAYOUT, false));
    assertEquals(POPUP, DexPopupDiscovery.find(types, LAYOUT).popup());
  }

  @Test
  public void inheritedPopupContractIsFollowed() {
    var types = fixture(LAYOUT);
    var old = types.get(1);
    types.set(
        1,
        type(
            POPUP,
            "Lobfuscated/base;",
            List.of(),
            Modifier.PUBLIC,
            toList(old.getFields()),
            toList(old.getMethods())));
    types.add(
        type(
            "Lobfuscated/base;",
            "Ljava/lang/Object;",
            List.of(CONTRACT),
            Modifier.PUBLIC,
            List.of(),
            List.of()));
    assertEquals(POPUP, DexPopupDiscovery.find(types, LAYOUT).popup());
  }

  @Test
  public void ambiguousPopupStopsDiscovery() {
    var types = fixture(LAYOUT);
    types.add(popup("Lsecond/popup;", LAYOUT, true));
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(types, LAYOUT));
  }

  @Test
  public void removedCancellationAnchorStopsDiscovery() {
    var types = fixture(LAYOUT);
    types.set(2, manager(false, false));
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(types, LAYOUT));
  }

  @Test
  public void ambiguousCloserStopsDiscovery() {
    var types = fixture(LAYOUT);
    types.set(2, manager(true, true));
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(types, LAYOUT));
  }

  @Test
  public void missingCallerStopsDiscovery() {
    var types = fixture(LAYOUT);
    types.remove(3);
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(types, LAYOUT));
  }

  @Test
  public void unknownLayoutStopsDiscovery() {
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(fixture(LAYOUT), 0));
    assertThrows(
        IllegalStateException.class, () -> DexPopupDiscovery.find(fixture(LAYOUT), LAYOUT + 1));
  }

  @Test
  public void duplicateDexClassStopsDiscovery() {
    var types = fixture(LAYOUT);
    types.add(types.get(0));
    assertThrows(IllegalStateException.class, () -> DexPopupDiscovery.find(types, LAYOUT));
  }

  private static <T> List<T> toList(Iterable<? extends T> items) {
    List<T> out = new ArrayList<>();
    for (T item : items) out.add(item);
    return out;
  }
}
