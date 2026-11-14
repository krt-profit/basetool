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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.BooleanNode;

/**
 * The one filter every session gate runs in (REQ-SEC-028, REQ-PATCH-020, ADR-0385): the gates run
 * in their order, the first holding gate refuses with its own code, and no gate refuses the
 * endpoints of itself or of an earlier gate.
 */
class SessionGateAccessFilterTest {

  private static final UUID USER_ID = UUID.randomUUID();

  private final FakeGate first = new FakeGate(Code.FIRST_REQUIRED, "/api/v1/first");
  private final FakeGate second = new FakeGate(Code.SECOND_REQUIRED, "/api/v1/second");
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private SessionGateAccessFilter filter;

  @BeforeEach
  void setUp() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.second_required.title", Locale.ENGLISH, "Second step");
    messages.addMessage("problem.second_required.detail", Locale.ENGLISH, "Take the second step.");
    filter =
        new SessionGateAccessFilter(
            List.of(first, second),
            messages,
            new ProblemResponseFactory(BoundProperties.defaults(AppProblemProperties.class)),
            new ObjectMapper(),
            meterRegistry);
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(USER_ID.toString()).build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  @Test
  void aCallerNoGateHoldsPasses() throws Exception {
    FilterChain chain = mock(FilterChain.class);

    assertThat(invoke("/api/v1/missions", chain).getStatus()).isEqualTo(200);
    verify(chain).doFilter(any(), any());
  }

  @Test
  void theFirstHoldingGateRefusesWithItsOwnCodeTypeAndBundleKeys() throws Exception {
    second.holding = true;
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletResponse response = invoke("/api/v1/missions", chain);

    verify(chain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    assertThat(response.getContentAsString())
        .contains("\"code\":\"SECOND_REQUIRED\"")
        .contains("second-required")
        .contains("Second step")
        .contains("Take the second step.");
    assertThat(second.refusedUsers).containsExactly(USER_ID);
    assertThat(meterRegistry.counter("basetool.http.error", "code", "SECOND_REQUIRED").count())
        .isEqualTo(1.0);
    assertThat(MDC.get("userId")).as("the MDC key is released after the write").isNull();
  }

  @Test
  void everyGatesRefusalCarriesTheSessionGateMarker() throws Exception {
    first.holding = true;
    String firstRefusal = invoke("/api/v1/missions", mock(FilterChain.class)).getContentAsString();
    first.holding = false;
    second.holding = true;
    String secondRefusal = invoke("/api/v1/missions", mock(FilterChain.class)).getContentAsString();

    assertThat(List.of(firstRefusal, secondRefusal))
        .extracting(body -> new ObjectMapper().readTree(body).findValue("sessionGate"))
        .containsExactly(BooleanNode.TRUE, BooleanNode.TRUE);
  }

  @Test
  void anEarlierGateWinsWhenBothHold() throws Exception {
    first.holding = true;
    second.holding = true;

    MockHttpServletResponse response = invoke("/api/v1/missions", mock(FilterChain.class));

    assertThat(response.getContentAsString())
        .contains("FIRST_REQUIRED")
        .contains(first.fallbackDetail());
    assertThat(second.asked).isZero();
  }

  @Test
  void aLaterGateLeavesTheEndpointsOfAnEarlierGateOpen() throws Exception {
    second.holding = true;

    assertThat(invoke("/api/v1/first", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    assertThat(invoke("/api/v1/second", mock(FilterChain.class)).getStatus()).isEqualTo(200);
  }

  @Test
  void anEarlierGateStillRefusesTheEndpointsOfALaterGate() throws Exception {
    first.holding = true;

    assertThat(invoke("/api/v1/second", mock(FilterChain.class)).getStatus()).isEqualTo(403);
  }

  @Test
  void anEncodedSpellingNeitherBypassesAGateNorLosesAnExemption() throws Exception {
    first.holding = true;

    assertThat(invoke("/%61pi/v1/missions", mock(FilterChain.class)).getStatus()).isEqualTo(403);
    assertThat(invoke("/api/v1/%66irst", mock(FilterChain.class)).getStatus()).isEqualTo(200);
  }

  @Test
  void requestsOutsideTheApiAndUnidentifiedCallersAreNotJudged() throws Exception {
    first.holding = true;

    assertThat(invoke("/actuator/health", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    Jwt service = Jwt.withTokenValue("t").header("alg", "none").subject("service-account").build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(service, List.of()));
    assertThat(invoke("/api/v1/missions", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    SecurityContextHolder.clearContext();
    assertThat(invoke("/api/v1/missions", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    assertThat(first.asked).isZero();
  }

  /**
   * Runs the filter against a path.
   *
   * @param path the context-relative request path
   * @param chain the rest of the chain
   * @return the response the filter produced
   * @throws Exception if the filter throws
   */
  private @NotNull MockHttpServletResponse invoke(@NotNull String path, @NotNull FilterChain chain)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  /** The codes of the fake gates. */
  private enum Code implements ProblemCode {
    /** The first gate's step is missing. */
    FIRST_REQUIRED,
    /** The second gate's step is missing. */
    SECOND_REQUIRED;

    @Override
    public @NotNull String code() {
      return name();
    }

    @Override
    public @NotNull HttpStatus status() {
      return HttpStatus.FORBIDDEN;
    }
  }

  /** A gate whose verdict the test sets, recording what the filter asked of it. */
  private static final class FakeGate implements SessionGate {

    private final Code code;
    private final String exemptPath;
    private final List<UUID> refusedUsers = new ArrayList<>();
    private boolean holding;
    private int asked;

    /**
     * Creates a gate that holds nobody yet.
     *
     * @param code its problem code
     * @param exemptPath its one exempt endpoint
     */
    private FakeGate(@NotNull Code code, @NotNull String exemptPath) {
      this.code = code;
      this.exemptPath = exemptPath;
    }

    @Override
    public @NotNull ProblemCode problemCode() {
      return code;
    }

    @Override
    public @NotNull List<String> exemptPaths() {
      return List.of(exemptPath);
    }

    @Override
    public boolean holds(@NotNull Authentication authentication, @NotNull UUID userId) {
      asked++;
      return holding;
    }

    @Override
    public @NotNull String fallbackDetail() {
      return "Take the " + code.name() + " step.";
    }

    @Override
    public void refused(@NotNull UUID userId) {
      refusedUsers.add(userId);
    }
  }
}
