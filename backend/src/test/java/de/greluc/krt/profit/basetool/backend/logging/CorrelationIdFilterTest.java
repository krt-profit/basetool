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

package de.greluc.krt.profit.basetool.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies the MDC contract of {@link CorrelationIdFilter}:
 *
 * <ul>
 *   <li>inbound correlation id header is honoured and echoed back
 *   <li>missing header produces a fresh UUID
 *   <li>unsafe inbound values are rejected to prevent log injection
 *   <li>JWT {@code sub} is exposed via the {@code userId} MDC key
 *   <li>MDC is cleared in {@code finally} to avoid thread leakage
 * </ul>
 */
class CorrelationIdFilterTest {

  private final LoggingProperties props = BoundProperties.defaults(LoggingProperties.class);
  private final AuthHelperService authHelperService = mock(AuthHelperService.class);
  private final OwnerScopeService ownerScopeService = mock(OwnerScopeService.class);
  private final CorrelationIdFilter filter =
      new CorrelationIdFilter(props, authHelperService, ownerScopeService);

  {
    // Default behaviour: anonymous traffic returns "none" through the filter's defensive
    // fallback. Tests that assert orgUnit-context behaviour can stub these mocks per-case.
    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.empty());
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void missingHeader_ShouldGenerateUuidAndEchoBack() throws ServletException, IOException {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> mdcDuringChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(props.correlationIdMdcKey()));

    // When
    filter.doFilter(request, response, chain);

    // Then
    String echoed = response.getHeader(props.correlationIdHeader());
    assertThat(echoed).isNotBlank();
    assertThat(echoed).hasSize(36); // UUID length with dashes
    assertThat(mdcDuringChain.get()).isEqualTo(echoed);
    // MDC cleaned up in finally
    assertThat(MDC.get(props.correlationIdMdcKey())).isNull();
  }

  @Test
  void inboundHeader_ShouldBeReused() throws ServletException, IOException {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
    request.addHeader(props.correlationIdHeader(), "req-abc-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    assertThat(response.getHeader(props.correlationIdHeader())).isEqualTo("req-abc-123");
  }

  @Test
  void unsafeInboundHeader_ShouldBeReplacedWithUuid() throws ServletException, IOException {
    // Given: CR/LF injection attempt
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.addHeader(props.correlationIdHeader(), "abc\ninjected: evil");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // When
    filter.doFilter(request, response, (req, res) -> {});

    // Then
    String echoed = response.getHeader(props.correlationIdHeader());
    assertThat(echoed).doesNotContain("\n", "injected");
    assertThat(echoed).hasSize(36);
  }

  @Test
  void authenticatedRequest_ShouldPlaceJwtSubIntoMdc() throws ServletException, IOException {
    // Given
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn("user-sub-42");
    JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
    SecurityContextHolder.getContext().setAuthentication(auth);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> userIdDuringChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> userIdDuringChain.set(MDC.get(props.userIdMdcKey()));

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(userIdDuringChain.get()).isEqualTo("user-sub-42");
    assertThat(MDC.get(props.userIdMdcKey())).isNull();
  }

  @Test
  void unauthenticatedRequest_ShouldExposeAnonymousUserId() throws ServletException, IOException {
    // Given: non-JWT principal (e.g. anonymous filter)
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anon", java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> userIdDuringChain = new AtomicReference<>();

    // When
    filter.doFilter(
        request, response, (req, res) -> userIdDuringChain.set(MDC.get(props.userIdMdcKey())));

    // Then
    assertThat(userIdDuringChain.get()).isEqualTo("anonymous");
  }

  @Test
  void initialDispatch_ShouldStashTheResolvedValuesForAnAsyncDispatch()
      throws ServletException, IOException {
    // Given
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notifications");
    request.addHeader(props.correlationIdHeader(), "stash-me");

    // When
    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    // Then
    assertThat(request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE))
        .isEqualTo("stash-me");
    assertThat(request.getAttribute(CorrelationIdFilter.USER_ID_ATTRIBUTE)).isEqualTo("anonymous");
    assertThat(request.getAttribute(CorrelationIdFilter.ORG_UNIT_ID_ATTRIBUTE))
        .isEqualTo("anonymous");
  }

  @Test
  void asyncDispatch_ShouldRebindTheStashedValuesWithoutResolvingAnything()
      throws ServletException, IOException {
    // Given: the stream's async result is dispatched back, carrying a header the client sent
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
    request.setDispatcherType(DispatcherType.ASYNC);
    request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, "from-initial");
    request.setAttribute(CorrelationIdFilter.USER_ID_ATTRIBUTE, "member-sub");
    request.setAttribute(CorrelationIdFilter.ORG_UNIT_ID_ATTRIBUTE, "org-unit-7");
    request.addHeader(props.correlationIdHeader(), "client-sent-on-async");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Map<String, String>> mdcDuringChain = new AtomicReference<>();

    // When
    filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.getCopyOfContextMap()));

    // Then
    assertThat(mdcDuringChain.get())
        .containsEntry(props.correlationIdMdcKey(), "from-initial")
        .containsEntry(props.userIdMdcKey(), "member-sub")
        .containsEntry(props.orgUnitIdMdcKey(), "org-unit-7");
    assertThat(response.getHeader(props.correlationIdHeader()))
        .as("the async pass leaves the response header to the initial dispatch")
        .isNull();
    verifyNoInteractions(authHelperService, ownerScopeService);
    assertThat(MDC.get(props.correlationIdMdcKey())).isNull();
    assertThat(MDC.get(props.userIdMdcKey())).isNull();
    assertThat(MDC.get(props.orgUnitIdMdcKey())).isNull();
  }

  @Test
  void asyncDispatchWithoutStash_ShouldMintNothingAndTrustNoHeader()
      throws ServletException, IOException {
    // Given
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
    request.setDispatcherType(DispatcherType.ASYNC);
    request.addHeader(props.correlationIdHeader(), "client-sent-on-async");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> correlationDuringChain = new AtomicReference<>("unset");

    // When
    filter.doFilter(
        request,
        response,
        (req, res) -> correlationDuringChain.set(MDC.get(props.correlationIdMdcKey())));

    // Then
    assertThat(correlationDuringChain.get()).isNull();
    assertThat(response.getHeader(props.correlationIdHeader())).isNull();
  }
}
