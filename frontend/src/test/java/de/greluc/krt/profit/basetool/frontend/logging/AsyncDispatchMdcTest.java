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

package de.greluc.krt.profit.basetool.frontend.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.frontend.controller.MeFrontendController;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * A log line written during a servlet <b>async dispatch</b> carries the same {@code correlationId},
 * {@code userId} and {@code orgUnitId} as the request's initial dispatch (REQ-OBS-001/-002).
 *
 * <p>Pins the fix for the 2026-09-25 misattribution: the notification relay's async result was
 * dispatched back into MVC, {@code GlobalExceptionHandler} logged an {@code ERROR} there, and the
 * line carried no correlation id and the logback fallback {@code userId=anonymous} — for a
 * logged-in member — because both MDC filters are {@code OncePerRequestFilter}s and skipped the
 * async pass.
 *
 * <p>The request runs through both real filters in their production order, and the async pass is
 * driven the way the container drives it: the same request object, re-entered with {@code
 * DispatcherType.ASYNC} once the {@link DeferredResult} completes. Between the two dispatches the
 * test deliberately removes everything the async pass could re-derive a value from — the security
 * context, the session pin, the inbound header — so a value that survives can only have come from
 * what the initial dispatch stashed.
 */
class AsyncDispatchMdcTest {

  private static final String HEADER = "X-Correlation-Id";
  private static final String CORRELATION_KEY = "correlationId";
  private static final String USER_KEY = "userId";
  private static final String ORG_UNIT_KEY = ActiveSquadronContextFilter.ORG_UNIT_ID_MDC_KEY;
  private static final String SUBJECT = "7d0b8a6e-5c1f-4e2a-9b3d-1f2e3d4c5b6a";

  private final LoggingProperties props =
      new LoggingProperties(HEADER, CORRELATION_KEY, USER_KEY, 2000L, 1500L, false);

  private final StreamController controller = new StreamController();
  private MockMvc mockMvc;
  private Logger probeLogger;
  private ListAppender<ILoggingEvent> appender;

  /**
   * A stand-in for the notification relay: the initial dispatch logs and hands back an open {@link
   * DeferredResult}; the async result arrives later and is resolved on the async dispatch, where
   * the controller's exception handler logs at {@code ERROR} exactly as the relay's failure did.
   */
  @Slf4j
  @RestController
  static class StreamController {

    /** The result handed out by the last {@link #stream()} call, completed by the test. */
    private final AtomicReference<DeferredResult<String>> pending = new AtomicReference<>();

    /**
     * Opens the "stream": logs on the initial dispatch and returns a result nobody has set yet.
     *
     * @return the pending result
     */
    @GetMapping("/notifications/stream")
    DeferredResult<String> stream() {
      log.info("stream opened");
      DeferredResult<String> result = new DeferredResult<>();
      pending.set(result);
      return result;
    }

    /**
     * Resolves the async failure on the async dispatch, logging it the way the global handler's
     * catch-all did in the incident.
     *
     * @param ex the failure the async result carried
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    void onAsyncFailure(IllegalStateException ex) {
      log.error("stream failed: {}", ex.getMessage());
    }

    /**
     * Returns the result the last stream call handed out.
     *
     * @return the pending result
     */
    @NotNull
    DeferredResult<String> pending() {
      return pending.get();
    }
  }

  @BeforeEach
  void setUp() {
    probeLogger = (Logger) LoggerFactory.getLogger(StreamController.class);
    probeLogger.setLevel(Level.INFO);
    appender = new ListAppender<>();
    appender.start();
    probeLogger.addAppender(appender);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new CorrelationIdFilter(props), new ActiveSquadronContextFilter())
            .build();
  }

  @AfterEach
  void tearDown() {
    probeLogger.detachAppender(appender);
    probeLogger.setLevel(null);
    SecurityContextHolder.clearContext();
    MDC.clear();
    CorrelationContext.clear();
    ActiveSquadronContext.clear();
  }

  @Test
  void theAsyncDispatchLogsTheInitialDispatchesCorrelationUserAndOrgUnit() throws Exception {
    UUID pin = UUID.randomUUID();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY, pin);
    authenticateAs(SUBJECT);

    MvcResult initial =
        mockMvc
            .perform(get("/notifications/stream").session(session).header(HEADER, "stream-cid-1"))
            .andExpect(request().asyncStarted())
            .andReturn();

    SecurityContextHolder.clearContext();
    session.setAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY, UUID.randomUUID());
    controller.pending().setErrorResult(new IllegalStateException("upstream stream closed"));
    mockMvc.perform(asyncDispatch(initial)).andExpect(status().isInternalServerError());

    Map<String, String> asyncMdc = mdcOf(Level.ERROR);
    assertThat(asyncMdc)
        .as("the async-dispatch ERROR line is attributed to the member, not to anonymous")
        .containsEntry(CORRELATION_KEY, "stream-cid-1")
        .containsEntry(USER_KEY, SUBJECT)
        .containsEntry(ORG_UNIT_KEY, pin.toString());
  }

  @Test
  void theAsyncDispatchNeitherMintsAnIdNorTrustsAHeader() throws Exception {
    MvcResult initial =
        mockMvc
            .perform(get("/notifications/stream"))
            .andExpect(request().asyncStarted())
            .andReturn();
    String minted = initial.getResponse().getHeader(HEADER);
    assertThat(minted).as("the initial dispatch minted an id").matches("[0-9a-f-]{36}");

    MockHttpServletRequest sameRequest = (MockHttpServletRequest) initial.getRequest();
    sameRequest.removeHeader(HEADER);
    sameRequest.addHeader(HEADER, "forged-on-the-async-pass");
    controller.pending().setErrorResult(new IllegalStateException("upstream stream closed"));
    MvcResult async = mockMvc.perform(asyncDispatch(initial)).andReturn();

    assertThat(mdcOf(Level.ERROR))
        .as("the async pass re-binds the one id the request already has")
        .containsEntry(CORRELATION_KEY, minted)
        .containsEntry(USER_KEY, "anonymous")
        .containsEntry(ORG_UNIT_KEY, ActiveSquadronContextFilter.NO_ACTIVE_ORG_UNIT);
    assertThat(async.getResponse().getHeaders(HEADER))
        .as("the response header is the initial dispatch's, set once and never re-minted")
        .containsExactly(minted);
  }

  @Test
  void nothingIsLeftOnTheThreadAfterTheAsyncDispatch() throws Exception {
    authenticateAs(SUBJECT);
    MvcResult initial =
        mockMvc
            .perform(get("/notifications/stream").header(HEADER, "stream-cid-2"))
            .andExpect(request().asyncStarted())
            .andReturn();
    controller.pending().setResult("done");
    mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk());

    assertThat(MDC.get(CORRELATION_KEY)).isNull();
    assertThat(MDC.get(USER_KEY)).isNull();
    assertThat(MDC.get(ORG_UNIT_KEY)).isNull();
    assertThat(CorrelationContext.get()).isNull();
    assertThat(ActiveSquadronContext.get()).isNull();
  }

  @Test
  void theInitialDispatchIsUnchanged() throws Exception {
    UUID pin = UUID.randomUUID();
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(MeFrontendController.ACTIVE_ORG_UNIT_SESSION_KEY, pin);
    authenticateAs(SUBJECT);

    MvcResult initial =
        mockMvc
            .perform(get("/notifications/stream").session(session).header(HEADER, "stream-cid-3"))
            .andExpect(request().asyncStarted())
            .andReturn();

    assertThat(initial.getResponse().getHeader(HEADER)).isEqualTo("stream-cid-3");
    assertThat(mdcOf(Level.INFO))
        .containsEntry(CORRELATION_KEY, "stream-cid-3")
        .containsEntry(USER_KEY, SUBJECT)
        .containsEntry(ORG_UNIT_KEY, pin.toString());
    assertThat(MDC.get(CORRELATION_KEY)).isNull();
    assertThat(CorrelationContext.get()).isNull();
    controller.pending().setResult("done");
  }

  /**
   * Puts an OIDC login for {@code subject} into the security context, the way the frontend's OAuth2
   * login leaves it for {@link CorrelationIdFilter} to read.
   *
   * @param subject the OIDC {@code sub} claim
   */
  private static void authenticateAs(@NotNull String subject) {
    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
    OidcIdToken idToken = OidcIdToken.withTokenValue("token").subject(subject).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new OAuth2AuthenticationToken(
                new DefaultOidcUser(authorities, idToken), authorities, "keycloak"));
  }

  /**
   * Returns the MDC the single captured probe line at {@code level} was logged with.
   *
   * @param level the level of the line to find
   * @return that line's MDC snapshot
   */
  @NotNull
  private Map<String, String> mdcOf(@NotNull Level level) {
    List<ILoggingEvent> lines =
        appender.list.stream().filter(event -> event.getLevel() == level).toList();
    assertThat(lines).as("exactly one %s line was captured", level).hasSize(1);
    return lines.getFirst().getMDCPropertyMap();
  }
}
