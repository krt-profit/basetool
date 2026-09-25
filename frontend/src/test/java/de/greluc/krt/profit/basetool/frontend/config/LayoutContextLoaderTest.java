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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.config.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader.LayoutContext;
import de.greluc.krt.profit.basetool.frontend.config.LayoutContextLoader.MeLayoutResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.support.LayoutResponses;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unit tests for {@link LayoutContextLoader} (FE-PERF-01): the request-scoped memo that turns the
 * layout advices' four backend reads into one {@code GET /api/v1/me/layout}, and the handler test
 * that skips even that one for a handler which can never render the model.
 */
@ExtendWith(MockitoExtension.class)
class LayoutContextLoaderTest {

  @Mock private BackendApiClient backendApiClient;
  @Mock private FrontendAuthHelperService authHelper;

  private LayoutContextLoader loader() {
    return new LayoutContextLoader(backendApiClient, authHelper);
  }

  @Test
  void theThreeLayoutAdvicesShareOneBackendReadPerRequest() {
    UUID home = UUID.randomUUID();
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(false);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(
            new MeLayoutResponse(
                home,
                List.of(new OrgUnitMembershipOptionDto(home, "IRIDIUM", "IRI", "SQUADRON", true)),
                new CapabilitiesResponse(true, true, false),
                3L));
    LayoutContextLoader loader = loader();
    MockHttpServletRequest request = new MockHttpServletRequest();

    OrgUnitContextAdvice orgUnits = new OrgUnitContextAdvice(backendApiClient, loader, authHelper);
    CapabilityFlagsAdvice capabilities = new CapabilityFlagsAdvice(loader, authHelper);
    LayoutMiscAdvice misc = new LayoutMiscAdvice(loader, new StaticMessageSource());

    assertThat(orgUnits.activeSquadronId(request)).isEqualTo(home);
    assertThat(orgUnits.availableOrgUnits(request)).hasSize(1);
    assertThat(capabilities.meCapabilities(request).canViewJobOrders()).isTrue();
    assertThat(misc.unreadNotificationCount(request)).isEqualTo(3L);

    verify(backendApiClient, times(1)).get(LayoutResponses.PATH, MeLayoutResponse.class);
  }

  @Test
  void aSecondRequestReadsAgain() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(LayoutResponses.capabilities(true, false, false));
    LayoutContextLoader loader = loader();

    loader.load(new MockHttpServletRequest());
    loader.load(new MockHttpServletRequest());

    verify(backendApiClient, times(2)).get(LayoutResponses.PATH, MeLayoutResponse.class);
  }

  @Test
  void anAnonymousCallerGetsTheEmptyContextWithoutACall() {
    when(authHelper.isAuthenticated()).thenReturn(false);

    assertThat(loader().load(new MockHttpServletRequest())).isSameAs(LayoutContext.NONE);
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void aFailedReadFailsClosedAndIsNotRetriedWithinTheRequest() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenThrow(new RuntimeException("boom"));
    LayoutContextLoader loader = loader();
    MockHttpServletRequest request = new MockHttpServletRequest();

    LayoutContext first = loader.load(request);
    LayoutContext second = loader.load(request);

    assertThat(first).isSameAs(LayoutContext.NONE);
    assertThat(second).isSameAs(LayoutContext.NONE);
    assertThat(first.capabilities()).isEqualTo(CapabilitiesResponse.NONE);
    verify(backendApiClient, times(1)).get(LayoutResponses.PATH, MeLayoutResponse.class);
  }

  @Test
  void aMalformedAnswerIsNormalisedRatherThanPassedOn() {
    when(authHelper.isAuthenticated()).thenReturn(true);
    when(backendApiClient.get(LayoutResponses.PATH, MeLayoutResponse.class))
        .thenReturn(new MeLayoutResponse(null, null, null, 0L));

    LayoutContext context = loader().load(new MockHttpServletRequest());

    assertThat(context.orgUnits()).isEmpty();
    assertThat(context.capabilities()).isEqualTo(CapabilitiesResponse.NONE);
  }

  @Test
  void aResponseBodyHandlerCostsNoRead() throws Exception {
    when(authHelper.isAuthenticated()).thenReturn(true);

    LayoutContext context = loader().load(requestFor("json"));

    assertThat(context).isSameAs(LayoutContext.NONE);
    verifyNoInteractions(backendApiClient);
  }

  @Test
  void theHandlerTestDistinguishesEveryShapeThatWritesItsOwnBody() throws Exception {
    assertThat(LayoutContextLoader.needsLayoutModel(requestFor("json"))).isFalse();
    assertThat(LayoutContextLoader.needsLayoutModel(requestFor("entity"))).isFalse();
    assertThat(LayoutContextLoader.needsLayoutModel(requestFor("stream"))).isFalse();
    assertThat(LayoutContextLoader.needsLayoutModel(requestFor("view"))).isTrue();
  }

  @Test
  void aResponseBodyHandlerThatReadsAModelAttributeStillGetsTheModel() throws Exception {
    assertThat(LayoutContextLoader.needsLayoutModel(requestFor("jsonReadingAFlag"))).isTrue();
  }

  @Test
  void aRequestWithNoMatchedHandlerMethodBuildsTheModel() {
    assertThat(LayoutContextLoader.needsLayoutModel(new MockHttpServletRequest())).isTrue();
  }

  /**
   * A request carrying one of {@link Handlers}' methods as the matched handler, the way {@code
   * RequestMappingHandlerMapping} leaves it before the model is built.
   *
   * @param method the name of the {@link Handlers} method to match
   * @return the request
   * @throws NoSuchMethodException when the name matches no method
   */
  private static MockHttpServletRequest requestFor(String method) throws NoSuchMethodException {
    HandlerMethod handler =
        switch (method) {
          case "jsonReadingAFlag" ->
              new HandlerMethod(new Handlers(), Handlers.class.getMethod(method, boolean.class));
          case "view" ->
              new HandlerMethod(new Handlers(), Handlers.class.getMethod(method, Model.class));
          default -> new HandlerMethod(new Handlers(), Handlers.class.getMethod(method));
        };
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
    return request;
  }

  /** One handler of each shape {@link LayoutContextLoader#decide(HandlerMethod)} tells apart. */
  static final class Handlers {

    /**
     * A JSON handler.
     *
     * @return a body
     */
    @ResponseBody
    public String json() {
      return "{}";
    }

    /**
     * A handler returning an entity.
     *
     * @return a body
     */
    public ResponseEntity<String> entity() {
      return ResponseEntity.ok("{}");
    }

    /**
     * A streaming handler.
     *
     * @return an emitter
     */
    public SseEmitter stream() {
      return new SseEmitter();
    }

    /**
     * A view handler.
     *
     * @param model the model
     * @return a view name
     */
    public String view(Model model) {
      return "index";
    }

    /**
     * A JSON handler that reads a layout attribute.
     *
     * @param canViewJobOrders the flag
     * @return a body
     */
    @ResponseBody
    public String jsonReadingAFlag(@ModelAttribute("canViewJobOrders") boolean canViewJobOrders) {
      return String.valueOf(canViewJobOrders);
    }
  }
}
