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

package de.greluc.krt.profit.basetool.backend.exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Collects the problem codes the backend registers and the ones its code produces, so the registry
 * test can compare them (REQ-API-019, ADR-0235).
 */
final class ProblemCodeRegistry {

  /** The package the backend's classes live in. */
  private static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  /** A code-shaped string: upper snake case, at least two characters. */
  private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]+");

  /**
   * The source shapes that put a literal into a problem's {@code code}: a {@code
   * setProperty("code", "…")} call, a {@code CODE_… = "…"} constant, a hand-built {@code
   * "code":"…"} JSON fragment, and a literal code following a {@code problem.…} message key and a
   * type suffix in a problem builder's arguments.
   */
  private static final List<Pattern> LITERAL_CODE_SITES =
      List.of(
          Pattern.compile("setProperty\\(\\s*\"code\"\\s*,\\s*\"([A-Z][A-Z0-9_]+)\""),
          Pattern.compile("CODE_\\w+\\s*=\\s*\"([A-Z][A-Z0-9_]+)\""),
          Pattern.compile("\\\\\"code\\\\\"\\s*:\\s*\\\\\"([A-Z][A-Z0-9_]+)\\\\\""),
          Pattern.compile(
              "\"problem\\.[a-z0-9_.]+\"\\s*,\\s*\"[a-z0-9-]+\"\\s*,\\s*\"([A-Z][A-Z0-9_]+)\""));

  /** Not instantiable. */
  private ProblemCodeRegistry() {}

  /**
   * Finds every main-source enum implementing {@link ProblemCode}.
   *
   * @return the registry enums, sorted by name
   * @throws IllegalStateException if a scanned class cannot be loaded
   */
  static @NotNull @Unmodifiable List<Class<?>> registryEnums() {
    return mainTypes(ProblemCode.class).stream().filter(Class::isEnum).toList();
  }

  /**
   * Finds every concrete main-source class assignable to a supertype.
   *
   * @param supertype the class or interface the found classes extend or implement
   * @return the found classes, sorted by name
   * @throws IllegalStateException if a scanned class cannot be loaded
   */
  static @NotNull @Unmodifiable List<Class<?>> mainTypes(@NotNull Class<?> supertype) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(supertype));
    URL mainOutput = ProblemCode.class.getProtectionDomain().getCodeSource().getLocation();
    List<Class<?>> types = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(BACKEND_PACKAGE)) {
      Class<?> type;
      try {
        type =
            ClassUtils.forName(
                definition.getBeanClassName(), ProblemCodeRegistry.class.getClassLoader());
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("cannot load " + definition.getBeanClassName(), e);
      }
      if (mainOutput.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
        types.add(type);
      }
    }
    types.sort((left, right) -> left.getName().compareTo(right.getName()));
    return List.copyOf(types);
  }

  /**
   * Lists every code of the given registry enums with the enum that declares it.
   *
   * @param enums enums implementing {@link ProblemCode}
   * @return one {@code code -> declaring enums} entry per code; a code declared twice lists both
   */
  static @NotNull Map<String, List<String>> codes(@NotNull Collection<Class<?>> enums) {
    Map<String, List<String>> codes = new TreeMap<>();
    for (Class<?> type : enums) {
      for (Object constant : type.getEnumConstants()) {
        codes
            .computeIfAbsent(((ProblemCode) constant).code(), code -> new ArrayList<>())
            .add(type.getSimpleName() + "." + ((Enum<?>) constant).name());
      }
    }
    return codes;
  }

  /**
   * Reads the code-shaped values of a class's {@code static final String} fields whose name starts
   * with a prefix.
   *
   * @param type the class to read
   * @param prefix the field-name prefix, empty for every field
   * @return the values, sorted
   */
  static @NotNull Set<String> constantCodes(@NotNull Class<?> type, @NotNull String prefix) {
    Set<String> values = new TreeSet<>();
    for (Field field : type.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (!Modifier.isStatic(modifiers)
          || !Modifier.isFinal(modifiers)
          || field.getType() != String.class
          || !field.getName().startsWith(prefix)) {
        continue;
      }
      try {
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof String text && CODE_SHAPE.matcher(text).matches()) {
          values.add(text);
        }
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("cannot read " + type.getName() + "." + field.getName(), e);
      }
    }
    return values;
  }

  /**
   * Finds every code written as a string literal at a site that puts it into a problem body.
   *
   * @param source one Java source file's text
   * @return the literal codes, sorted
   */
  static @NotNull Set<String> literalCodes(@NotNull String source) {
    Set<String> found = new TreeSet<>();
    for (Pattern site : LITERAL_CODE_SITES) {
      Matcher matcher = site.matcher(source);
      while (matcher.find()) {
        found.add(matcher.group(1));
      }
    }
    return found;
  }

  /**
   * Scans a source tree for literal codes, keyed by file.
   *
   * @param root the source root
   * @return relative file path to the literal codes it writes, for files that write any
   */
  static @NotNull Map<String, Set<String>> literalCodesIn(@NotNull Path root) {
    Map<String, Set<String>> found = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        Set<String> codes = literalCodes(Files.readString(file, StandardCharsets.UTF_8));
        if (!codes.isEmpty()) {
          found.put(root.relativize(file).toString().replace('\\', '/'), codes);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return found;
  }
}
