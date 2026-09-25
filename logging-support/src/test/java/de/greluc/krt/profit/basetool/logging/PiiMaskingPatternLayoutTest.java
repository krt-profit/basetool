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

package de.greluc.krt.profit.basetool.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins that the human-readable console / file layout of all three applications scrubs PII (e-mail /
 * JWT / bearer token / session id) before a line is rendered (REQ-OBS-003/-004).
 */
class PiiMaskingPatternLayoutTest {

  private PiiMaskingPatternLayout layout;
  private LoggerContext context;
  private Logger logger;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    logger = context.getLogger(PiiMaskingPatternLayoutTest.class);
    layout = new PiiMaskingPatternLayout();
    layout.setPattern("%msg");
    layout.setContext(context);
    layout.start();
  }

  @Test
  void shouldMaskEmail() {
    ILoggingEvent event = createEvent("User email is test.user@example.com and it is sensitive");

    String result = layout.doLayout(event);

    assertEquals("User email is ***@***.*** and it is sensitive", result, "Email should be masked");
  }

  @Test
  void shouldMaskTokenKeyword() {
    ILoggingEvent event = createEvent("Authorization: Bearer 1234567890abcdef and some other text");

    String result = layout.doLayout(event);

    assertEquals("Authorization: Bearer *** and some other text", result, "Token should be masked");
    assertFalse(result.contains("1234567890abcdef"));
  }

  @Test
  void shouldMaskSessionId() {
    ILoggingEvent event = createEvent("User logged in with session-id: abcdef-1234-5678");

    String result = layout.doLayout(event);

    assertEquals("User logged in with session-id: ***", result, "Session ID should be masked");
    assertFalse(result.contains("abcdef-1234-5678"));
  }

  @Test
  void shouldMaskStandaloneJwt() {
    ILoggingEvent event =
        createEvent(
            "Received JWT"
                + " eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");

    String result = layout.doLayout(event);

    assertEquals("Received JWT JWT_***", result, "JWT should be masked");
  }

  @Test
  void shouldMaskMultipleSecrets() {
    ILoggingEvent event = createEvent("Email user@example.org used token=my-secret-token");

    String result = layout.doLayout(event);

    assertEquals("Email ***@***.*** used token=***", result, "Both secrets should be masked");
    assertFalse(result.contains("user@example.org"));
    assertFalse(result.contains("my-secret-token"));
  }

  @Test
  void shouldNotAlterNormalText() {
    String normalText = "This is a normal log message without any secrets.";
    ILoggingEvent event = createEvent(normalText);

    String result = layout.doLayout(event);

    assertEquals(normalText, result, "Normal text should remain unaltered");
  }

  private ILoggingEvent createEvent(String message) {
    return new LoggingEvent(
        "de.greluc.krt.profit.basetool.logging.PiiMaskingPatternLayoutTest",
        logger,
        Level.INFO,
        message,
        null,
        null);
  }
}
