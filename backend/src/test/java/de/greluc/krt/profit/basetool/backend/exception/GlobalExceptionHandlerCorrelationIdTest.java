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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.backend.logging.CorrelationIdFilter;
import de.greluc.krt.profit.basetool.backend.logging.RequestLoggingFilter;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins that the two {@link GlobalExceptionHandler} branches which log at ERROR — the catch-all
 * {@code Exception} handler and the {@code SUPPRESSED} {@link AppException} branch — leave the
 * request's {@code correlationId} MDC key exactly as they found it (REQ-OBS-001, REQ-OBS-002).
 *
 * <p>Both branches used to put the id and then remove it unconditionally in a {@code finally}. On a
 * real request {@link CorrelationIdFilter} owns that key for the whole chain, so the removal
 * stripped it from every line written afterwards on the same thread — above all the {@link
 * RequestLoggingFilter} access-log line for the resulting 500, which then could not be joined to
 * the ERROR line or to the {@code correlationId} the caller received. The MockMvc tests run the two
 * real filters in their production order around a throwing controller; the direct-call tests pin
 * the other half of the contract, that a key the handler minted itself is not left behind.
 */
class GlobalExceptionHandlerCorrelationIdTest {

  /** The inbound id every request here carries, so all three places can be compared to it. */
  private static final String INBOUND_ID = "cid-500-access-line";

  /** MDC key both filters and the handler share. */
  private static final String MDC_KEY = "correlationId";

  private final LoggingProperties loggingProperties =
      BoundProperties.defaults(LoggingProperties.class);
  private final CapturingAppender appender = new CapturingAppender();
  private final Logger accessLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
  private final Logger handlerLogger =
      (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private Level accessLoggerLevel;
  private GlobalExceptionHandler handler;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AppProblemProperties problemProperties =
        new AppProblemProperties("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler =
        new GlobalExceptionHandler(
            problemProperties,
            new ProblemResponseFactory(problemProperties),
            messageSource,
            new SimpleMeterRegistry());

    AuthHelperService authHelperService = mock(AuthHelperService.class);
    OwnerScopeService ownerScopeService = mock(OwnerScopeService.class);
    when(authHelperService.isAuthenticated()).thenReturn(false);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.empty());

    mockMvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(handler)
            .addFilters(
                new CorrelationIdFilter(loggingProperties, authHelperService, ownerScopeService),
                new RequestLoggingFilter(loggingProperties))
            .build();

    accessLoggerLevel = accessLogger.getLevel();
    accessLogger.setLevel(Level.INFO);
    appender.start();
    accessLogger.addAppender(appender);
    handlerLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    accessLogger.detachAppender(appender);
    handlerLogger.detachAppender(appender);
    accessLogger.setLevel(accessLoggerLevel);
    appender.stop();
    LocaleContextHolder.resetLocaleContext();
    MDC.clear();
  }

  @Test
  void unexpectedException_accessLineCarriesTheSameCorrelationIdAsErrorLineAndBody()
      throws Exception {
    mockMvc
        .perform(get("/boom").header(loggingProperties.correlationIdHeader(), INBOUND_ID))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string(loggingProperties.correlationIdHeader(), INBOUND_ID))
        .andExpect(jsonPath("$.correlationId").value(INBOUND_ID));

    assertThat(appender.correlationIdOf(Level.ERROR, "Unexpected error at /boom"))
        .isEqualTo(INBOUND_ID);
    assertThat(appender.correlationIdOf(Level.INFO, "GET /boom -> 500"))
        .as("the access-log line for the 500 must keep the request's correlation id")
        .isEqualTo(INBOUND_ID);
  }

  @Test
  void suppressedAppException_accessLineCarriesTheSameCorrelationIdAsErrorLineAndBody()
      throws Exception {
    mockMvc
        .perform(get("/upstream").header(loggingProperties.correlationIdHeader(), INBOUND_ID))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.correlationId").value(INBOUND_ID));

    assertThat(appender.correlationIdOf(Level.ERROR, "at /upstream")).isEqualTo(INBOUND_ID);
    assertThat(appender.correlationIdOf(Level.INFO, "GET /upstream -> 502"))
        .as("the access-log line for the 502 must keep the request's correlation id")
        .isEqualTo(INBOUND_ID);
  }

  @Test
  void unexpectedException_withoutRequestId_logsMintedIdAndLeavesNoKeyBehind() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/no-filter");

    ResponseEntity<ProblemDetail> response =
        handler.handleAllExceptions(new IllegalStateException("boom"), request);

    String minted = correlationIdOf(response);
    assertThat(minted).isNotBlank();
    assertThat(appender.correlationIdOf(Level.ERROR, "Unexpected error at /no-filter"))
        .isEqualTo(minted);
    assertThat(MDC.get(MDC_KEY)).as("a key the handler minted must not leak").isNull();
  }

  @Test
  void suppressedAppException_withoutRequestId_logsMintedIdAndLeavesNoKeyBehind() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/no-filter");

    ResponseEntity<ProblemDetail> response =
        handler.handleAppException(new ExternalServiceException("upstream down"), request);

    String minted = correlationIdOf(response);
    assertThat(minted).isNotBlank();
    assertThat(appender.correlationIdOf(Level.ERROR, "at /no-filter")).isEqualTo(minted);
    assertThat(MDC.get(MDC_KEY)).as("a key the handler minted must not leak").isNull();
  }

  @Test
  void unexpectedException_withBlankPriorValue_restoresItAfterLoggingAMintedId() {
    MDC.put(MDC_KEY, " ");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/blank");

    ResponseEntity<ProblemDetail> response =
        handler.handleAllExceptions(new IllegalStateException("boom"), request);

    String minted = correlationIdOf(response);
    assertThat(minted).isNotBlank();
    assertThat(appender.correlationIdOf(Level.ERROR, "Unexpected error at /blank"))
        .isEqualTo(minted);
    assertThat(MDC.get(MDC_KEY)).isEqualTo(" ");
  }

  /**
   * Reads the {@code correlationId} extension property off a problem response.
   *
   * @param response the handler's response
   * @return the id the caller would see
   */
  private static String correlationIdOf(ResponseEntity<ProblemDetail> response) {
    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getProperties()).isNotNull();
    return (String) body.getProperties().get("correlationId");
  }

  /** Throws the two exception shapes whose handler branches log at ERROR. */
  @RestController
  static class ThrowingController {

    /**
     * Fails with an exception no dedicated handler matches, so the catch-all answers 500.
     *
     * @return never returns
     */
    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("unexpected failure");
    }

    /**
     * Fails with a {@code SUPPRESSED}-disclosure {@link AppException}, answered 502.
     *
     * @return never returns
     */
    @GetMapping("/upstream")
    String upstream() {
      throw new ExternalServiceException("upstream said no");
    }
  }

  /**
   * One captured event: its level, formatted message and the {@code correlationId} MDC value at
   * append time.
   *
   * @param level the event's level
   * @param message the fully formatted message
   * @param correlationId the MDC value at append time; {@code null} when the key was unset
   */
  private record CapturedEvent(Level level, String message, String correlationId) {}

  /**
   * Records the {@code correlationId} MDC value as it stands <em>at append time</em>, which is when
   * the logback pattern reads it. {@code ILoggingEvent.getMDCPropertyMap()} resolves lazily, so
   * reading it after the request has finished would see the filter's final cleanup, not the line.
   */
  private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

    /** Every accepted event, in arrival order. */
    private final List<CapturedEvent> events = new ArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(
          new CapturedEvent(event.getLevel(), event.getFormattedMessage(), MDC.get(MDC_KEY)));
    }

    /**
     * Returns the MDC {@code correlationId} recorded for the first event of {@code level} whose
     * message contains {@code needle}.
     *
     * @param level the wanted event's level
     * @param needle substring identifying the wanted log line
     * @return the recorded value, possibly {@code null} when the key was unset
     * @throws AssertionError when no matching event was captured
     */
    private String correlationIdOf(Level level, String needle) {
      return events.stream()
          .filter(e -> level.equals(e.level()) && e.message().contains(needle))
          .findFirst()
          .orElseThrow(
              () -> new AssertionError("no " + level + " event containing: " + needle + events))
          .correlationId();
    }
  }
}
