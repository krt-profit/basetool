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

package de.greluc.krt.profit.basetool.frontend.architecture;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Renders the frontend's handler mappings as one canonical line each and evaluates the per-handler
 * gate rule over them (REQ-FE-025).
 *
 * <p>A line reads {@code <patterns> <verbs>[ params=…][ headers=…][ consumes=…][ produces=…] ->
 * <Class>#<method> gate=<source>:<expression> layout=<yes|no>}. The handler is named by its simple
 * class name, so a pure package move leaves every line byte-identical; {@code gate} is the
 * {@code @PreAuthorize} Spring Security evaluates for the handler — the method's, else the class's
 * — and {@code layout} is the {@link UsesLayoutModel} opt-in.
 */
final class RouteGateSnapshot {

  /** Package prefix of the application's own handler classes. */
  static final String APPLICATION_PACKAGE = "de.greluc.krt.profit.basetool.";

  /** The gate value of a handler that carries no {@code @PreAuthorize} at all. */
  static final String NO_GATE = "none";

  /** Not instantiable. */
  private RouteGateSnapshot() {}

  /**
   * Renders every mapping as one line, sorted.
   *
   * @param mappings the handler mappings, as {@code RequestMappingHandlerMapping} reports them
   * @return one line per mapping, in lexicographic order
   */
  static @NotNull @Unmodifiable List<String> lines(
      @NotNull Map<RequestMappingInfo, HandlerMethod> mappings) {
    return mappings.entrySet().stream()
        .map(entry -> line(entry.getKey(), entry.getValue()))
        .sorted()
        .toList();
  }

  /**
   * Renders one mapping.
   *
   * @param info the request condition of the mapping
   * @param handler the handler method it dispatches to
   * @return the canonical line
   */
  static @NotNull String line(@NotNull RequestMappingInfo info, @NotNull HandlerMethod handler) {
    StringBuilder line = new StringBuilder();
    line.append(joined(patterns(info))).append(' ').append(verbs(info));
    appendCondition(line, "params", info.getParamsCondition().getExpressions());
    appendCondition(line, "headers", info.getHeadersCondition().getExpressions());
    appendCondition(line, "consumes", info.getConsumesCondition().getExpressions());
    appendCondition(line, "produces", info.getProducesCondition().getExpressions());
    line.append(" -> ")
        .append(handlerName(handler))
        .append(" gate=")
        .append(effectiveGate(handler.getBeanType(), handler.getMethod()))
        .append(" layout=")
        .append(usesLayoutModel(handler.getBeanType()) ? "yes" : "no");
    return line.toString();
  }

  /**
   * Names a handler by simple class name and method name.
   *
   * @param handler the handler method
   * @return {@code SimpleName#method}
   */
  static @NotNull String handlerName(@NotNull HandlerMethod handler) {
    return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
  }

  /**
   * Resolves the {@code @PreAuthorize} Spring Security applies to a handler: the method's own, else
   * the one on its class, merged through meta-annotations.
   *
   * @param beanType the handler's user class
   * @param method the handler method
   * @return {@code method:<expression>}, {@code class:<expression>} or {@link #NO_GATE}
   */
  static @NotNull String effectiveGate(@NotNull Class<?> beanType, @NotNull Method method) {
    PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
    if (onMethod != null) {
      return "method:" + normalise(onMethod.value());
    }
    PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(beanType, PreAuthorize.class);
    if (onClass != null) {
      return "class:" + normalise(onClass.value());
    }
    return NO_GATE;
  }

  /**
   * The application handlers that carry no effective gate and are not on the allow-list.
   *
   * @param handlers every handler method of the dispatcher
   * @param publicHandlers the exact {@code SimpleName#method} names allowed to be ungated
   * @return the offending handler names, sorted; empty when the rule holds
   */
  static @NotNull @Unmodifiable Set<String> ungatedHandlers(
      @NotNull Collection<HandlerMethod> handlers, @NotNull Set<String> publicHandlers) {
    Set<String> ungated = new TreeSet<>();
    for (HandlerMethod handler : applicationHandlers(handlers)) {
      String name = handlerName(handler);
      if (NO_GATE.equals(effectiveGate(handler.getBeanType(), handler.getMethod()))
          && !publicHandlers.contains(name)) {
        ungated.add(name);
      }
    }
    return Set.copyOf(ungated);
  }

  /**
   * The handlers whose class belongs to the application rather than to a framework.
   *
   * @param handlers every handler method of the dispatcher
   * @return the application's handlers, in input order
   */
  static @NotNull @Unmodifiable List<HandlerMethod> applicationHandlers(
      @NotNull Collection<HandlerMethod> handlers) {
    List<HandlerMethod> own = new ArrayList<>();
    for (HandlerMethod handler : handlers) {
      if (handler.getBeanType().getName().startsWith(APPLICATION_PACKAGE)) {
        own.add(handler);
      }
    }
    return List.copyOf(own);
  }

  /**
   * Whether the handler's class opts into the layout model.
   *
   * @param beanType the handler's user class
   * @return {@code true} when the class carries {@link UsesLayoutModel}
   */
  private static boolean usesLayoutModel(@NotNull Class<?> beanType) {
    return AnnotatedElementUtils.hasAnnotation(beanType, UsesLayoutModel.class);
  }

  /**
   * The path patterns of a mapping.
   *
   * @param info the mapping
   * @return its pattern strings, sorted; empty when it declares none
   */
  private static @NotNull Set<String> patterns(@NotNull RequestMappingInfo info) {
    Set<String> patterns = new TreeSet<>();
    if (info.getPathPatternsCondition() != null) {
      info.getPathPatternsCondition()
          .getPatterns()
          .forEach(pattern -> patterns.add(pattern.getPatternString()));
    }
    return patterns;
  }

  /**
   * The verbs of a mapping.
   *
   * @param info the mapping
   * @return the verbs joined by commas, sorted, or {@code *} when it answers every verb
   */
  private static @NotNull String verbs(@NotNull RequestMappingInfo info) {
    Set<String> verbs =
        info.getMethodsCondition().getMethods().stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
    return verbs.isEmpty() ? "*" : joined(verbs);
  }

  /**
   * Appends {@code name=[a,b]} when the condition holds expressions.
   *
   * @param line the line under construction
   * @param name the condition's label
   * @param expressions the condition's expressions; skipped when empty
   */
  private static void appendCondition(
      @NotNull StringBuilder line, @NotNull String name, @NotNull Collection<?> expressions) {
    if (expressions.isEmpty()) {
      return;
    }
    Set<String> rendered = new TreeSet<>();
    expressions.forEach(expression -> rendered.add(String.valueOf(expression)));
    line.append(' ').append(name).append("=[").append(joined(rendered)).append(']');
  }

  /**
   * Joins values with commas.
   *
   * @param values the values, already ordered
   * @return the joined text
   */
  @Contract(pure = true)
  private static @NotNull String joined(@NotNull Collection<String> values) {
    return String.join(",", values);
  }

  /**
   * Collapses every whitespace run of a SpEL expression into one space.
   *
   * @param expression the annotation value
   * @return the expression on one line, or {@code ""} for {@code null}
   */
  @Contract(pure = true)
  private static @NotNull String normalise(@Nullable String expression) {
    return expression == null ? "" : expression.strip().replaceAll("\\s+", " ");
  }
}
