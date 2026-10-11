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
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import de.greluc.krt.profit.basetool.backend.annotation.TenantScoped;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.Repository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The checks behind the G-05 tenancy guards (REQ-ORG-028): which entities hold an org-unit
 * reference and whether each is classified, which aggregates are tenant data, and whether the write
 * endpoints of the controllers that write tenant data gate on the scope service.
 *
 * <p>Every check returns its violations as messages, so the guard test can assert an empty list on
 * the production classes and a non-empty one on planted fixtures.
 */
final class TenancyGuardRules {

  private static final String TRAVERSED_PACKAGE = "de.greluc.krt.profit.basetool";

  private static final int MAX_DELEGATION_DEPTH = 3;

  private TenancyGuardRules() {}

  /**
   * One persistent field of an entity that refers to an org unit.
   *
   * @param entity the entity declaring the field, directly or through a mapped superclass
   * @param field the field name, dotted for a field of an embedded key or embeddable
   */
  record OrgUnitReference(@NotNull Class<?> entity, @NotNull String field) {

    /**
     * Returns the key the classification maps use for this reference.
     *
     * @return the entity's fully qualified name, {@code #}, and the field name
     */
    @NotNull
    String key() {
      return key(entity, field);
    }

    /**
     * Builds a classification key from a class literal and a field name.
     *
     * @param entity the entity class
     * @param field the field name
     * @return the entity's fully qualified name, {@code #}, and the field name
     */
    @NotNull
    static String key(@NotNull Class<?> entity, @NotNull String field) {
      return entity.getName() + "#" + field;
    }
  }

  /**
   * Identifies a request handler by its controller and method name.
   *
   * @param controller the controller class
   * @param method the handler method name
   */
  record HandlerKey(@NotNull Class<?> controller, @NotNull String method) {

    @Override
    @NotNull
    public String toString() {
      return controller.getSimpleName() + "#" + method;
    }
  }

  /**
   * The outcome of the write-gate rule.
   *
   * @param selectedControllers the controllers that inject a writer of tenant data
   * @param selectedHandlers the write handlers with a {@code UUID} path variable on them
   * @param violations one message per handler that does not gate on the scope service
   */
  record GateReport(
      @NotNull Set<Class<?>> selectedControllers,
      @NotNull Set<HandlerKey> selectedHandlers,
      @NotNull List<String> violations) {}

  /**
   * Lists the persistent fields of an entity that refer to an org unit: an association to {@link
   * OrgUnit} or a subtype, a collection of them, or a {@code UUID} column named like an org-unit or
   * squadron key.
   *
   * <p>Fields of mapped superclasses count as the entity's own; fields inherited from another
   * entity belong to that entity and are not repeated here.
   *
   * @param entity the {@code @Entity} class
   * @return the references in declaration order
   */
  @NotNull
  static List<OrgUnitReference> orgUnitReferences(@NotNull Class<?> entity) {
    List<OrgUnitReference> references = new ArrayList<>();
    for (Class<?> type = entity;
        type != null && (type == entity || type.isAnnotationPresent(MappedSuperclass.class));
        type = type.getSuperclass()) {
      collectReferences(entity, type, "", references);
    }
    return references;
  }

  private static void collectReferences(
      Class<?> entity, Class<?> declaring, String prefix, List<OrgUnitReference> out) {
    for (Field field : declaring.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
        continue;
      }
      if (field.isAnnotationPresent(Embedded.class)
          || field.isAnnotationPresent(EmbeddedId.class)
          || field.getType().isAnnotationPresent(Embeddable.class)) {
        collectReferences(entity, field.getType(), prefix + field.getName() + ".", out);
        continue;
      }
      if (refersToOrgUnit(field)) {
        out.add(new OrgUnitReference(entity, prefix + field.getName()));
      }
    }
  }

  private static boolean refersToOrgUnit(Field field) {
    if (OrgUnit.class.isAssignableFrom(field.getType())) {
      return true;
    }
    if (field.getGenericType() instanceof ParameterizedType parameterized) {
      for (Type argument : parameterized.getActualTypeArguments()) {
        if (argument instanceof Class<?> raw && OrgUnit.class.isAssignableFrom(raw)) {
          return true;
        }
      }
    }
    if (field.getType() == UUID.class) {
      String column = columnName(field);
      return column.endsWith("org_unit_id") || column.endsWith("squadron_id");
    }
    return false;
  }

  private static String columnName(Field field) {
    Column column = field.getAnnotation(Column.class);
    if (column != null && !column.name().isBlank()) {
      return column.name().toLowerCase(Locale.ROOT);
    }
    JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
    if (joinColumn != null && !joinColumn.name().isBlank()) {
      return joinColumn.name().toLowerCase(Locale.ROOT);
    }
    return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }

  /**
   * Checks that every org-unit reference of every entity is classified: named by the entity's
   * {@link TenantScoped} marker, or listed as not a tenancy boundary with a reason.
   *
   * <p>Also reports a marker naming a field that is no org-unit reference, a reference both marked
   * and listed, a stale list entry, and a marker on a class that is no entity.
   *
   * @param classes the imported classes to scan for entities and markers
   * @param notTenantScope reference keys ({@link OrgUnitReference#key()}) mapped to the reason the
   *     reference is no tenancy boundary
   * @return one message per violation, empty when every reference is classified
   */
  @NotNull
  static List<String> markerViolations(
      @NotNull JavaClasses classes, @NotNull Map<String, String> notTenantScope) {
    List<String> violations = new ArrayList<>();
    Set<String> seenKeys = new HashSet<>();
    for (JavaClass javaClass : classes) {
      boolean entity = javaClass.isAnnotatedWith(Entity.class);
      boolean marked = javaClass.isAnnotatedWith(TenantScoped.class);
      if (marked && !entity) {
        violations.add(javaClass.getName() + " carries @TenantScoped but is no @Entity");
      }
      if (!entity) {
        continue;
      }
      Class<?> type = javaClass.reflect();
      Map<String, OrgUnitReference> references = new LinkedHashMap<>();
      for (OrgUnitReference reference : orgUnitReferences(type)) {
        references.put(reference.field(), reference);
        seenKeys.add(reference.key());
      }
      Set<String> markedFields = new LinkedHashSet<>();
      if (marked) {
        TenantScoped marker = type.getAnnotation(TenantScoped.class);
        markedFields.addAll(List.of(marker.value()));
        if (markedFields.isEmpty()) {
          violations.add(type.getName() + " carries @TenantScoped without naming its org unit");
        }
      }
      for (String field : markedFields) {
        if (!references.containsKey(field)) {
          violations.add(
              type.getName()
                  + " @TenantScoped names `"
                  + field
                  + "`, which is no org-unit association of the entity");
        }
      }
      for (OrgUnitReference reference : references.values()) {
        boolean listed = notTenantScope.containsKey(reference.key());
        boolean named = markedFields.contains(reference.field());
        if (named && listed) {
          violations.add(
              reference.key()
                  + " is named by @TenantScoped and also listed as no tenancy boundary");
        } else if (!named && !listed) {
          violations.add(
              reference.key()
                  + " refers to an org unit but is neither named by @TenantScoped on its entity nor"
                  + " listed with a reason as no tenancy boundary (REQ-ORG-028)");
        }
      }
    }
    for (String key : new TreeSet<>(notTenantScope.keySet())) {
      if (!seenKeys.contains(key)) {
        violations.add(key + " is listed as no tenancy boundary but is no org-unit reference");
      }
    }
    return violations;
  }

  /**
   * Returns the entities that are tenant data: every {@link TenantScoped} entity plus,
   * transitively, every entity with a mandatory to-one association to one of them (an aggregate
   * part).
   *
   * @param classes the imported classes
   * @return the tenant-data entity classes
   */
  @NotNull
  static Set<Class<?>> tenantData(@NotNull JavaClasses classes) {
    List<Class<?>> entities = new ArrayList<>();
    for (JavaClass javaClass : classes) {
      if (javaClass.isAnnotatedWith(Entity.class)) {
        entities.add(javaClass.reflect());
      }
    }
    Set<Class<?>> data = new LinkedHashSet<>();
    for (Class<?> entity : entities) {
      if (entity.isAnnotationPresent(TenantScoped.class)) {
        data.add(entity);
      }
    }
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Class<?> entity : entities) {
        if (!data.contains(entity) && isMandatoryPartOf(entity, data)) {
          data.add(entity);
          grew = true;
        }
      }
    }
    return data;
  }

  private static boolean isMandatoryPartOf(Class<?> entity, Set<Class<?>> roots) {
    for (Field field : entity.getDeclaredFields()) {
      if (!roots.contains(field.getType())) {
        continue;
      }
      ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
      OneToOne oneToOne = field.getAnnotation(OneToOne.class);
      if (manyToOne == null && oneToOne == null) {
        continue;
      }
      boolean optional = manyToOne != null ? manyToOne.optional() : oneToOne.optional();
      JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
      boolean nullableColumn = joinColumn == null || joinColumn.nullable();
      if (!optional || !nullableColumn) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks that every write handler with a {@code UUID} path variable, on a controller that injects
   * a writer of tenant data or of the org-unit tree, gates on the scope service.
   *
   * <p>A controller is selected when one of its fields is a repository of such an entity or a class
   * that calls a mutating method ({@code save*}, {@code delete*} or a {@code @Modifying} query) on
   * one, or calls an {@code @ObserverSpi} or a module's published command interface one of whose
   * implementations does. A selected handler passes when its effective {@code @PreAuthorize} is
   * scope-gated ({@link SpelScopeGate}); when it is listed as service-gated and reaches a scope
   * check from its body within {@value #MAX_DELEGATION_DEPTH} calls; or when it is listed as no
   * tenant write.
   *
   * @param classes the imported classes holding controllers, services and repositories
   * @param tenantData the tenant-data entities ({@link #tenantData(JavaClasses)})
   * @param scopeGateTypes the scope services whose {@code can*} methods and SpEL bean names count
   * @param serviceGated handlers whose scope check lives in the service layer, with the reason
   * @param notTenantWrites handlers that write no tenant data although selected, with the reason
   * @return the selection and one message per violation
   */
  @NotNull
  static GateReport writeGateReport(
      @NotNull JavaClasses classes,
      @NotNull Set<Class<?>> tenantData,
      @NotNull Set<Class<?>> scopeGateTypes,
      @NotNull Map<HandlerKey, String> serviceGated,
      @NotNull Map<HandlerKey, String> notTenantWrites) {
    Map<String, Class<?>> guardedRepositories = guardedRepositories(classes, tenantData);
    Set<String> writers = writersOf(classes, guardedRepositories);
    SpelScopeGate gate = new SpelScopeGate(scopeGateTypes);
    Set<String> scopeTypeNames = new HashSet<>();
    for (Class<?> type : scopeGateTypes) {
      scopeTypeNames.add(type.getName());
    }

    Set<Class<?>> controllers = new LinkedHashSet<>();
    Set<HandlerKey> handlers = new LinkedHashSet<>();
    List<String> violations = new ArrayList<>();
    for (JavaClass javaClass : classes) {
      if (javaClass.isInterface() || !javaClass.isAnnotatedWith(RestController.class)) {
        continue;
      }
      boolean selected = false;
      for (JavaField field : javaClass.getFields()) {
        String fieldType = field.getRawType().getName();
        if (writers.contains(fieldType) || guardedRepositories.containsKey(fieldType)) {
          selected = true;
        }
      }
      if (!selected) {
        continue;
      }
      Class<?> controller = javaClass.reflect();
      controllers.add(controller);
      PreAuthorize classGate =
          AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
      for (Method method : controller.getDeclaredMethods()) {
        if (!isWriteHandlerWithUuidPathVariable(method)) {
          continue;
        }
        HandlerKey key = new HandlerKey(controller, method.getName());
        handlers.add(key);
        PreAuthorize methodGate =
            AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        String expression =
            methodGate != null ? methodGate.value() : classGate != null ? classGate.value() : "";
        if (gate.isScopeGated(expression)) {
          continue;
        }
        if (notTenantWrites.containsKey(key)) {
          continue;
        }
        if (serviceGated.containsKey(key)) {
          JavaMethod javaMethod = javaClass.getMethod(method.getName(), method.getParameterTypes());
          if (reachesScopeCheck(javaMethod, 0, new HashSet<>(), gate, scopeTypeNames)) {
            continue;
          }
          violations.add(
              key
                  + " is listed as service-gated, but no scope check is reachable from its body"
                  + " within "
                  + MAX_DELEGATION_DEPTH
                  + " calls");
          continue;
        }
        violations.add(
            key
                + " writes on a controller that writes tenant data, takes a UUID path variable,"
                + " and its @PreAuthorize `"
                + expression
                + "` does not gate every branch on "
                + gate.scopeBeanReferences()
                + " or hasRole('ADMIN') (REQ-ORG-002, REQ-ORG-028)");
      }
    }
    for (Map<HandlerKey, String> list : List.of(serviceGated, notTenantWrites)) {
      for (HandlerKey key : list.keySet()) {
        if (!handlers.contains(key)) {
          violations.add(key + " is listed as an exception but is no selected write handler");
        }
      }
    }
    return new GateReport(controllers, handlers, violations);
  }

  private static Map<String, Class<?>> guardedRepositories(
      JavaClasses classes, Set<Class<?>> tenantData) {
    Map<String, Class<?>> repositories = new HashMap<>();
    for (JavaClass javaClass : classes) {
      if (!javaClass.isInterface() || !javaClass.isAssignableTo(Repository.class)) {
        continue;
      }
      Class<?> domain =
          ResolvableType.forClass(javaClass.reflect()).as(Repository.class).getGeneric(0).resolve();
      if (domain != null
          && (tenantData.contains(domain)
              || OrgUnit.class.isAssignableFrom(domain)
              || domain == OrgUnitMembership.class)) {
        repositories.put(javaClass.getName(), domain);
      }
    }
    return repositories;
  }

  private static Set<String> writersOf(
      JavaClasses classes, Map<String, Class<?>> guardedRepositories) {
    Set<String> writers = new HashSet<>();
    for (JavaClass javaClass : classes) {
      if (javaClass.isInterface()) {
        continue;
      }
      for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
        if (guardedRepositories.containsKey(call.getTargetOwner().getName()) && isMutating(call)) {
          writers.add(javaClass.getName());
          break;
        }
      }
    }
    Set<String> writingObservers = new HashSet<>();
    for (JavaClass javaClass : classes) {
      if (writers.contains(javaClass.getName())) {
        for (JavaClass spi : javaClass.getAllRawInterfaces()) {
          if (spi.isAnnotatedWith(ObserverSpi.class) || isCommandApi(spi)) {
            writingObservers.add(spi.getName());
          }
        }
      }
    }
    for (JavaClass javaClass : classes) {
      if (javaClass.isInterface()) {
        continue;
      }
      for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
        if (writingObservers.contains(call.getTargetOwner().getName())) {
          writers.add(javaClass.getName());
          break;
        }
      }
    }
    return writers;
  }

  /**
   * Whether an interface is a module's published command API: a {@code …Commands} type in an {@code
   * api} package (plan §5.3).
   *
   * @param type the interface
   * @return {@code true} for a published command interface
   */
  private static boolean isCommandApi(JavaClass type) {
    return type.getPackageName().endsWith(".api") && type.getSimpleName().endsWith("Commands");
  }

  private static boolean isMutating(JavaMethodCall call) {
    String name = call.getName();
    if (name.startsWith("save") || name.startsWith("delete")) {
      return true;
    }
    return call.getTarget()
        .resolveMember()
        .map(m -> m.isAnnotatedWith(Modifying.class))
        .orElse(false);
  }

  private static boolean isWriteHandlerWithUuidPathVariable(Method method) {
    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    if (mapping == null) {
      return false;
    }
    boolean write = mapping.method().length == 0;
    for (RequestMethod verb : mapping.method()) {
      if (verb == RequestMethod.POST
          || verb == RequestMethod.PUT
          || verb == RequestMethod.PATCH
          || verb == RequestMethod.DELETE) {
        write = true;
      }
    }
    if (!write) {
      return false;
    }
    for (Parameter parameter : method.getParameters()) {
      if (parameter.isAnnotationPresent(PathVariable.class) && parameter.getType() == UUID.class) {
        return true;
      }
    }
    return false;
  }

  private static boolean reachesScopeCheck(
      JavaMethod method,
      int depth,
      Set<String> visited,
      SpelScopeGate gate,
      Set<String> scopeTypeNames) {
    if (depth > MAX_DELEGATION_DEPTH || !visited.add(method.getFullName())) {
      return false;
    }
    Deque<JavaMethod> next = new ArrayDeque<>();
    for (JavaMethodReference reference : method.getMethodReferencesFromSelf()) {
      if (scopeTypeNames.contains(reference.getTargetOwner().getName())
          && reference.getName().startsWith("can")) {
        return true;
      }
    }
    for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
      JavaClass owner = call.getTargetOwner();
      if (scopeTypeNames.contains(owner.getName()) && call.getName().startsWith("can")) {
        return true;
      }
      if (!owner.getPackageName().startsWith(TRAVERSED_PACKAGE)
          || owner.isAssignableTo(Repository.class)) {
        continue;
      }
      for (JavaMethod target : call.getTarget().resolveMember().stream().toList()) {
        if (hasScopeGatedPreAuthorize(target, gate)) {
          return true;
        }
        next.add(target);
      }
    }
    for (JavaMethod target : next) {
      if (reachesScopeCheck(target, depth + 1, visited, gate, scopeTypeNames)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasScopeGatedPreAuthorize(JavaMethod method, SpelScopeGate gate) {
    if (!method.isAnnotatedWith(PreAuthorize.class)) {
      return false;
    }
    Object value =
        method
            .getAnnotationOfType(PreAuthorize.class.getName())
            .tryGetExplicitlyDeclaredProperty("value")
            .orElse("");
    return gate.isScopeGated(String.valueOf(value));
  }

  /**
   * Collects the entities of the given classes in a stable order.
   *
   * @param classes the imported classes
   * @return the entity classes
   */
  @NotNull
  static Collection<Class<?>> entities(@NotNull JavaClasses classes) {
    List<Class<?>> entities = new ArrayList<>();
    for (JavaClass javaClass : classes) {
      if (javaClass.isAnnotatedWith(Entity.class)) {
        entities.add(javaClass.reflect());
      }
    }
    return entities;
  }
}
