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

package de.greluc.krt.profit.basetool.testsupport.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.bean.override.BeanOverrideHandler;

/**
 * Computes the keys under which Spring's test-context cache stores the application contexts of a
 * module's test classes, without starting any context (REQ-OPS-041).
 *
 * <p>The key is the {@link MergedContextConfiguration} Spring's own bootstrapper builds for a test
 * class, so two classes share a context exactly when their keys are equal. That includes the
 * {@code @MockitoBean} and {@code @MockitoSpyBean} fields: a by-type override is keyed by its bean
 * type <em>and its field name</em>, so the same mock under two field names splits a context.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestContextKeys {

  private static final String SPRING_EXTENSION =
      "org.springframework.test.context.junit.jupiter.SpringExtension";

  private static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";

  private static final String NESTED = "org.junit.jupiter.api.Nested";

  private static final String BOOTSTRAP_MARKER =
      "org.springframework.boot.test.context.SpringBootTestContextBootstrapper";

  private static final Set<String> TYPE_LEVEL_OVERRIDES = Set.of("MockitoBean", "MockitoSpyBean");

  /**
   * Lists the concrete test classes below a compiled test-class root that run with Spring's JUnit
   * extension, top-level classes and their {@code @Nested} inner classes alike.
   *
   * @param classesRoot the root of the compiled test classes, e.g. {@code build/classes/java/test}
   * @param loader the class loader that loads them without initialising them
   * @return the Spring test classes, sorted by name; none when the root does not exist
   */
  public static @NotNull @Unmodifiable List<Class<?>> springTestClasses(
      @NotNull Path classesRoot, @NotNull ClassLoader loader) {
    List<Class<?>> classes = new ArrayList<>();
    for (String name : classNames(classesRoot)) {
      Class<?> candidate = load(name, loader);
      if (isConcreteTestClass(candidate) && runsWithSpring(candidate)) {
        classes.add(candidate);
      }
    }
    classes.sort(Comparator.comparing(Class::getName));
    return List.copyOf(classes);
  }

  /**
   * Groups test classes by the context-cache key Spring builds for each of them.
   *
   * @param testClasses the Spring test classes
   * @return one entry per distinct key, the largest group first, each group sorted by class name
   */
  public static @NotNull @Unmodifiable Map<MergedContextConfiguration, List<Class<?>>> group(
      @NotNull Collection<Class<?>> testClasses) {
    Map<MergedContextConfiguration, List<Class<?>>> groups = new LinkedHashMap<>();
    for (Class<?> testClass : testClasses) {
      MergedContextConfiguration key =
          BootstrapUtils.resolveTestContextBootstrapper(testClass)
              .buildMergedContextConfiguration();
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(testClass);
    }
    Map<MergedContextConfiguration, List<Class<?>>> sorted = new LinkedHashMap<>();
    groups.entrySet().stream()
        .sorted(
            Comparator.comparing(
                    (Map.Entry<MergedContextConfiguration, List<Class<?>>> e) ->
                        e.getValue().size())
                .reversed()
                .thenComparing(e -> e.getValue().getFirst().getName()))
        .forEach(
            e ->
                sorted.put(
                    e.getKey(),
                    e.getValue().stream().sorted(Comparator.comparing(Class::getName)).toList()));
    return Collections.unmodifiableMap(sorted);
  }

  /**
   * Renders the groups as a readable report, one block per distinct context.
   *
   * @param groups the groups {@link #group(Collection)} returned
   * @return the report, naming each group's size, its classes and what sets its key apart
   */
  public static @NotNull String report(
      @NotNull Map<MergedContextConfiguration, List<Class<?>>> groups) {
    StringBuilder out = new StringBuilder();
    int index = 1;
    for (Map.Entry<MergedContextConfiguration, List<Class<?>>> entry : groups.entrySet()) {
      out.append(String.format("#%d  %d class(es)%n", index++, entry.getValue().size()));
      out.append("    ").append(describe(entry.getKey(), entry.getValue().getFirst())).append('\n');
      for (Class<?> testClass : entry.getValue()) {
        out.append("      ").append(testClass.getName()).append('\n');
      }
    }
    return out.toString();
  }

  /**
   * Describes what sets one context key apart: its configuration classes, inlined properties,
   * profiles, non-override customizers and bean overrides.
   *
   * @param key the context key
   * @param sample one test class with that key, whose bean-override fields are listed
   * @return a one-line description
   */
  public static @NotNull String describe(
      @NotNull MergedContextConfiguration key, @NotNull Class<?> sample) {
    List<String> parts = new ArrayList<>();
    parts.add("classes=" + Arrays.stream(key.getClasses()).map(Class::getSimpleName).toList());
    if (key.getPropertySourceProperties().length > 0) {
      parts.add(
          "properties="
              + Arrays.stream(key.getPropertySourceProperties())
                  .filter(p -> !p.startsWith(BOOTSTRAP_MARKER))
                  .toList());
    }
    if (!key.getPropertySourceDescriptors().isEmpty()) {
      parts.add("propertySources=" + key.getPropertySourceDescriptors());
    }
    if (key.getActiveProfiles().length > 0) {
      parts.add("profiles=" + Arrays.toString(key.getActiveProfiles()));
    }
    if (!key.getContextInitializerClasses().isEmpty()) {
      parts.add(
          "initializers="
              + key.getContextInitializerClasses().stream().map(Class::getSimpleName).toList());
    }
    parts.add("customizers=" + customizerNames(key));
    parts.add("overrides=" + overrides(sample));
    return String.join(" ", parts);
  }

  /**
   * Names the context customizers of a key other than the bean-override one.
   *
   * @param key the context key
   * @return the customizers' simple class names, sorted
   */
  private static List<String> customizerNames(@NotNull MergedContextConfiguration key) {
    return key.getContextCustomizers().stream()
        .map(ContextCustomizer::getClass)
        .map(Class::getSimpleName)
        .filter(name -> !"BeanOverrideContextCustomizer".equals(name))
        .sorted()
        .toList();
  }

  /**
   * Lists the bean-override fields a test class declares, inherits or takes from its enclosing
   * classes.
   *
   * @param testClass the test class
   * @return {@code Kind Type field} entries, sorted
   */
  private static List<String> overrides(@NotNull Class<?> testClass) {
    TreeSet<String> entries = new TreeSet<>();
    for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
      for (BeanOverrideHandler handler : BeanOverrideHandler.forTestClass(c)) {
        String kind = handler.getClass().getSimpleName().replace("OverrideHandler", "");
        String type = handler.getBeanType().toClass().getSimpleName();
        String field = handler.getField() == null ? "-" : handler.getField().getName();
        String name = handler.getBeanName() == null ? "" : " name=" + handler.getBeanName();
        entries.add(kind + " " + type + " " + field + name);
      }
      MergedAnnotations.from(c, SearchStrategy.TYPE_HIERARCHY).stream()
          .filter(a -> TYPE_LEVEL_OVERRIDES.contains(a.getType().getSimpleName()))
          .forEach(
              a -> {
                for (Class<?> type : a.getClassArray("types")) {
                  entries.add(a.getType().getSimpleName() + " " + type.getSimpleName() + " -");
                }
              });
      if (Modifier.isStatic(c.getModifiers())) {
        break;
      }
    }
    return List.copyOf(entries);
  }

  /**
   * Tells whether a class is one JUnit runs as a test class: concrete, and either top-level or a
   * {@code @Nested} inner class.
   *
   * @param candidate the loaded class
   * @return {@code true} for a concrete top-level class or a {@code @Nested} inner class
   */
  private static boolean isConcreteTestClass(@NotNull Class<?> candidate) {
    if (candidate.isInterface()
        || candidate.isAnnotation()
        || candidate.isEnum()
        || candidate.isRecord()
        || candidate.isAnonymousClass()
        || candidate.isLocalClass()
        || Modifier.isAbstract(candidate.getModifiers())) {
      return false;
    }
    if (!candidate.isMemberClass()) {
      return true;
    }
    return !Modifier.isStatic(candidate.getModifiers())
        && MergedAnnotations.from(candidate).isPresent(NESTED);
  }

  /**
   * Tells whether JUnit runs a class with Spring's extension, declared on it, a superclass, an
   * enclosing class or a meta-annotation such as {@code @SpringBootTest}.
   *
   * @param testClass the test class
   * @return {@code true} when {@code SpringExtension} is among its extensions
   */
  private static boolean runsWithSpring(@NotNull Class<?> testClass) {
    return MergedAnnotations.search(SearchStrategy.TYPE_HIERARCHY)
        .withEnclosingClasses(c -> !Modifier.isStatic(c.getModifiers()))
        .from(testClass)
        .stream(EXTEND_WITH)
        .map(MergedAnnotation::asAnnotationAttributes)
        .flatMap(attributes -> Arrays.stream((Object[]) attributes.get("value")))
        .anyMatch(
            extension ->
                extension instanceof Class<?> type && SPRING_EXTENSION.equals(type.getName())
                    || SPRING_EXTENSION.equals(extension));
  }

  /**
   * Lists the binary names of the classes below a compiled-class root.
   *
   * @param classesRoot the root
   * @return the names, sorted; none when the root does not exist
   */
  private static List<String> classNames(@NotNull Path classesRoot) {
    if (!Files.isDirectory(classesRoot)) {
      return List.of();
    }
    try (Stream<Path> tree = Files.walk(classesRoot)) {
      return tree.filter(Files::isRegularFile)
          .map(p -> classesRoot.relativize(p).toString().replace('\\', '/'))
          .filter(p -> p.endsWith(".class"))
          .filter(p -> !p.endsWith("module-info.class") && !p.endsWith("package-info.class"))
          .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Loads a class without initialising it.
   *
   * @param name the binary name
   * @param loader the class loader
   * @return the class
   * @throws IllegalStateException when the class cannot be loaded
   */
  private static Class<?> load(@NotNull String name, @NotNull ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IllegalStateException("cannot load test class " + name, e);
    }
  }
}
