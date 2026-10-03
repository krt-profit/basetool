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

package de.greluc.krt.profit.basetool.backend.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.greluc.krt.profit.basetool.backend.config.ApiDomains;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;

/**
 * Finds the Java types the REST controllers expose and the OpenAPI schema name each one gets, so
 * that two types sharing one schema name can be caught (REQ-API-018).
 *
 * <p>springdoc names a schema after the class's simple name unless {@code @Schema(name = …)} says
 * otherwise; two exposed classes with one name collapse into one schema and one of them is
 * documented wrongly. A type is exposed when it is reachable from a handler's return type or its
 * {@code @RequestBody} / {@code @RequestPart} parameters, through generic arguments, arrays, record
 * components and non-static fields. Enums are left out because the document inlines them.
 */
public final class ExposedTypes {

  /** The package whose classes become named schemas. */
  private static final String OWN_PACKAGE = "de.greluc.krt.profit.basetool";

  /** The package the backend's controllers live in. */
  private static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  /** Not instantiable. */
  private ExposedTypes() {}

  /**
   * Scans the backend for its controllers.
   *
   * @return every main-source class annotated, directly or as a meta-annotation, with {@link
   *     Controller}; test fixtures are left out
   * @throws IllegalStateException if a scanned class cannot be loaded
   */
  public static @NotNull @Unmodifiable List<Class<?>> controllers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
    URL mainOutput = ApiDomains.class.getProtectionDomain().getCodeSource().getLocation();
    List<Class<?>> controllers = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BACKEND_PACKAGE)) {
      Class<?> controller;
      try {
        controller =
            ClassUtils.forName(definition.getBeanClassName(), ExposedTypes.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("cannot load " + definition.getBeanClassName(), e);
      }
      if (mainOutput.equals(controller.getProtectionDomain().getCodeSource().getLocation())) {
        controllers.add(controller);
      }
    }
    controllers.sort(Comparator.comparing(Class::getName));
    return List.copyOf(controllers);
  }

  /**
   * Groups every type the given controllers expose by the schema name it gets.
   *
   * @param controllers the controller classes to start from
   * @return schema name to the classes that get it, sorted by name
   */
  public static @NotNull Map<String, Set<String>> bySchemaName(
      @NotNull Collection<Class<?>> controllers) {
    Set<Class<?>> exposed = new HashSet<>();
    for (Class<?> controller : controllers) {
      for (Method method : controller.getDeclaredMethods()) {
        if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
          continue;
        }
        walk(method.getGenericReturnType(), exposed);
        for (Parameter parameter : method.getParameters()) {
          if (parameter.isAnnotationPresent(RequestBody.class)
              || parameter.isAnnotationPresent(RequestPart.class)) {
            walk(parameter.getParameterizedType(), exposed);
          }
        }
      }
    }
    Map<String, Set<String>> byName = new TreeMap<>();
    for (Class<?> type : exposed) {
      byName.computeIfAbsent(schemaName(type), name -> new TreeSet<>()).add(type.getName());
    }
    return byName;
  }

  /**
   * Keeps the schema names more than one exposed type gets.
   *
   * @param bySchemaName the grouping {@link #bySchemaName} returns
   * @return each colliding name with its classes, sorted
   */
  public static @NotNull Map<String, Set<String>> collisions(
      @NotNull Map<String, Set<String>> bySchemaName) {
    Map<String, Set<String>> collisions = new TreeMap<>();
    bySchemaName.forEach(
        (name, classes) -> {
          if (classes.size() > 1) {
            collisions.put(name, classes);
          }
        });
    return collisions;
  }

  /**
   * The schema name springdoc gives a class.
   *
   * @param type the exposed class
   * @return the explicit {@code @Schema(name)}, or the simple name
   */
  public static @NotNull String schemaName(@NotNull Class<?> type) {
    Schema schema = type.getAnnotation(Schema.class);
    return schema != null && !schema.name().isEmpty() ? schema.name() : type.getSimpleName();
  }

  /**
   * Records a type and everything it reaches.
   *
   * @param type the type to visit
   * @param exposed the accumulator and cycle guard
   */
  private static void walk(Type type, Set<Class<?>> exposed) {
    switch (type) {
      case ParameterizedType parameterized -> {
        walk(parameterized.getRawType(), exposed);
        for (Type argument : parameterized.getActualTypeArguments()) {
          walk(argument, exposed);
        }
      }
      case GenericArrayType array -> walk(array.getGenericComponentType(), exposed);
      case WildcardType wildcard -> {
        for (Type bound : wildcard.getUpperBounds()) {
          walk(bound, exposed);
        }
      }
      case Class<?> rawClass -> walkClass(rawClass, exposed);
      default -> {}
    }
  }

  /**
   * Records an own class and walks its properties.
   *
   * @param type the class to visit
   * @param exposed the accumulator and cycle guard
   */
  private static void walkClass(Class<?> type, Set<Class<?>> exposed) {
    if (type.isArray()) {
      walk(type.getComponentType(), exposed);
      return;
    }
    if (type.isEnum()
        || type.isPrimitive()
        || !type.getName().startsWith(OWN_PACKAGE)
        || !exposed.add(type)) {
      return;
    }
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        if (!component.getAccessor().isAnnotationPresent(JsonIgnore.class)) {
          walk(component.getGenericType(), exposed);
        }
      }
      return;
    }
    for (Class<?> current = type;
        current != null && current.getName().startsWith(OWN_PACKAGE);
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)
            || Modifier.isTransient(modifiers)
            || field.isAnnotationPresent(JsonIgnore.class)) {
          continue;
        }
        walk(field.getGenericType(), exposed);
      }
    }
  }
}
