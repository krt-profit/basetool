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

import jakarta.servlet.Filter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Builds the authorization matrix that {@link AuthorizationMatrixTest} compares against {@code
 * src/test/resources/api/authorization-matrix.txt} (REQ-SEC-074).
 *
 * <p>The {@code [operations]} section holds one line per handler mapping, verb and path pattern:
 * {@code VERB PATH | Handler#method | pre=<effective @PreAuthorize> | url=<matching URL rule>}. The
 * effective annotation is the method's, else the class's, else {@code none}; the URL rule is the
 * first {@code authorizeHttpRequests} entry of the first matching filter chain that matches a mock
 * request for that verb and path, decided exactly as {@link
 * RequestMatcherDelegatingAuthorizationManager} decides it. The {@code [service-gates]} section
 * holds one line per gated method of a non-controller bean. Lines sort by path, verb and handler;
 * handler and bean names are simple class names, so a package move leaves the file unchanged.
 */
final class AuthorizationMatrix {

  /** Header line of the handler-mapping section. */
  static final String OPERATIONS_HEADER = "[operations]";

  /** Header line of the service-level gate section. */
  static final String SERVICE_GATES_HEADER = "[service-gates]";

  /** The verbs a mapping without a declared method is expanded to. */
  private static final List<String> VERBLESS_EXPANSION =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE");

  /** A URI template variable, including a regex-constrained or catch-all one. */
  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{\\*?[^/{}]+(?::[^/]*)?}");

  /** The value substituted for every path variable when the mock request is built. */
  private static final String SAMPLE_SEGMENT = "x";

  /** Runs of whitespace inside an expression, collapsed to one space. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Not instantiable. */
  private AuthorizationMatrix() {}

  /**
   * One row of the {@code [operations]} section before rendering.
   *
   * @param verb the HTTP method
   * @param path the path pattern exactly as mapped
   * @param handler the handler as {@code SimpleClassName#method}
   * @param gate the rendered effective method-security gate
   * @param urlRule the rendered matching URL rule
   */
  record Operation(String verb, String path, String handler, String gate, String urlRule) {

    /** Sort order of the section: path, then verb, then handler. */
    static final Comparator<Operation> ORDER =
        Comparator.comparing(Operation::path)
            .thenComparing(Operation::verb)
            .thenComparing(Operation::handler);

    /**
     * Renders the row as its matrix line.
     *
     * @return {@code VERB PATH | handler | gate | url=rule}
     */
    String line() {
      return verb + " " + path + " | " + handler + " | " + gate + " | url=" + urlRule;
    }
  }

  /**
   * Collects one operation per handler mapping, verb and path pattern.
   *
   * @param handlerMethods the handler methods of the request-mapping handler mapping
   * @param chains the security filter chains in the order {@code FilterChainProxy} tries them
   * @return the operations, sorted by {@link Operation#ORDER}
   */
  static List<Operation> operations(
      Map<RequestMappingInfo, HandlerMethod> handlerMethods, List<SecurityFilterChain> chains) {
    List<Operation> operations = new ArrayList<>();
    handlerMethods.forEach(
        (info, handler) -> {
          Set<String> verbs = new TreeSet<>();
          for (RequestMethod method : info.getMethodsCondition().getMethods()) {
            verbs.add(method.name());
          }
          if (verbs.isEmpty()) {
            verbs.addAll(VERBLESS_EXPANSION);
          }
          Class<?> beanType = handler.getBeanType();
          Method method = handler.getMethod();
          String handlerName = beanType.getSimpleName() + "#" + method.getName();
          String gate = effectiveGate(method, beanType);
          for (String path : info.getPatternValues()) {
            for (String verb : verbs) {
              operations.add(
                  new Operation(verb, path, handlerName, gate, urlRule(chains, verb, path)));
            }
          }
        });
    operations.sort(Operation.ORDER);
    return operations;
  }

  /**
   * Renders the effective method-security gate of a handler: the method's annotations, else the
   * class's, else {@code pre=none}.
   *
   * @param method the handler method
   * @param beanType the user class declaring or inheriting it
   * @return {@code pre=<expr>}, followed by {@code | post=<expr>} when a post-check exists
   */
  static String effectiveGate(Method method, Class<?> beanType) {
    PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
    if (pre == null) {
      pre = AnnotatedElementUtils.findMergedAnnotation(beanType, PreAuthorize.class);
    }
    PostAuthorize post = AnnotatedElementUtils.findMergedAnnotation(method, PostAuthorize.class);
    if (post == null) {
      post = AnnotatedElementUtils.findMergedAnnotation(beanType, PostAuthorize.class);
    }
    String rendered = "pre=" + (pre == null ? "none" : normalize(pre.value()));
    return post == null ? rendered : rendered + " | post=" + normalize(post.value());
  }

  /**
   * Collapses whitespace runs in an expression to single spaces.
   *
   * @param expression the SpEL text as compiled into the annotation
   * @return the expression on one line
   */
  static String normalize(String expression) {
    return WHITESPACE.matcher(expression).replaceAll(" ").trim();
  }

  /**
   * Replaces every URI template variable of a path pattern with a fixed sample segment.
   *
   * @param pattern the path pattern as mapped
   * @return a concrete path the pattern matches, for building the mock request
   */
  static String concretePath(String pattern) {
    return PATH_VARIABLE.matcher(pattern).replaceAll(SAMPLE_SEGMENT);
  }

  /**
   * Renders the URL rule that decides a request, first match winning, as Spring Security evaluates
   * it.
   *
   * @param chains the security filter chains in the order {@code FilterChainProxy} tries them
   * @param verb the HTTP method of the request
   * @param pathPattern the mapped path pattern; its variables are filled with a sample segment
   * @return {@code <matcher> -> <manager>}, {@code unmatched -> denyAll} when no entry matches,
   *     prefixed with the chain's matcher when the deciding chain is not an any-request chain
   */
  static String urlRule(List<SecurityFilterChain> chains, String verb, String pathPattern) {
    MockHttpServletRequest request = new MockHttpServletRequest(verb, concretePath(pathPattern));
    for (SecurityFilterChain chain : chains) {
      if (!chain.matches(request)) {
        continue;
      }
      String prefix = chainPrefix(chain);
      RequestMatcherDelegatingAuthorizationManager delegating = delegatingManager(chain);
      if (delegating == null) {
        return prefix + "no authorization filter";
      }
      for (RequestMatcherEntry<AuthorizationManager<?>> entry : mappings(delegating)) {
        if (entry.getRequestMatcher().matcher(request).isMatch()) {
          return prefix
              + describeMatcher(entry.getRequestMatcher())
              + " -> "
              + describeManager(entry.getEntry());
        }
      }
      return prefix + "unmatched -> denyAll";
    }
    return "no filter chain";
  }

  /**
   * Names the chain when it is not the catch-all chain.
   *
   * @param chain the deciding chain
   * @return an empty string for an any-request chain, else {@code chain[<matcher>] }
   */
  private static String chainPrefix(SecurityFilterChain chain) {
    if (chain instanceof DefaultSecurityFilterChain defaultChain) {
      RequestMatcher matcher = defaultChain.getRequestMatcher();
      return matcher instanceof AnyRequestMatcher ? "" : "chain[" + describeMatcher(matcher) + "] ";
    }
    return "chain[" + chain.getClass().getSimpleName() + "] ";
  }

  /**
   * Finds the request-matcher delegating manager behind a chain's {@link AuthorizationFilter},
   * unwrapping decorators such as the observation wrapper.
   *
   * @param chain the chain to inspect
   * @return the delegating manager, or {@code null} when the chain has no authorization filter
   * @throws IllegalStateException when the manager is of a shape this renderer cannot unwrap
   */
  static @Nullable RequestMatcherDelegatingAuthorizationManager delegatingManager(
      SecurityFilterChain chain) {
    for (Filter filter : chain.getFilters()) {
      if (filter instanceof AuthorizationFilter authorizationFilter) {
        Object manager = authorizationFilter.getAuthorizationManager();
        for (int depth = 0; depth < 8; depth++) {
          if (manager instanceof RequestMatcherDelegatingAuthorizationManager delegating) {
            return delegating;
          }
          manager = readField(manager, "delegate");
        }
        throw new IllegalStateException(
            "AuthorizationFilter manager is not a RequestMatcherDelegatingAuthorizationManager");
      }
    }
    return null;
  }

  /**
   * Reads the ordered matcher entries of a delegating manager.
   *
   * @param manager the delegating manager
   * @return its entries in evaluation order
   */
  @SuppressWarnings("unchecked")
  static List<RequestMatcherEntry<AuthorizationManager<?>>> mappings(
      RequestMatcherDelegatingAuthorizationManager manager) {
    return (List<RequestMatcherEntry<AuthorizationManager<?>>>) readField(manager, "mappings");
  }

  /**
   * Renders a URL-rule matcher.
   *
   * @param matcher the matcher of a URL rule or a chain
   * @return {@code VERB /pattern} or {@code /pattern} for a path matcher, {@code anyRequest} for
   *     the catch-all, else the matcher's own {@code toString}
   */
  static String describeMatcher(RequestMatcher matcher) {
    if (matcher instanceof AnyRequestMatcher) {
      return "anyRequest";
    }
    String text = matcher.toString();
    if (matcher instanceof PathPatternRequestMatcher
        && text.startsWith("PathPattern [")
        && text.endsWith("]")) {
      return text.substring("PathPattern [".length(), text.length() - 1);
    }
    return text;
  }

  /**
   * Renders a URL-rule authorization manager.
   *
   * @param manager the manager a URL rule delegates to
   * @return {@code permitAll}, {@code denyAll}, {@code authenticated}, {@code fullyAuthenticated},
   *     {@code rememberMe}, {@code anonymous} or {@code hasAnyAuthority(A,B)} with sorted
   *     authorities
   * @throws IllegalStateException for a manager type this renderer does not know, so a new kind of
   *     rule is rendered deliberately rather than as an object identity
   */
  static String describeManager(Object manager) {
    if (manager instanceof SingleResultAuthorizationManager<?>) {
      AuthorizationResult result = (AuthorizationResult) readField(manager, "result");
      return result.isGranted() ? "permitAll" : "denyAll";
    }
    if (manager instanceof AuthenticatedAuthorizationManager<?>) {
      String strategy = readField(manager, "authorizationStrategy").getClass().getSimpleName();
      return switch (strategy) {
        case "AuthenticatedAuthorizationStrategy" -> "authenticated";
        case "FullyAuthenticatedAuthorizationStrategy" -> "fullyAuthenticated";
        case "RememberMeAuthorizationStrategy" -> "rememberMe";
        case "AnonymousAuthorizationStrategy" -> "anonymous";
        default -> throw new IllegalStateException("Unknown authentication strategy " + strategy);
      };
    }
    if (manager instanceof AuthorityAuthorizationManager<?>) {
      @SuppressWarnings("unchecked")
      Collection<String> authorities = (Collection<String>) readField(manager, "authorities");
      return "hasAnyAuthority(" + String.join(",", new TreeSet<>(authorities)) + ")";
    }
    throw new IllegalStateException(
        "No rendering for URL-rule manager " + manager.getClass().getName());
  }

  /**
   * Collects one line per method-security gate on a non-controller class: per gated method, or
   * {@code #*} for a class-level gate.
   *
   * @param types the user classes of the application's beans
   * @return the lines, sorted
   */
  static List<String> serviceGates(Collection<Class<?>> types) {
    Set<String> lines = new TreeSet<>();
    for (Class<?> type : new LinkedHashSet<>(types)) {
      if (AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
        continue;
      }
      if (AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class)
          || AnnotatedElementUtils.hasAnnotation(type, PostAuthorize.class)) {
        lines.add(type.getSimpleName() + "#* | " + classGate(type));
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic()
            || method.isBridge()
            || Modifier.isPrivate(method.getModifiers())) {
          continue;
        }
        if (AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
            || AnnotatedElementUtils.hasAnnotation(method, PostAuthorize.class)) {
          lines.add(
              type.getSimpleName()
                  + "#"
                  + method.getName()
                  + "("
                  + method.getParameterCount()
                  + ") | "
                  + effectiveGate(method, type));
        }
      }
    }
    return new ArrayList<>(lines);
  }

  /**
   * Renders a class-level gate.
   *
   * @param type the gated class
   * @return {@code pre=<expr>} and, when present, {@code | post=<expr>}
   */
  private static String classGate(Class<?> type) {
    PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
    PostAuthorize post = AnnotatedElementUtils.findMergedAnnotation(type, PostAuthorize.class);
    String rendered = "pre=" + (pre == null ? "none" : normalize(pre.value()));
    return post == null ? rendered : rendered + " | post=" + normalize(post.value());
  }

  /**
   * Renders the whole matrix file: both sections, LF line endings, a trailing newline.
   *
   * @param operations the operations, already sorted
   * @param serviceGates the service-gate lines, already sorted
   * @return the file content
   */
  static String render(List<Operation> operations, List<String> serviceGates) {
    StringBuilder out = new StringBuilder();
    out.append(OPERATIONS_HEADER).append('\n');
    operations.forEach(operation -> out.append(operation.line()).append('\n'));
    out.append('\n').append(SERVICE_GATES_HEADER).append('\n');
    serviceGates.forEach(line -> out.append(line).append('\n'));
    return out.toString();
  }

  /**
   * Lists the lines that differ between two renderings, in a unified-diff-like form.
   *
   * @param expected the committed content; CR characters are ignored
   * @param actual the freshly rendered content
   * @return {@code - line} for every line only in {@code expected}, {@code + line} for every line
   *     only in {@code actual}; empty when the multisets of lines and their order agree
   */
  static List<String> differences(String expected, String actual) {
    List<String> expectedLines = Arrays.asList(expected.replace("\r", "").split("\n", -1));
    List<String> actualLines = Arrays.asList(actual.replace("\r", "").split("\n", -1));
    List<String> out = new ArrayList<>();
    Set<String> expectedSet = new TreeSet<>(expectedLines);
    Set<String> actualSet = new TreeSet<>(actualLines);
    for (String line : expectedSet) {
      if (!actualSet.contains(line)) {
        out.add("- " + line);
      }
    }
    for (String line : actualSet) {
      if (!expectedSet.contains(line)) {
        out.add("+ " + line);
      }
    }
    if (out.isEmpty() && !expectedLines.equals(actualLines)) {
      out.add("~ same lines, different order or multiplicity");
    }
    return out;
  }

  /**
   * Reads a private field, walking up the class hierarchy.
   *
   * @param target the object to read from
   * @param name the field name
   * @return the field value
   * @throws IllegalStateException when no such field exists or it holds {@code null}
   */
  private static @NotNull Object readField(Object target, String name) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(target);
        if (value == null) {
          throw new IllegalStateException(type.getName() + "." + name + " is null");
        }
        return value;
      } catch (NoSuchFieldException ignored) {
        continue;
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Cannot read " + type.getName() + "." + name, e);
      }
    }
    throw new IllegalStateException(target.getClass().getName() + " has no field " + name);
  }
}
