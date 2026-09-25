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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies the structured WARN log emitted by {@link BackendErrorLogging}.
 *
 * <p>The log line must contain action, contextId, status, code, correlationId and field errors so
 * that a user-reported {@code 400 VALIDATION_FAILED} is reproducible from the log alone. Crucially,
 * it must NOT leak any rejected user value (PII protection, AGENTS.md).
 */
class BackendErrorLoggingTest {

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger("BackendErrorLoggingTestLogger");
    logger.setLevel(Level.WARN);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void shouldLogStructuredWarnWithoutLeakingRejectedValue() {
    UUID jobOrderId = UUID.fromString("087c5b6e-c143-4358-a5e6-149aa6b02ba7");
    String correlationId = "57410a34-650a-4b9f-a139-aaa02ed8400a";
    BackendServiceException ex =
        new BackendServiceException(
            "Backend returned 400",
            null,
            400,
            "VALIDATION_FAILED",
            correlationId,
            List.of(new BackendServiceException.FieldError("recipientHandle", "must not be blank")),
            "One or more fields have invalid values.");

    BackendErrorLogging.warn(logger, "POST /api/v1/orders/{id}/handovers", jobOrderId, ex);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    String formatted = event.getFormattedMessage();
    assertTrue(formatted.contains("action=POST /api/v1/orders/{id}/handovers"), formatted);
    assertTrue(formatted.contains("contextId=" + jobOrderId), formatted);
    assertTrue(formatted.contains("status=400"), formatted);
    assertTrue(formatted.contains("code=VALIDATION_FAILED"), formatted);
    assertTrue(formatted.contains("correlationId=" + correlationId), formatted);
    assertTrue(formatted.contains("recipientHandle"), formatted);
    assertTrue(formatted.contains("must not be blank"), formatted);
    assertFalse(formatted.contains("secret-pii-handle"), formatted);
  }

  @Test
  void shouldOmitContextIdWhenNullOverloadIsUsed() {
    BackendServiceException ex =
        new BackendServiceException(
            "Backend returned 503",
            null,
            503,
            BackendServiceException.CODE_SERVICE_UNAVAILABLE,
            null,
            List.of(),
            null);

    BackendErrorLogging.warn(logger, "GET /api/v1/orders", ex);

    assertEquals(1, appender.list.size());
    String formatted = appender.list.get(0).getFormattedMessage();
    assertFalse(formatted.contains("contextId="), formatted);
    assertTrue(formatted.contains("status=503"), formatted);
    assertTrue(formatted.contains("code=SERVICE_UNAVAILABLE"), formatted);
  }
}
