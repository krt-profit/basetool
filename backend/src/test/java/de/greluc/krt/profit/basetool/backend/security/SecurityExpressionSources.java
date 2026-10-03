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

package de.greluc.krt.profit.basetool.backend.security;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Collects every {@code @PreAuthorize} and {@code @PostAuthorize} expression declared in the
 * backend's main classes, class-level and method-level, read from the compiled annotations so
 * constants are already folded in.
 */
final class SecurityExpressionSources {

  /** The backend's base package. */
  static final String BASE_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  /** The expressions, collected once per test JVM. */
  private static final List<Declared> DECLARED = collect();

  /** Not instantiable. */
  private SecurityExpressionSources() {}

  /**
   * One declared security expression.
   *
   * @param origin {@code SimpleName#method} or {@code SimpleName} for a class-level annotation,
   *     followed by the annotation's simple name
   * @param type the declaring class
   * @param expression the expression text
   */
  record Declared(String origin, Class<?> type, String expression) {}

  /**
   * Returns every declared expression of the backend's main classes.
   *
   * @return the expressions, sorted by origin
   */
  static List<Declared> declared() {
    return DECLARED;
  }

  /**
   * Imports the main classes and reads their security annotations.
   *
   * @return the expressions, sorted by origin
   */
  private static List<Declared> collect() {
    List<Declared> out = new ArrayList<>();
    for (JavaClass javaClass :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE)) {
      Class<?> type = javaClass.reflect();
      add(out, type, type.getSimpleName(), type);
      for (Method method : type.getDeclaredMethods()) {
        if (!method.isSynthetic() && !method.isBridge()) {
          add(out, type, type.getSimpleName() + "#" + method.getName(), method);
        }
      }
    }
    out.sort(Comparator.comparing(Declared::origin).thenComparing(Declared::expression));
    return List.copyOf(out);
  }

  /**
   * Adds the security expressions declared directly on one element.
   *
   * @param out the list to append to
   * @param type the declaring class
   * @param origin the element's name for messages
   * @param element the class or method
   */
  private static void add(
      List<Declared> out, Class<?> type, String origin, AnnotatedElement element) {
    PreAuthorize pre = element.getDeclaredAnnotation(PreAuthorize.class);
    if (pre != null) {
      out.add(new Declared(origin + " @PreAuthorize", type, pre.value()));
    }
    PostAuthorize post = element.getDeclaredAnnotation(PostAuthorize.class);
    if (post != null) {
      out.add(new Declared(origin + " @PostAuthorize", type, post.value()));
    }
  }
}
