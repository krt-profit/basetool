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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.ObservationAuthorizationManager;
import org.springframework.security.authorization.SingleResultAuthorizationManager;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/**
 * Proves that the authorization matrix of {@link AuthorizationMatrixTest} can fail: a changed
 * annotation, a reordered URL rule or a dropped gate each change a line, and a changed line is
 * reported (REQ-SEC-074).
 */
class AuthorizationMatrixRenderingTest {

  /** A fixture with a class-level gate and one method that overrides it. */
  @PreAuthorize("hasRole('ADMIN')")
  static class ClassGatedFixture {

    /** Inherits the class-level gate. */
    public void inherited() {}

    /** Overrides the class-level gate. */
    @PreAuthorize("isAuthenticated()")
    public void overridden() {}
  }

  /** A fixture without any gate. */
  static class UngatedFixture {

    /** Carries no gate at all. */
    public void open() {}
  }

  /** A fixture service with one gated and one ungated method. */
  static class GatedServiceFixture {

    /**
     * Carries a service-level gate.
     *
     * @param id the id the gate checks
     * @return the id
     */
    @PreAuthorize("@fixtureGate.allows(#id)")
    public String gated(String id) {
      return id;
    }

    /** Carries no gate. */
    public void ungated() {}
  }

  @Test
  @DisplayName("the method annotation wins over the class annotation, and none is rendered")
  void effectiveGateFollowsMethodThenClass() throws NoSuchMethodException {
    assertThat(
            AuthorizationMatrix.effectiveGate(
                ClassGatedFixture.class.getMethod("inherited"), ClassGatedFixture.class))
        .isEqualTo("pre=hasRole('ADMIN')");
    assertThat(
            AuthorizationMatrix.effectiveGate(
                ClassGatedFixture.class.getMethod("overridden"), ClassGatedFixture.class))
        .isEqualTo("pre=isAuthenticated()");
    assertThat(
            AuthorizationMatrix.effectiveGate(
                UngatedFixture.class.getMethod("open"), UngatedFixture.class))
        .isEqualTo("pre=none");
  }

  @Test
  @DisplayName("a changed annotation changes the operation's line and is reported")
  void aChangedAnnotationChangesTheLine() throws NoSuchMethodException {
    List<SecurityFilterChain> chains = List.of(chain(fixtureRules()));
    String before =
        AuthorizationMatrix.render(
            AuthorizationMatrix.operations(
                Map.of(
                    mapping(RequestMethod.GET, "/api/v1/things/{id}"),
                    handler(new ClassGatedFixture(), "inherited")),
                chains),
            List.of());
    String after =
        AuthorizationMatrix.render(
            AuthorizationMatrix.operations(
                Map.of(
                    mapping(RequestMethod.GET, "/api/v1/things/{id}"),
                    handler(new UngatedFixture(), "open")),
                chains),
            List.of());

    assertThat(before)
        .contains(
            "GET /api/v1/things/{id} | ClassGatedFixture#inherited | pre=hasRole('ADMIN') |"
                + " url=GET /api/v1/things/* -> hasAnyAuthority(ROLE_OFFICER)");
    assertThat(AuthorizationMatrix.differences(before, after))
        .containsExactly(
            "- GET /api/v1/things/{id} | ClassGatedFixture#inherited | pre=hasRole('ADMIN') |"
                + " url=GET /api/v1/things/* -> hasAnyAuthority(ROLE_OFFICER)",
            "+ GET /api/v1/things/{id} | UngatedFixture#open | pre=none |"
                + " url=GET /api/v1/things/* -> hasAnyAuthority(ROLE_OFFICER)");
    assertThat(AuthorizationMatrix.differences(before, before.replace("\n", "\r\n"))).isEmpty();
  }

  @Test
  @DisplayName("the URL rule is decided first-match-wins, through an observation wrapper")
  void urlRuleIsFirstMatchWins() {
    List<SecurityFilterChain> chains =
        List.of(
            chain(new ObservationAuthorizationManager<>(ObservationRegistry.NOOP, fixtureRules())));

    assertThat(AuthorizationMatrix.urlRule(chains, "GET", "/api/v1/things/{id}"))
        .isEqualTo("GET /api/v1/things/* -> hasAnyAuthority(ROLE_OFFICER)");
    assertThat(AuthorizationMatrix.urlRule(chains, "POST", "/api/v1/things/{id}"))
        .isEqualTo("/api/v1/things/** -> authenticated");
    assertThat(AuthorizationMatrix.urlRule(chains, "GET", "/api/v1/things/{id}/sub/{*rest}"))
        .isEqualTo("/api/v1/things/** -> authenticated");
    assertThat(AuthorizationMatrix.urlRule(chains, "GET", "/internal/ping"))
        .isEqualTo("anyRequest -> permitAll");
  }

  @Test
  @DisplayName("swapping two URL rules changes the decided rule")
  void reorderedUrlRulesChangeTheDecision() {
    RequestMatcherDelegatingAuthorizationManager swapped =
        RequestMatcherDelegatingAuthorizationManager.builder()
            .add(
                PathPatternRequestMatcher.pathPattern("/api/v1/things/**"),
                AuthenticatedAuthorizationManager.authenticated())
            .add(
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/things/*"),
                AuthorityAuthorizationManager.hasRole("OFFICER"))
            .build();

    assertThat(AuthorizationMatrix.urlRule(List.of(chain(swapped)), "GET", "/api/v1/things/{id}"))
        .isEqualTo("/api/v1/things/** -> authenticated");
    assertThat(AuthorizationMatrix.urlRule(List.of(chain(swapped)), "GET", "/elsewhere"))
        .isEqualTo("unmatched -> denyAll");
  }

  @Test
  @DisplayName("a manager without a rendering fails instead of printing an object identity")
  void unknownManagerFails() {
    AuthorizationManager<Object> custom = (authentication, object) -> null;
    assertThatThrownBy(() -> AuthorizationMatrix.describeManager(custom))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationMatrix.describeManager(SingleResultAuthorizationManager.denyAll()))
        .isEqualTo("denyAll");
  }

  @Test
  @DisplayName("a service gate is listed with its arity, an ungated method is not")
  void serviceGatesListGatedMethodsOnly() {
    assertThat(AuthorizationMatrix.serviceGates(List.of(GatedServiceFixture.class)))
        .containsExactly("GatedServiceFixture#gated(1) | pre=@fixtureGate.allows(#id)");
    assertThat(AuthorizationMatrix.serviceGates(List.of(UngatedFixture.class))).isEmpty();
  }

  @Test
  @DisplayName("every path variable form is replaced by the sample segment")
  void concretePathFillsEveryVariable() {
    assertThat(AuthorizationMatrix.concretePath("/a/{id}/b/{name:[a-z]+}/c/{*rest}"))
        .isEqualTo("/a/x/b/x/c/x");
  }

  /**
   * Builds the fixture URL rules: a verb-specific rule, a broader path rule, then a catch-all.
   *
   * @return the delegating manager
   */
  private static RequestMatcherDelegatingAuthorizationManager fixtureRules() {
    return RequestMatcherDelegatingAuthorizationManager.builder()
        .add(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/things/*"),
            AuthorityAuthorizationManager.hasRole("OFFICER"))
        .add(
            PathPatternRequestMatcher.pathPattern("/api/v1/things/**"),
            AuthenticatedAuthorizationManager.authenticated())
        .add(AnyRequestMatcher.INSTANCE, SingleResultAuthorizationManager.permitAll())
        .build();
  }

  /**
   * Wraps a manager into a catch-all chain with only an authorization filter.
   *
   * @param manager the request authorization manager
   * @return the chain
   */
  private static SecurityFilterChain chain(AuthorizationManager<HttpServletRequest> manager) {
    return new DefaultSecurityFilterChain(
        AnyRequestMatcher.INSTANCE, new AuthorizationFilter(manager));
  }

  /**
   * Builds a request mapping for one verb and path.
   *
   * @param method the verb
   * @param path the path pattern
   * @return the mapping info
   */
  private static RequestMappingInfo mapping(RequestMethod method, String path) {
    return RequestMappingInfo.paths(path).methods(method).build();
  }

  /**
   * Builds a handler method for a fixture instance.
   *
   * @param fixture the fixture instance acting as the controller bean
   * @param name the method name
   * @return the handler method
   * @throws NoSuchMethodException when the fixture lacks the method
   */
  private static HandlerMethod handler(Object fixture, String name) throws NoSuchMethodException {
    Method method = fixture.getClass().getMethod(name);
    return new HandlerMethod(fixture, method);
  }
}
