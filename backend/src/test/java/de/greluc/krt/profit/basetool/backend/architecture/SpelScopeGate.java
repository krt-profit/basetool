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

import java.beans.Introspector;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@code @PreAuthorize} expression gates on an org-unit scope bean, by walking
 * its SpEL syntax tree rather than matching substrings.
 *
 * <p>An expression is scope-gated when every top-level {@code or} branch either is exactly {@code
 * hasRole('ADMIN')} or contains, as one of its {@code and} operands, a call on one of the scope
 * beans. A branch such as {@code isAuthenticated()} beside a scope call therefore fails the gate.
 */
final class SpelScopeGate {

  private static final SpelExpressionParser PARSER = new SpelExpressionParser();

  private final Set<String> scopeBeanReferences;

  /**
   * Creates a gate check for the given scope-bean types.
   *
   * @param scopeBeanTypes the Spring beans whose methods count as a scope gate in SpEL
   */
  SpelScopeGate(@NotNull Set<Class<?>> scopeBeanTypes) {
    Set<String> references = new LinkedHashSet<>();
    for (Class<?> type : scopeBeanTypes) {
      references.add("@" + beanName(type));
    }
    this.scopeBeanReferences = Set.copyOf(references);
  }

  /**
   * Returns the SpEL bean references this gate accepts, such as {@code @ownerScopeService}.
   *
   * @return the accepted bean references
   */
  @NotNull
  Set<String> scopeBeanReferences() {
    return scopeBeanReferences;
  }

  /**
   * Whether the expression gates every branch on a scope bean or on the admin role alone.
   *
   * @param expression the {@code @PreAuthorize} value; blank counts as ungated
   * @return {@code true} when the expression is scope-gated
   */
  boolean isScopeGated(@NotNull String expression) {
    if (expression.isBlank()) {
      return false;
    }
    SpelExpression parsed = (SpelExpression) PARSER.parseExpression(expression);
    return gated(parsed.getAST());
  }

  private boolean gated(SpelNode node) {
    if (node instanceof OpOr) {
      for (int i = 0; i < node.getChildCount(); i++) {
        if (!gated(node.getChild(i))) {
          return false;
        }
      }
      return true;
    }
    if (node instanceof OpAnd) {
      for (int i = 0; i < node.getChildCount(); i++) {
        if (gated(node.getChild(i))) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof CompoundExpression
        && node.getChildCount() >= 2
        && node.getChild(0) instanceof BeanReference bean) {
      return scopeBeanReferences.contains(bean.toStringAST());
    }
    if (node instanceof MethodReference method
        && "hasRole".equals(method.getName())
        && method.getChildCount() == 1
        && method.getChild(0) instanceof StringLiteral literal) {
      return "ADMIN".equals(literal.getLiteralValue().getValue());
    }
    return false;
  }

  /**
   * Derives the Spring bean name of a component the way component scanning names it.
   *
   * @param type the component class
   * @return the explicit {@code @Component}-family value, or the decapitalised simple name
   */
  @NotNull
  static String beanName(@NotNull Class<?> type) {
    Component component = AnnotatedElementUtils.findMergedAnnotation(type, Component.class);
    if (component != null && !component.value().isBlank()) {
      return component.value();
    }
    return Introspector.decapitalize(type.getSimpleName());
  }
}
