/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.config;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Derives, from compiled bytecode, the types the frontend can store in the HTTP session
 * (REQ-FE-027): the static type of every value handed to a session sink and the closure of the
 * application types reachable from them.
 *
 * <p>A sink is {@code RedirectAttributes.addFlashAttribute} / {@code addAllFlashAttributes}, a
 * {@code FlashMap} write, {@code HttpSession.setAttribute} and {@code
 * WebUtils.setSessionAttribute}. The value's type is read from the instruction that pushes it: a
 * constant, a local variable's declared (generic) type, a method's generic return type, a field's
 * generic type, a cast or a constructor. Anything else — a merge of two branches, an {@code
 * Object}, a type variable, a raw container, an abstract application type — is reported as
 * unresolved, so a new sink can never be skipped silently.
 */
final class SessionBoundTypeScan {

  /** Package prefix of the application's own types. */
  static final String APPLICATION_PACKAGE = "de.greluc.krt.profit.basetool.";

  /** Types whose runtime class a static type does not determine. */
  private static final Set<String> TOO_BROAD =
      Set.of(
          "java.lang.Object",
          "java.io.Serializable",
          "java.lang.Comparable",
          "java.lang.CharSequence",
          "java.lang.Number",
          "java.lang.Cloneable",
          "java.lang.Record",
          "java.lang.Enum");

  /** Not instantiable. */
  private SessionBoundTypeScan() {}

  /**
   * One value handed to a session sink.
   *
   * @param site {@code fully.qualified.Class#method} holding the call
   * @param sink the sink method called
   * @param types the class names the value's static type mentions, container and arguments alike
   * @param problems why the type does not determine the stored classes; empty when it does
   */
  record SessionWrite(
      @NotNull String site,
      @NotNull String sink,
      @NotNull Set<String> types,
      @NotNull List<String> problems) {

    /**
     * Whether the static type determines every class the value can store.
     *
     * @return {@code true} when there are no problems
     */
    boolean resolved() {
      return problems.isEmpty();
    }

    /**
     * Renders the write as it is reported.
     *
     * @return {@code site -> sink: types [problems]}
     */
    @Override
    public @NotNull String toString() {
      return site
          + " -> "
          + sink
          + ": "
          + new TreeSet<>(types)
          + (resolved() ? "" : " " + problems);
    }
  }

  /**
   * Finds every session write in one class file.
   *
   * @param classBytes the class file
   * @param loader the loader to resolve referenced types with
   * @return the writes, in code order
   */
  static @NotNull @Unmodifiable List<SessionWrite> writes(
      byte @NotNull [] classBytes, @NotNull ClassLoader loader) {
    ClassModel model = ClassFile.of().parse(classBytes);
    String owner = model.thisClass().asInternalName().replace('/', '.');
    List<SessionWrite> writes = new ArrayList<>();
    for (MethodModel method : model.methods()) {
      method
          .code()
          .ifPresent(
              code ->
                  writes.addAll(
                      writesIn(owner + "#" + method.methodName().stringValue(), code, loader)));
    }
    return List.copyOf(writes);
  }

  /**
   * Finds the ways one class file could put values into the session other than through a sink call:
   * {@code @SessionAttributes}, {@code @SessionScope} and {@code @Scope("session")}, on the class
   * or on a method.
   *
   * @param classBytes the class file
   * @return one description per occurrence; empty when there is none
   */
  static @NotNull @Unmodifiable List<String> otherSessionMechanisms(byte @NotNull [] classBytes) {
    ClassModel model = ClassFile.of().parse(classBytes);
    String owner = model.thisClass().asInternalName().replace('/', '.');
    List<String> found = new ArrayList<>();
    model
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(attribute -> found.addAll(sessionAnnotations(owner, attribute.annotations())));
    for (MethodModel method : model.methods()) {
      method
          .findAttribute(Attributes.runtimeVisibleAnnotations())
          .ifPresent(
              attribute ->
                  found.addAll(
                      sessionAnnotations(
                          owner + "#" + method.methodName().stringValue(),
                          attribute.annotations())));
    }
    return List.copyOf(found);
  }

  /**
   * Picks the session-binding annotations out of an annotation list.
   *
   * @param where the annotated element, for reporting
   * @param annotations the annotations
   * @return one description per session-binding annotation
   */
  private static @NotNull List<String> sessionAnnotations(
      @NotNull String where, @NotNull List<Annotation> annotations) {
    List<String> found = new ArrayList<>();
    for (Annotation annotation : annotations) {
      String type = annotation.className().stringValue();
      boolean sessionScoped =
          type.equals("Lorg/springframework/context/annotation/Scope;")
              && annotation.elements().stream()
                  .anyMatch(
                      element ->
                          element.value() instanceof AnnotationValue.OfString value
                              && value.stringValue().equals("session"));
      if (type.equals("Lorg/springframework/web/bind/annotation/SessionAttributes;")
          || type.equals("Lorg/springframework/web/context/annotation/SessionScope;")
          || sessionScoped) {
        found.add(where + " @" + type.substring(type.lastIndexOf('/') + 1, type.length() - 1));
      }
    }
    return found;
  }

  /**
   * Whether a call is a session sink.
   *
   * @param invoke the call
   * @return the sink's short name, or {@code null} when the call does not store into the session
   */
  static @Nullable String sinkName(@NotNull InvokeInstruction invoke) {
    String owner = invoke.owner().asInternalName();
    String name = invoke.name().stringValue();
    int arity = invoke.typeSymbol().parameterCount();
    if ((name.equals("addFlashAttribute") || name.equals("addAllFlashAttributes")) && arity >= 1) {
      return "RedirectAttributes." + name;
    }
    if (owner.equals("org/springframework/web/servlet/FlashMap")
        && (name.equals("put") || name.equals("putAll") || name.equals("putIfAbsent"))) {
      return "FlashMap." + name;
    }
    if ((owner.equals("jakarta/servlet/http/HttpSession")
            || owner.equals("org/springframework/session/Session"))
        && name.equals("setAttribute")) {
      return "HttpSession.setAttribute";
    }
    if (owner.equals("org/springframework/web/util/WebUtils")
        && name.equals("setSessionAttribute")) {
      return "WebUtils.setSessionAttribute";
    }
    return null;
  }

  /**
   * Finds the session writes of one method body.
   *
   * @param site the method's name for reporting
   * @param code the method body
   * @param loader the loader to resolve referenced types with
   * @return the writes, in code order
   */
  private static @NotNull List<SessionWrite> writesIn(
      @NotNull String site, @NotNull CodeModel code, @NotNull ClassLoader loader) {
    List<CodeElement> elements = code.elementList();
    Map<Label, Integer> labelIndex = new HashMap<>();
    Set<Label> mergeTargets = new HashSet<>();
    List<LocalVariable> locals = new ArrayList<>();
    List<LocalVariableType> localTypes = new ArrayList<>();
    for (int i = 0; i < elements.size(); i++) {
      switch (elements.get(i)) {
        case LabelTarget target -> labelIndex.put(target.label(), i);
        case BranchInstruction branch -> mergeTargets.add(branch.target());
        case TableSwitchInstruction table -> {
          mergeTargets.add(table.defaultTarget());
          table.cases().stream().map(SwitchCase::target).forEach(mergeTargets::add);
        }
        case LookupSwitchInstruction lookup -> {
          mergeTargets.add(lookup.defaultTarget());
          lookup.cases().stream().map(SwitchCase::target).forEach(mergeTargets::add);
        }
        case ExceptionCatch handler -> mergeTargets.add(handler.handler());
        case LocalVariable local -> locals.add(local);
        case LocalVariableType local -> localTypes.add(local);
        default -> {}
      }
    }
    Map<String, String> localSignatures = new HashMap<>();
    for (LocalVariableType local : localTypes) {
      localSignatures.put(
          local.slot() + "@" + labelIndex.get(local.startScope()), local.signature().stringValue());
    }
    List<SessionWrite> writes = new ArrayList<>();
    for (int i = 0; i < elements.size(); i++) {
      if (!(elements.get(i) instanceof InvokeInstruction invoke)) {
        continue;
      }
      String sink = sinkName(invoke);
      if (sink == null) {
        continue;
      }
      TypeShape shape = new TypeShape();
      Instruction pushing = pushingInstruction(elements, i, mergeTargets, shape);
      if (pushing != null) {
        describePushed(pushing, i, labelIndex, locals, localSignatures, loader, shape);
      }
      writes.add(new SessionWrite(site, sink, shape.classes, shape.problems));
    }
    return writes;
  }

  /**
   * Finds the instruction that pushed the last argument of the call at {@code callIndex}.
   *
   * @param elements the method body
   * @param callIndex the position of the sink call
   * @param mergeTargets labels other code jumps to
   * @param shape receives a problem when the value is the merge of two paths
   * @return the pushing instruction, or {@code null} with a problem recorded
   */
  private static @Nullable Instruction pushingInstruction(
      @NotNull List<CodeElement> elements,
      int callIndex,
      @NotNull Set<Label> mergeTargets,
      @NotNull TypeShape shape) {
    for (int j = callIndex - 1; j >= 0; j--) {
      CodeElement element = elements.get(j);
      if (element instanceof LabelTarget target && mergeTargets.contains(target.label())) {
        shape.problems.add("value is the merge of two code paths");
        return null;
      }
      if (element instanceof Instruction instruction) {
        return instruction;
      }
    }
    shape.problems.add("no pushing instruction");
    return null;
  }

  /**
   * Records the static type of the value one instruction pushes.
   *
   * @param pushing the instruction
   * @param callIndex the position of the sink call, for local-variable scopes
   * @param labelIndex the position of each bound label
   * @param locals the method's local variable table
   * @param localSignatures generic signatures keyed by slot and scope start
   * @param loader the loader to resolve referenced types with
   * @param shape receives the classes and problems
   */
  private static void describePushed(
      @NotNull Instruction pushing,
      int callIndex,
      @NotNull Map<Label, Integer> labelIndex,
      @NotNull List<LocalVariable> locals,
      @NotNull Map<String, String> localSignatures,
      @NotNull ClassLoader loader,
      @NotNull TypeShape shape) {
    switch (pushing) {
      case ConstantInstruction constant -> {
        ConstantDesc value = constant.constantValue();
        if (value != null) {
          shape.addRaw(value.getClass().getName(), loader);
        }
      }
      case InvokeDynamicInstruction indy -> shape.addDesc(indy.typeSymbol().returnType(), loader);
      case InvokeInstruction invoke when invoke.name().stringValue().equals("<init>") ->
          shape.addDesc(invoke.owner().asSymbol(), loader);
      case InvokeInstruction invoke -> {
        Method method = findMethod(invoke, loader);
        if (method == null) {
          shape.problems.add(
              "cannot resolve "
                  + invoke.owner().asInternalName()
                  + "."
                  + invoke.name().stringValue());
        } else {
          shape.addType(method.getGenericReturnType(), true);
        }
      }
      case FieldInstruction field -> {
        Field resolved = findField(field, loader);
        if (resolved == null) {
          shape.problems.add("cannot resolve field " + field.name().stringValue());
        } else {
          shape.addType(resolved.getGenericType(), true);
        }
      }
      case TypeCheckInstruction cast when cast.opcode() == Opcode.CHECKCAST ->
          shape.addDesc(cast.type().asSymbol(), loader);
      case LoadInstruction load when load.typeKind() == TypeKind.REFERENCE -> {
        LocalVariable local = localAt(load.slot(), callIndex, locals, labelIndex);
        if (local == null) {
          shape.problems.add("local slot " + load.slot() + " has no debug entry");
        } else {
          String signature =
              localSignatures.get(local.slot() + "@" + labelIndex.get(local.startScope()));
          if (signature == null) {
            shape.addDesc(local.typeSymbol(), loader);
          } else {
            shape.addSignature(Signature.parseFrom(signature), loader);
          }
        }
      }
      default -> shape.problems.add("value pushed by " + pushing.opcode());
    }
  }

  /**
   * Finds the local variable entry for a slot whose scope covers a position.
   *
   * @param slot the slot
   * @param position the element index
   * @param locals the local variable table
   * @param labelIndex the position of each bound label
   * @return the entry, or {@code null} when none covers the position
   */
  private static @Nullable LocalVariable localAt(
      int slot,
      int position,
      @NotNull List<LocalVariable> locals,
      @NotNull Map<Label, Integer> labelIndex) {
    for (LocalVariable local : locals) {
      Integer start = labelIndex.get(local.startScope());
      Integer end = labelIndex.get(local.endScope());
      if (local.slot() == slot
          && start != null
          && end != null
          && start <= position
          && position < end) {
        return local;
      }
    }
    return null;
  }

  /**
   * Resolves the method an instruction calls, searching the owner's superclasses and interfaces.
   *
   * @param invoke the call
   * @param loader the loader
   * @return the method, or {@code null} when it cannot be found
   */
  private static @Nullable Method findMethod(
      @NotNull InvokeInstruction invoke, @NotNull ClassLoader loader) {
    Class<?> owner = load(invoke.owner().asSymbol(), loader);
    if (owner == null) {
      return null;
    }
    String name = invoke.name().stringValue();
    MethodTypeDesc descriptor = invoke.typeSymbol();
    Deque<Class<?>> queue = new ArrayDeque<>(List.of(owner));
    Set<Class<?>> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      Class<?> type = queue.poll();
      if (!seen.add(type)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.getName().equals(name) && descriptorOf(method).equals(descriptor)) {
          return method;
        }
      }
      if (type.getSuperclass() != null) {
        queue.add(type.getSuperclass());
      }
      queue.addAll(List.of(type.getInterfaces()));
    }
    return null;
  }

  /**
   * Resolves the field an instruction reads, searching the owner's superclasses.
   *
   * @param instruction the field access
   * @param loader the loader
   * @return the field, or {@code null} when it cannot be found
   */
  private static @Nullable Field findField(
      @NotNull FieldInstruction instruction, @NotNull ClassLoader loader) {
    for (Class<?> type = load(instruction.owner().asSymbol(), loader);
        type != null;
        type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (field.getName().equals(instruction.name().stringValue())) {
          return field;
        }
      }
    }
    return null;
  }

  /**
   * The method descriptor of a reflected method.
   *
   * @param method the method
   * @return its descriptor
   */
  private static @NotNull MethodTypeDesc descriptorOf(@NotNull Method method) {
    return MethodTypeDesc.of(
        method.getReturnType().describeConstable().orElseThrow(),
        java.util.Arrays.stream(method.getParameterTypes())
            .map(type -> type.describeConstable().orElseThrow())
            .toArray(ClassDesc[]::new));
  }

  /**
   * Loads a class without initialising it.
   *
   * @param desc the class descriptor
   * @param loader the loader
   * @return the class, or {@code null} when it does not load
   */
  private static @Nullable Class<?> load(@NotNull ClassDesc desc, @NotNull ClassLoader loader) {
    ClassDesc element = desc;
    while (element.isArray()) {
      element = element.componentType();
    }
    if (element.isPrimitive()) {
      return null;
    }
    return load(nameOf(element), loader);
  }

  /**
   * Loads a class by binary name without initialising it.
   *
   * @param name the binary name
   * @param loader the loader
   * @return the class, or {@code null} when it does not load
   */
  static @Nullable Class<?> load(@NotNull String name, @NotNull ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException | LinkageError ex) {
      return null;
    }
  }

  /**
   * The binary name of a class descriptor.
   *
   * @param desc a non-array, non-primitive descriptor
   * @return e.g. {@code java.util.Map$Entry}
   */
  private static @NotNull String nameOf(@NotNull ClassDesc desc) {
    String descriptor = desc.descriptorString();
    return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
  }

  /**
   * Computes the closure of session-bound types: the roots, and for every application type among
   * them the types of its fields, record components and getters, transitively.
   *
   * @param roots the class names the session writes mention
   * @param loader the loader
   * @param problems receives every member whose type does not determine the stored classes
   * @return every class name reached, sorted
   */
  static @NotNull @Unmodifiable Set<String> closure(
      @NotNull Set<String> roots, @NotNull ClassLoader loader, @NotNull List<String> problems) {
    Set<String> reached = new TreeSet<>();
    Deque<String> queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      String name = queue.poll();
      if (!reached.add(name) || !name.startsWith(APPLICATION_PACKAGE)) {
        continue;
      }
      Class<?> type = load(name, loader);
      if (type == null) {
        problems.add(name + " does not load");
        continue;
      }
      if (type.isEnum()) {
        continue;
      }
      for (Type member : memberTypes(type)) {
        TypeShape shape = new TypeShape();
        shape.addType(member, false);
        shape.problems.forEach(problem -> problems.add(name + ": " + problem));
        queue.addAll(shape.classes);
      }
    }
    return reached;
  }

  /**
   * The generic types of the state Jackson would serialise for an application type.
   *
   * @param type the application type
   * @return its record components, or its instance fields and public getters, superclasses included
   */
  private static @NotNull List<Type> memberTypes(@NotNull Class<?> type) {
    List<Type> types = new ArrayList<>();
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        types.add(component.getGenericType());
      }
      return types;
    }
    for (Class<?> c = type;
        c != null && c.getName().startsWith(APPLICATION_PACKAGE);
        c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
          types.add(field.getGenericType());
        }
      }
      for (Method method : c.getDeclaredMethods()) {
        String name = method.getName();
        if (Modifier.isPublic(method.getModifiers())
            && !Modifier.isStatic(method.getModifiers())
            && method.getParameterCount() == 0
            && method.getReturnType() != void.class
            && (name.startsWith("get") || name.startsWith("is"))) {
          types.add(method.getGenericReturnType());
        }
      }
    }
    return types;
  }

  /**
   * Accumulates the classes one static type mentions and why it falls short of determining them.
   */
  private static final class TypeShape {

    /** The class names mentioned, container and arguments alike. */
    private final Set<String> classes = new LinkedHashSet<>();

    /** Why the type does not determine the stored classes. */
    private final List<String> problems = new ArrayList<>();

    /**
     * Records a reflected type.
     *
     * @param type the type
     * @param strict whether a type variable is a problem rather than bound elsewhere
     */
    void addType(@NotNull Type type, boolean strict) {
      switch (type) {
        case Class<?> c when c.isArray() -> addType(c.getComponentType(), strict);
        case Class<?> c when c.isPrimitive() -> {}
        case Class<?> c -> {
          addClass(c);
          if (c.getTypeParameters().length > 0) {
            problems.add("raw " + c.getName() + ": element types unknown");
          }
        }
        case ParameterizedType parameterized -> {
          addClass((Class<?>) parameterized.getRawType());
          for (Type argument : parameterized.getActualTypeArguments()) {
            addType(argument, strict);
          }
        }
        case WildcardType wildcard -> {
          for (Type bound : wildcard.getUpperBounds()) {
            addType(bound, strict);
          }
        }
        case GenericArrayType array -> addType(array.getGenericComponentType(), strict);
        case TypeVariable<?> variable -> {
          if (strict) {
            problems.add("type variable " + variable.getName());
          }
        }
        default -> problems.add("unsupported type " + type);
      }
    }

    /**
     * Records a descriptor-only type, which carries no type arguments.
     *
     * @param desc the descriptor
     * @param loader the loader
     */
    void addDesc(@NotNull ClassDesc desc, @NotNull ClassLoader loader) {
      ClassDesc element = desc;
      while (element.isArray()) {
        element = element.componentType();
      }
      if (!element.isPrimitive()) {
        addRaw(nameOf(element), loader);
      }
    }

    /**
     * Records a class by name, flagging it when it is generic and used without arguments.
     *
     * @param name the binary name
     * @param loader the loader
     */
    void addRaw(@NotNull String name, @NotNull ClassLoader loader) {
      Class<?> type = load(name, loader);
      if (type == null) {
        classes.add(name);
        problems.add(name + " does not load");
        return;
      }
      addType(type, true);
    }

    /**
     * Records a generic signature from a local variable type table.
     *
     * @param signature the parsed signature
     * @param loader the loader
     */
    void addSignature(@NotNull Signature signature, @NotNull ClassLoader loader) {
      switch (signature) {
        case Signature.ArrayTypeSig array -> addSignature(array.componentSignature(), loader);
        case Signature.BaseTypeSig _ -> {}
        case Signature.TypeVarSig variable ->
            problems.add("type variable " + variable.identifier());
        case Signature.ClassTypeSig type -> {
          String name = binaryName(type);
          Class<?> loaded = load(name, loader);
          if (loaded == null) {
            classes.add(name);
            problems.add(name + " does not load");
          } else {
            addClass(loaded);
            if (type.typeArgs().isEmpty() && loaded.getTypeParameters().length > 0) {
              problems.add("raw " + name + ": element types unknown");
            }
          }
          for (Signature.TypeArg argument : type.typeArgs()) {
            switch (argument) {
              case Signature.TypeArg.Bounded bounded -> addSignature(bounded.boundType(), loader);
              case Signature.TypeArg.Unbounded _ -> problems.add("unbounded wildcard in " + name);
            }
          }
        }
      }
    }

    /**
     * Records one class and flags it when its static type does not fix the runtime class.
     *
     * @param type the class
     */
    private void addClass(@NotNull Class<?> type) {
      classes.add(type.getName());
      if (TOO_BROAD.contains(type.getName())) {
        problems.add(type.getName() + " does not determine the stored class");
      } else if (type.getName().startsWith(APPLICATION_PACKAGE)
          && !type.isEnum()
          && (type.isInterface() || Modifier.isAbstract(type.getModifiers()))) {
        problems.add("abstract application type " + type.getName());
      }
    }

    /**
     * The binary name of a class type signature, nested types joined with {@code $}.
     *
     * @param type the signature
     * @return the binary name
     */
    private static @NotNull String binaryName(@NotNull Signature.ClassTypeSig type) {
      return type.outerType()
          .map(outer -> binaryName(outer) + "$" + type.className())
          .orElseGet(() -> type.className().replace('/', '.'));
    }
  }
}
