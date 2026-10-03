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

package de.greluc.krt.profit.basetool.backend.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import jakarta.validation.Valid;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checks behind the G-06 mass-assignment guards (REQ-SEC-077, REQ-API-015): a type returned by
 * a handler is never a request body, request types carry no server-managed component, and every
 * request body is validated.
 *
 * <p>Handlers are read by reflection from the {@code @RestController} classes of the imported
 * classes. Every check returns its violations as messages, so the guard test can assert an empty
 * list on the production classes and a non-empty one on planted fixtures.
 */
final class MassAssignmentGuardRules {

  private static final String PROJECT_PACKAGE = "de.greluc.krt.profit.basetool.";

  private static final Set<String> OWNER_COMPONENTS =
      Set.of("owner", "ownerId", "ownerName", "ownerUserId", "ownerSub", "ownerHandle");

  private static final Set<String> TIMESTAMP_COMPONENTS =
      Set.of(
          "createdAt",
          "updatedAt",
          "createdBy",
          "updatedBy",
          "modifiedAt",
          "lastModifiedAt",
          "lastModifiedBy",
          "deletedAt");

  private static final String TRANSITION_PATH_SUFFIX = "/status";

  private MassAssignmentGuardRules() {}

  /**
   * One request-mapped handler method.
   *
   * @param controller the declaring controller
   * @param method the handler method
   * @param path the class path joined with the first method path
   */
  record Handler(@NotNull Class<?> controller, @NotNull Method method, @NotNull String path) {

    @Override
    @NotNull
    public String toString() {
      return controller.getSimpleName() + "#" + method.getName();
    }
  }

  /**
   * Lists every request-mapped method of every {@code @RestController} among the classes.
   *
   * @param classes the imported classes
   * @return the handlers, ordered by controller and method name
   */
  @NotNull
  static List<Handler> handlers(@NotNull JavaClasses classes) {
    List<Handler> handlers = new ArrayList<>();
    for (JavaClass javaClass : classes) {
      if (javaClass.isInterface() || !javaClass.isAnnotatedWith(RestController.class)) {
        continue;
      }
      Class<?> controller = javaClass.reflect();
      RequestMapping classMapping =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      String base = classMapping != null ? firstPath(classMapping) : "";
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping != null) {
          handlers.add(new Handler(controller, method, base + firstPath(mapping)));
        }
      }
    }
    handlers.sort(Comparator.comparing(Handler::toString));
    return handlers;
  }

  private static String firstPath(RequestMapping mapping) {
    return mapping.path().length > 0 ? mapping.path()[0] : "";
  }

  /**
   * Collects the project types a handler returns, unwrapping generic containers such as {@code
   * ResponseEntity}, {@code PageResponse} and {@code List}.
   *
   * @param handlers the handlers
   * @return the returned project types
   */
  @NotNull
  static Set<Class<?>> responseTypes(@NotNull List<Handler> handlers) {
    Set<Class<?>> types = new LinkedHashSet<>();
    for (Handler handler : handlers) {
      projectTypes(handler.method().getGenericReturnType(), types);
    }
    return types;
  }

  /**
   * Maps every project type bound as a request body, unwrapped from its containers, to the paths
   * that bind it.
   *
   * @param handlers the handlers
   * @return the request body types with their paths
   */
  @NotNull
  static Map<Class<?>, Set<String>> requestBodyTypes(@NotNull List<Handler> handlers) {
    Map<Class<?>, Set<String>> types = new LinkedHashMap<>();
    for (Handler handler : handlers) {
      for (Parameter parameter : handler.method().getParameters()) {
        if (!parameter.isAnnotationPresent(RequestBody.class)) {
          continue;
        }
        Set<Class<?>> bound = new LinkedHashSet<>();
        projectTypes(parameter.getParameterizedType(), bound);
        for (Class<?> type : bound) {
          types.computeIfAbsent(type, ignored -> new TreeSet<>()).add(handler.path());
        }
      }
    }
    return types;
  }

  /**
   * Rule 1: no type is both returned by a handler and bound as a request body, except the listed
   * ones; a listed type that is no longer dual-use is reported as stale.
   *
   * @param handlers the handlers
   * @param dualUseExceptions the reviewed dual-use types
   * @return one message per violation
   */
  @NotNull
  static List<String> dualUseViolations(
      @NotNull List<Handler> handlers, @NotNull Set<Class<?>> dualUseExceptions) {
    Set<Class<?>> responses = responseTypes(handlers);
    List<String> violations = new ArrayList<>();
    Set<Class<?>> dualUse = new LinkedHashSet<>();
    for (Map.Entry<Class<?>, Set<String>> entry : requestBodyTypes(handlers).entrySet()) {
      if (responses.contains(entry.getKey())) {
        dualUse.add(entry.getKey());
        if (!dualUseExceptions.contains(entry.getKey())) {
          violations.add(
              entry.getKey().getName()
                  + " is returned by a handler and bound as a request body on "
                  + entry.getValue()
                  + "; bind a dedicated request record instead (REQ-SEC-077)");
        }
      }
    }
    for (Class<?> listed : dualUseExceptions) {
      if (!dualUse.contains(listed)) {
        violations.add(listed.getName() + " is listed as dual-use but is no longer both");
      }
    }
    return violations;
  }

  /**
   * Rule 2: no request type, nor any project type nested in one, declares a server-managed
   * component, except the reviewed client inputs.
   *
   * <p>Server-managed are: {@code id} on the body type itself (a nested {@code id} names a
   * referenced row); the owner ({@code owner}, {@code ownerId}, …); every {@code owning…}
   * component; every component naming an org unit; creation and modification timestamps and {@code
   * …SyncedAt}; and {@code status} unless every path binding the type ends in {@code /status}. The
   * walk does not enter the listed dual-use types, which are proven separately.
   *
   * @param handlers the handlers
   * @param dualUseExceptions the reviewed dual-use types, not walked
   * @param reviewedInputs per type, the components that are deliberate client input, each with the
   *     reason and the check that validates it; an entry that matches nothing is reported as stale
   * @return one message per violation
   */
  @NotNull
  static List<String> serverManagedComponentViolations(
      @NotNull List<Handler> handlers,
      @NotNull Set<Class<?>> dualUseExceptions,
      @NotNull Map<Class<?>, Map<String, String>> reviewedInputs) {
    Map<Class<?>, Set<String>> topLevel = requestBodyTypes(handlers);
    Map<Class<?>, Set<String>> pathsByType = new LinkedHashMap<>();
    Set<Class<?>> topTypes = new LinkedHashSet<>(topLevel.keySet());
    Deque<Class<?>> queue = new ArrayDeque<>();
    for (Map.Entry<Class<?>, Set<String>> entry : topLevel.entrySet()) {
      if (dualUseExceptions.contains(entry.getKey())) {
        continue;
      }
      pathsByType.put(entry.getKey(), new TreeSet<>(entry.getValue()));
      queue.add(entry.getKey());
    }
    while (!queue.isEmpty()) {
      Class<?> type = queue.poll();
      for (Component component : components(type)) {
        Set<Class<?>> nested = new LinkedHashSet<>();
        projectTypes(component.type(), nested);
        for (Class<?> child : nested) {
          if (dualUseExceptions.contains(child) || child == type) {
            continue;
          }
          Set<String> childPaths = pathsByType.computeIfAbsent(child, ignored -> new TreeSet<>());
          if (childPaths.addAll(pathsByType.get(type))) {
            queue.add(child);
          }
        }
      }
    }

    List<String> violations = new ArrayList<>();
    Set<String> usedReviewedInputs = new LinkedHashSet<>();
    for (Map.Entry<Class<?>, Set<String>> entry : pathsByType.entrySet()) {
      Class<?> type = entry.getKey();
      boolean top = topTypes.contains(type);
      boolean transitionOnly =
          entry.getValue().stream().allMatch(path -> path.endsWith(TRANSITION_PATH_SUFFIX));
      Map<String, String> reviewed = reviewedInputs.getOrDefault(type, Map.of());
      for (Component component : components(type)) {
        String reason = serverManagedReason(component.name(), top, transitionOnly);
        if (reason == null) {
          continue;
        }
        if (reviewed.containsKey(component.name())) {
          usedReviewedInputs.add(type.getName() + "#" + component.name());
          continue;
        }
        violations.add(
            type.getName()
                + " is bound by "
                + entry.getValue()
                + " and declares `"
                + component.name()
                + "`, "
                + reason
                + " (REQ-SEC-077)");
      }
    }
    for (Map.Entry<Class<?>, Map<String, String>> entry : reviewedInputs.entrySet()) {
      for (String component : entry.getValue().keySet()) {
        if (!usedReviewedInputs.contains(entry.getKey().getName() + "#" + component)) {
          violations.add(
              entry.getKey().getName()
                  + "#"
                  + component
                  + " is listed as a reviewed client input but no request type declares it as a"
                  + " server-managed name");
        }
      }
    }
    return violations;
  }

  /**
   * Classifies a component name as server-managed.
   *
   * @param name the component name
   * @param topLevel whether the declaring type is bound as the body itself
   * @param transitionOnly whether every path binding the declaring type is a status transition
   * @return why the component is server-managed, or {@code null} when it is not
   */
  @Nullable
  static String serverManagedReason(
      @NotNull String name, boolean topLevel, boolean transitionOnly) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (topLevel && "id".equals(name)) {
      return "the identity of the written row comes from the path or the server";
    }
    if (OWNER_COMPONENTS.contains(name)) {
      return "the owner is stamped from the authenticated caller";
    }
    if (name.startsWith("owning") || lower.contains("orgunit")) {
      return "the org unit is stamped or validated by the scope service, never copied";
    }
    if (TIMESTAMP_COMPONENTS.contains(name) || name.endsWith("SyncedAt")) {
      return "the timestamp is maintained by the server";
    }
    if ("status".equals(name) && !transitionOnly) {
      return "a status moves only through a transition endpoint ending in /status";
    }
    return null;
  }

  /**
   * Rule 3: every {@code @RequestBody} parameter carries {@code @Valid} or {@code @Validated}.
   *
   * @param handlers the handlers
   * @return one message per unvalidated body, and the number of bodies checked
   */
  @NotNull
  static ValidationReport unvalidatedBodies(@NotNull List<Handler> handlers) {
    List<String> violations = new ArrayList<>();
    int bodies = 0;
    for (Handler handler : handlers) {
      for (Parameter parameter : handler.method().getParameters()) {
        if (!parameter.isAnnotationPresent(RequestBody.class)) {
          continue;
        }
        bodies++;
        if (!parameter.isAnnotationPresent(Valid.class)
            && !parameter.isAnnotationPresent(Validated.class)) {
          violations.add(
              handler
                  + " binds a @RequestBody without @Valid on "
                  + handler.path()
                  + " (REQ-API-015)");
        }
      }
    }
    return new ValidationReport(bodies, violations);
  }

  /**
   * The outcome of the body-validation rule.
   *
   * @param bodies the number of {@code @RequestBody} parameters checked
   * @param violations one message per unvalidated body
   */
  record ValidationReport(int bodies, @NotNull List<String> violations) {}

  /**
   * A component of a request type: a record component or a non-static field.
   *
   * @param name the component name
   * @param type the component's generic type
   */
  record Component(@NotNull String name, @NotNull Type type) {}

  /**
   * Lists the components of a request type: the record components of a record, otherwise the
   * non-static fields of the class and its project superclasses; none for enums and interfaces.
   *
   * @param type the request type
   * @return the components
   */
  @NotNull
  static List<Component> components(@NotNull Class<?> type) {
    List<Component> components = new ArrayList<>();
    if (type.isEnum() || type.isInterface()) {
      return components;
    }
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        components.add(new Component(component.getName(), component.getGenericType()));
      }
      return components;
    }
    for (Class<?> declaring = type;
        declaring != null && declaring.getName().startsWith(PROJECT_PACKAGE);
        declaring = declaring.getSuperclass()) {
      for (Field field : declaring.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
          components.add(new Component(field.getName(), field.getGenericType()));
        }
      }
    }
    return components;
  }

  /**
   * Adds every project class named by a type, its type arguments, array components, wildcard bounds
   * and, for a sealed interface, its permitted subclasses.
   *
   * @param type the type to unwrap
   * @param out the set receiving the project classes
   */
  static void projectTypes(@NotNull Type type, @NotNull Set<Class<?>> out) {
    if (type instanceof Class<?> raw) {
      if (raw.isArray()) {
        projectTypes(raw.getComponentType(), out);
      } else if (raw.getName().startsWith(PROJECT_PACKAGE) && out.add(raw) && raw.isSealed()) {
        for (Class<?> permitted : raw.getPermittedSubclasses()) {
          projectTypes(permitted, out);
        }
      }
    } else if (type instanceof ParameterizedType parameterized) {
      projectTypes(parameterized.getRawType(), out);
      for (Type argument : parameterized.getActualTypeArguments()) {
        projectTypes(argument, out);
      }
    } else if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        projectTypes(bound, out);
      }
    } else if (type instanceof TypeVariable<?> variable) {
      for (Type bound : variable.getBounds()) {
        projectTypes(bound, out);
      }
    }
  }
}
