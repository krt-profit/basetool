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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Proves the route/gate snapshot and the per-handler gate rule able to fail (REQ-FE-025), on
 * planted fixture controllers the application's component scan never sees: they carry no
 * stereotype, and a test mapping registers them directly.
 */
class RouteGateSnapshotRuleTest {

  @Test
  void aHandlerMovedOutOfAClassGatedControllerIsReportedAndChangesItsLine() {
    Map<RequestMappingInfo, HandlerMethod> before = mappingsOf(new GatedFixtureController());
    Map<RequestMappingInfo, HandlerMethod> after = mappingsOf(new UngatedFixtureController());

    assertThat(RouteGateSnapshot.ungatedHandlers(before.values(), Set.of())).isEmpty();
    assertThat(RouteGateSnapshot.ungatedHandlers(after.values(), Set.of()))
        .containsExactly("UngatedFixtureController#moved");
    assertThat(RouteGateSnapshot.lines(before))
        .containsExactly(
            "/fixture/moved GET -> GatedFixtureController#moved"
                + " gate=class:hasRole('ADMIN') layout=yes",
            "/fixture/own POST -> GatedFixtureController#own"
                + " gate=method:hasRole('LOGISTICIAN') layout=yes");
    assertThat(RouteGateSnapshot.lines(after))
        .contains("/fixture/moved GET -> UngatedFixtureController#moved gate=none layout=no");
  }

  @Test
  void anAllowListedHandlerIsNotReportedButOnlyByItsExactName() {
    List<HandlerMethod> handlers = List.copyOf(mappingsOf(new UngatedFixtureController()).values());

    assertThat(
            RouteGateSnapshot.ungatedHandlers(handlers, Set.of("UngatedFixtureController#moved")))
        .isEmpty();
    assertThat(RouteGateSnapshot.ungatedHandlers(handlers, Set.of("UngatedFixtureController")))
        .containsExactly("UngatedFixtureController#moved");
  }

  @Test
  void aChangedExpressionChangesTheLine() {
    List<String> lines = RouteGateSnapshot.lines(mappingsOf(new GatedFixtureController()));

    assertThat(lines).noneMatch(line -> line.contains("hasRole('OFFICER')"));
    assertThat(RouteGateSnapshot.lines(mappingsOf(new WeakenedFixtureController())))
        .containsExactly(
            "/fixture/moved GET -> WeakenedFixtureController#moved"
                + " gate=class:isAuthenticated() layout=yes",
            "/fixture/own POST -> WeakenedFixtureController#own"
                + " gate=method:hasRole('LOGISTICIAN') layout=yes");
  }

  @Test
  void aFrameworkHandlerIsOutsideTheRule() throws NoSuchMethodException {
    List<HandlerMethod> framework =
        List.of(
            new HandlerMethod(
                new org.springframework.web.servlet.mvc.ParameterizableViewController(),
                "getViewName"));

    assertThat(RouteGateSnapshot.applicationHandlers(framework)).isEmpty();
  }

  /**
   * Detects the handler methods of one fixture object the way the dispatcher does.
   *
   * @param fixture the fixture controller instance
   * @return its mappings
   */
  private static Map<RequestMappingInfo, HandlerMethod> mappingsOf(Object fixture) {
    FixtureMapping mapping = new FixtureMapping();
    mapping.register(fixture);
    return mapping.getHandlerMethods();
  }

  /**
   * A {@link RequestMappingHandlerMapping} over an empty context that accepts any object as a
   * handler, so a fixture needs no {@code @Controller} the component scan would pick up.
   */
  private static final class FixtureMapping extends RequestMappingHandlerMapping {

    /** Creates the mapping over an empty, refreshed web context. */
    FixtureMapping() {
      GenericWebApplicationContext context =
          new GenericWebApplicationContext(new MockServletContext());
      context.refresh();
      setApplicationContext(context);
      afterPropertiesSet();
    }

    /**
     * Accepts every type, unlike the dispatcher, which requires {@code @Controller}.
     *
     * @param beanType the candidate type
     * @return always {@code true}
     */
    @Override
    protected boolean isHandler(Class<?> beanType) {
      return true;
    }

    /**
     * Registers the handler methods of one object.
     *
     * @param handler the fixture instance
     */
    void register(Object handler) {
      detectHandlerMethods(handler);
    }
  }

  /** A class-gated fixture: one handler inherits the class gate, one declares its own. */
  @UsesLayoutModel
  @RequestMapping("/fixture")
  @PreAuthorize("hasRole('ADMIN')")
  static class GatedFixtureController {

    /**
     * Inherits the class-level gate.
     *
     * @return a view name
     */
    @GetMapping("/moved")
    public String moved() {
      return "fixture";
    }

    /**
     * Declares its own gate.
     *
     * @return a view name
     */
    @PostMapping("/own")
    @PreAuthorize("hasRole('LOGISTICIAN')")
    public String own() {
      return "fixture";
    }
  }

  /** The same handler after a move into a class that carries no gate. */
  @RequestMapping("/fixture")
  static class UngatedFixtureController {

    /**
     * Carries no gate of its own and inherits none.
     *
     * @return a view name
     */
    @GetMapping("/moved")
    public String moved() {
      return "fixture";
    }
  }

  /** The gated fixture with its class gate weakened, which the snapshot must show as a diff. */
  @UsesLayoutModel
  @RequestMapping("/fixture")
  @PreAuthorize("isAuthenticated()")
  static class WeakenedFixtureController {

    /**
     * Inherits the weakened class-level gate.
     *
     * @return a view name
     */
    @GetMapping("/moved")
    public String moved() {
      return "fixture";
    }

    /**
     * Declares its own gate.
     *
     * @return a view name
     */
    @PostMapping("/own")
    @PreAuthorize("hasRole('LOGISTICIAN')")
    public String own() {
      return "fixture";
    }
  }
}
