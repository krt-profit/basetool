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

package de.greluc.krt.profit.basetool.frontend.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * Tests that a client disconnecting mid-response is not an application error (REQ-OBS-001,
 * REQ-NOTIF-010): the exception reaches its own handler in {@link GlobalExceptionHandler}, nothing
 * is written to the response, and nothing is logged at {@code WARN} or {@code ERROR}.
 */
class DisconnectedClientHandlingTest {

  /** The name of the handler both disconnect types must resolve to. */
  private static final String DISCONNECT_HANDLER = "handleDisconnectedClient";

  private Logger handlerLogger;
  private ListAppender<ILoggingEvent> appender;
  private MockMvc mockMvc;

  /**
   * A controller that fails the way a relay's async dispatch does: the exception arrives at the
   * dispatcher as the handler's outcome, with nothing written yet.
   */
  @RestController
  static class DisconnectingController {

    /**
     * Raises the exception Spring produces when an async response is used after the client left.
     *
     * @return never returns normally
     * @throws AsyncRequestNotUsableException always
     */
    @GetMapping("/notifications/stream")
    String asyncDisconnect() throws AsyncRequestNotUsableException {
      throw new AsyncRequestNotUsableException("Response not usable after response errors.");
    }

    /**
     * Raises Tomcat's exception for a client that closed a plain response while it was written.
     *
     * @return never returns normally
     * @throws ClientAbortException always
     */
    @GetMapping("/plain-abort")
    String plainAbort() throws ClientAbortException {
      throw new ClientAbortException(new IOException("Broken pipe"));
    }

    /**
     * Raises a genuine application fault, the contrast case that must still log {@code ERROR}.
     *
     * @return never returns normally
     */
    @GetMapping("/real-fault")
    String realFault() {
      throw new IllegalStateException("a real fault");
    }
  }

  @BeforeEach
  void setUp() {
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    appender = new ListAppender<>();
    appender.start();
    handlerLogger.addAppender(appender);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingController())
            .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
            .build();
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(appender);
  }

  @Test
  void bothDisconnectTypesResolveToTheirOwnHandlerNotTheCatchAll() {
    ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    assertThat(
            resolver
                .resolveMethod(
                    new AsyncRequestNotUsableException(
                        "Servlet container error notification for disconnected client",
                        new IOException("Broken pipe")))
                .getName())
        .isEqualTo(DISCONNECT_HANDLER);
    assertThat(
            resolver
                .resolveMethod(new ClientAbortException(new IOException("Broken pipe")))
                .getName())
        .isEqualTo(DISCONNECT_HANDLER);
  }

  @Test
  void theDisconnectHandlerReturnsNothingAtAll() throws NoSuchMethodException {
    assertThat(
            GlobalExceptionHandler.class
                .getMethod(DISCONNECT_HANDLER, IOException.class, HttpServletRequest.class)
                .getReturnType())
        .as(
            "a body cannot be written to a closed connection, and rendering the error page into"
                + " it is what produced Tomcat's second line")
        .isEqualTo(void.class);
  }

  @Test
  void anAsyncDisconnectOnTheStreamLogsNoErrorAndWritesNothing() throws Exception {
    MvcResult result = mockMvc.perform(get("/notifications/stream")).andReturn();

    assertNothingWritten(result.getResponse());
    assertNoErrorOrWarn();
  }

  @Test
  void aPlainClientAbortLogsNoErrorAndWritesNothing() throws Exception {
    MvcResult result = mockMvc.perform(get("/plain-abort")).andReturn();

    assertNothingWritten(result.getResponse());
    assertNoErrorOrWarn();
  }

  @Test
  void aRealFaultStillLogsError() throws Exception {
    mockMvc.perform(get("/real-fault")).andReturn();

    assertThat(appender.list)
        .as("the capture must see the catch-all, or the two tests above prove nothing")
        .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
  }

  /**
   * Asserts that the handler left the response exactly as it found it: no status set, no body, no
   * forwarded error view.
   *
   * @param response the response the dispatcher produced
   * @throws Exception if the body cannot be read
   */
  private static void assertNothingWritten(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as("no 500 was set on the dead response").isEqualTo(200);
    assertThat(response.getContentAsString()).as("no body was written").isEmpty();
    assertThat(response.getForwardedUrl()).as("no error view was rendered").isNull();
  }

  /** Asserts that {@link GlobalExceptionHandler} logged nothing at {@code WARN} or above. */
  private void assertNoErrorOrWarn() {
    assertThat(appender.list)
        .as("a disconnect is not an error (REQ-OBS-001)")
        .noneSatisfy(event -> assertThat(event.getLevel().isGreaterOrEqual(Level.WARN)).isTrue());
  }
}
