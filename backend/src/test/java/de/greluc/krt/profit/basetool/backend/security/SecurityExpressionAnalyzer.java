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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.BooleanLiteral;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.IntLiteral;
import org.springframework.expression.spel.ast.LongLiteral;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.NullLiteral;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * Parses a method-security expression and checks it against the constant-SpEL rules of REQ-SEC-075,
 * collecting every {@code @bean.method(args)} reference for resolution.
 *
 * <p>The allowed forms are: literals; {@code and}, {@code or}, {@code not}, {@code ==}, {@code !=};
 * a root function call such as {@code hasRole('X')} whose arguments are values; {@code #variable}
 * and {@code authentication}/{@code principal} followed only by property reads and zero-argument
 * calls; and {@code @bean.method(values)} at the top level of the expression, never as an argument
 * and never followed by a further call. Every other node — {@code T(...)} outside {@link
 * #ALLOWED_TYPE_REFERENCES}, {@code +} and the other arithmetic operators, {@code new}, assignment,
 * {@code #function()}, indexers, projections, selections, inline lists and maps, {@code matches}
 * and the Elvis and safe-navigation forms — is a violation.
 */
final class SecurityExpressionAnalyzer {

  /** Type references a security expression may use; none today. */
  static final Set<String> ALLOWED_TYPE_REFERENCES = Set.of();

  /** The parser, configured as Spring Security's default expression handler configures it. */
  private static final SpelExpressionParser PARSER = new SpelExpressionParser();

  /** Not instantiable. */
  private SecurityExpressionAnalyzer() {}

  /**
   * One {@code @bean.method(args)} reference.
   *
   * @param bean the bean name after {@code @}
   * @param method the method name
   * @param arity the number of arguments passed
   * @param origin where the expression was declared, for messages
   */
  record BeanCall(String bean, String method, int arity, String origin) {}

  /**
   * The outcome of analysing one expression.
   *
   * @param beanCalls the bean references found, in source order
   * @param violations the rule violations found, each naming the origin and the construct
   */
  record Analysis(List<BeanCall> beanCalls, List<String> violations) {}

  /**
   * Parses and checks one expression.
   *
   * @param expression the expression text as compiled into the annotation
   * @param origin where it was declared, for messages
   * @return the bean references and the violations; an unparsable expression is one violation
   */
  static Analysis analyze(String expression, String origin) {
    List<BeanCall> calls = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    SpelNode root;
    try {
      root = ((SpelExpression) PARSER.parseExpression(expression)).getAST();
    } catch (ParseException e) {
      violations.add(origin + ": does not parse: " + e.getMessage());
      return new Analysis(calls, violations);
    }
    new Walker(origin, expression, calls, violations).top(root);
    return new Analysis(calls, violations);
  }

  /**
   * Resolves bean references against bean types by name, method name and arity.
   *
   * @param calls the references to resolve
   * @param beanTypes maps a bean name to the bean's user class, or to {@code null} when no such
   *     bean exists
   * @return one failure per unresolvable reference; empty when all resolve
   */
  static List<String> unresolved(
      List<BeanCall> calls, Function<String, @Nullable Class<?>> beanTypes) {
    List<String> failures = new ArrayList<>();
    for (BeanCall call : calls) {
      Class<?> type = beanTypes.apply(call.bean());
      if (type == null) {
        failures.add(call.origin() + ": no bean named '" + call.bean() + "'");
        continue;
      }
      boolean named = false;
      boolean matched = false;
      for (Method method : type.getMethods()) {
        if (!method.getName().equals(call.method())) {
          continue;
        }
        named = true;
        int parameters = method.getParameterCount();
        if (parameters == call.arity() || (method.isVarArgs() && call.arity() >= parameters - 1)) {
          matched = true;
          break;
        }
      }
      if (!matched) {
        failures.add(
            call.origin()
                + ": @"
                + call.bean()
                + "."
                + call.method()
                + " with "
                + call.arity()
                + " argument(s) "
                + (named ? "has no overload of that arity" : "does not exist")
                + " on "
                + type.getSimpleName());
      }
    }
    return failures;
  }

  /** Walks one expression tree and records bean calls and violations. */
  private static final class Walker {

    /** Where the expression was declared. */
    private final String origin;

    /** The expression text, for messages. */
    private final String expression;

    /** Collected bean references. */
    private final List<BeanCall> calls;

    /** Collected violations. */
    private final List<String> violations;

    /**
     * Creates a walker that appends to the given lists.
     *
     * @param origin where the expression was declared
     * @param expression the expression text
     * @param calls the list bean references are appended to
     * @param violations the list violations are appended to
     */
    Walker(String origin, String expression, List<BeanCall> calls, List<String> violations) {
      this.origin = origin;
      this.expression = expression;
      this.calls = calls;
      this.violations = violations;
    }

    /**
     * Checks a node in a boolean position: the root or an operand of a logical or comparison
     * operator.
     *
     * @param node the node
     */
    void top(SpelNode node) {
      if (node instanceof OpAnd
          || node instanceof OpOr
          || node instanceof OperatorNot
          || node instanceof OpEQ
          || node instanceof OpNE) {
        children(node).forEach(this::top);
      } else if (node instanceof CompoundExpression
          && node.getChildCount() > 0
          && node.getChild(0) instanceof BeanReference bean) {
        beanCall(node, bean);
      } else if (node instanceof MethodReference function) {
        children(function).forEach(this::value);
      } else {
        value(node);
      }
    }

    /**
     * Checks a {@code @bean.method(args)} compound and records it.
     *
     * @param node the compound expression
     * @param bean its leading bean reference
     */
    private void beanCall(SpelNode node, BeanReference bean) {
      if (node.getChildCount() != 2 || !(node.getChild(1) instanceof MethodReference method)) {
        violate("@" + bean.getName() + " must be followed by exactly one method call");
        return;
      }
      if (method.isNullSafe()) {
        violate("safe navigation after @" + bean.getName());
      }
      calls.add(new BeanCall(bean.getName(), method.getName(), method.getChildCount(), origin));
      children(method).forEach(this::value);
    }

    /**
     * Checks a node in a value position: a function or bean-method argument, or an operand.
     *
     * @param node the node
     */
    void value(SpelNode node) {
      if (node instanceof StringLiteral
          || node instanceof BooleanLiteral
          || node instanceof NullLiteral
          || node instanceof IntLiteral
          || node instanceof LongLiteral
          || node instanceof VariableReference
          || node instanceof PropertyOrFieldReference) {
        return;
      }
      if (node instanceof CompoundExpression) {
        accessorChain(node);
        return;
      }
      if (node instanceof BeanReference bean) {
        violate("@" + bean.getName() + " used as a value");
        return;
      }
      if (node instanceof TypeReference type) {
        if (!ALLOWED_TYPE_REFERENCES.contains(type.toStringAST())) {
          violate("type reference " + type.toStringAST() + " outside the allow-list");
        }
        return;
      }
      violate("forbidden construct " + node.getClass().getSimpleName());
    }

    /**
     * Checks a compound in a value position: a variable or root property followed only by property
     * reads and zero-argument calls.
     *
     * @param node the compound expression
     */
    private void accessorChain(SpelNode node) {
      SpelNode head = node.getChild(0);
      if (head instanceof BeanReference bean) {
        violate("@" + bean.getName() + " called inside another call's argument");
        return;
      }
      if (head instanceof TypeReference type) {
        if (!ALLOWED_TYPE_REFERENCES.contains(type.toStringAST())) {
          violate("type reference " + type.toStringAST() + " outside the allow-list");
          return;
        }
      } else if (!(head instanceof VariableReference)
          && !(head instanceof PropertyOrFieldReference)) {
        violate("forbidden chain head " + head.getClass().getSimpleName());
        return;
      }
      for (int i = 1; i < node.getChildCount(); i++) {
        SpelNode step = node.getChild(i);
        if (step instanceof PropertyOrFieldReference property && !property.isNullSafe()) {
          continue;
        }
        if (step instanceof MethodReference call
            && call.getChildCount() == 0
            && !call.isNullSafe()) {
          continue;
        }
        violate("forbidden step " + step.toStringAST() + " after " + head.toStringAST());
      }
    }

    /**
     * Records a violation for this expression.
     *
     * @param message what is wrong
     */
    private void violate(String message) {
      violations.add(origin + ": " + message + " in \"" + expression + "\"");
    }

    /**
     * Lists the children of a node.
     *
     * @param node the node
     * @return its children in order
     */
    private static List<SpelNode> children(SpelNode node) {
      List<SpelNode> out = new ArrayList<>();
      for (int i = 0; i < node.getChildCount(); i++) {
        out.add(node.getChild(i));
      }
      return out;
    }
  }
}
