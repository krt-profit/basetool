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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Verifies that the layout {@code @ControllerAdvice} beans do not run for a {@code @RestController}
 * or for a {@code ResponseBody} handler, probed via {@link CsrfTokenController}. That view
 * controllers still receive the model is covered by the render tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class LayoutModelScopeMvcTest {

  /**
   * The backend reads made only to populate the layout model, as they appear in the first argument
   * of a {@link BackendApiClient} call: URI paths and the {@code CachedCatalog} constant name.
   */
  private static final Set<String> LAYOUT_MODEL_BACKEND_CALLS =
      Set.of(
          "/api/v1/me/layout",
          "/api/v1/me/capabilities",
          "/api/v1/notifications/unread-count",
          "/api/v1/me/active-org-unit",
          "/api/v1/me/org-units",
          "SQUADRONS");

  @Autowired private WebApplicationContext context;

  @MockitoBean private WebClient webClient;

  @MockitoBean(name = "termsDocumentClient")
  private WebClient termsDocumentClient;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * An authenticated JSON request must not trigger a single layout-model backend read.
   *
   * <p>{@code BackendRoleSyncFilter} legitimately reads {@code /api/v1/users/me} on the same
   * request, so this asserts on the five layout calls by name rather than on "no interactions at
   * all" — the filter's round trip is a separate concern with its own throttle.
   */
  @Test
  @WithMockUser
  void restControllerRequest_doesNotRunTheLayoutAdvices() throws Exception {
    mockMvc.perform(get("/csrf")).andExpect(status().isOk());

    assertThat(layoutModelCallsMade())
        .as(
            "GET /csrf returns JSON and can never read a model attribute; the layout advices must"
                + " not be invoked for it, and each of these calls is an uncached backend round"
                + " trip spent on a model that is discarded")
        .isEmpty();
  }

  /**
   * The unread-count poll, a {@code ResponseBody} handler inside a view controller, makes exactly
   * one backend call, its own.
   */
  @Test
  @WithMockUser
  void unreadCountPoll_makesExactlyOneBackendCall_itsOwn() throws Exception {
    mockMvc
        .perform(get("/notifications/unread-count").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk());

    assertThat(allBackendCalls())
        .as(
            "the poll's own read is the only backend call it may make; a layout read here means"
                + " the handler test in LayoutContextLoader stopped recognising a ResponseBody"
                + " handler")
        .containsExactly("/api/v1/notifications/unread-count");
  }

  /**
   * A rendered page still receives the whole layout model, but reads it in <em>one</em> call: the
   * three advices share {@code GET /api/v1/me/layout} through the request-scoped memo in {@code
   * LayoutContextLoader}, and none of the four retired reads is made. The cached squadron catalogue
   * is the one other layout read, and it is not a backend call on a cache hit.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void renderedPage_readsTheLayoutOnce() throws Exception {
    mockMvc.perform(get("/")).andExpect(status().isOk());

    List<String> calls = allBackendCalls();
    assertThat(calls.stream().filter("/api/v1/me/layout"::equals).count())
        .as("the three layout advices must share one /api/v1/me/layout read per request")
        .isEqualTo(1L);
    assertThat(calls)
        .as("the four separate layout reads were replaced by /api/v1/me/layout (FE-PERF-01)")
        .doesNotContain(
            "/api/v1/me/capabilities",
            "/api/v1/notifications/unread-count",
            "/api/v1/me/active-org-unit",
            "/api/v1/me/org-units");
  }

  /**
   * Every first argument the mocked client was called with, in call order — the backend path, or
   * the {@code CachedCatalog} constant for {@code getCached}.
   *
   * @return the call identifiers
   */
  private List<String> allBackendCalls() {
    return mockingDetails(backendApiClient).getInvocations().stream()
        .map(invocation -> String.valueOf(invocation.getArguments()[0]))
        .toList();
  }

  /**
   * Collects the layout-model backend reads recorded on the mocked client so far.
   *
   * @return the matched call identifiers, sorted, or an empty set when none were made.
   */
  private Set<String> layoutModelCallsMade() {
    return mockingDetails(backendApiClient).getInvocations().stream()
        .map(invocation -> Arrays.asList(invocation.getArguments()))
        .flatMap(List::stream)
        .map(String::valueOf)
        .filter(LAYOUT_MODEL_BACKEND_CALLS::contains)
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
