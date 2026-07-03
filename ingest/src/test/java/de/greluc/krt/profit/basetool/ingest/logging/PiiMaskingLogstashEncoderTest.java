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

package de.greluc.krt.profit.basetool.ingest.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins that the ingest prod JSON sink scrubs PII (e-mail / JWT / bearer token) before it is
 * written, mirroring the backend/frontend encoder tests. Before epic #936 Phase 1 the ingest prod
 * appender used the stock {@code LogstashEncoder} and was the last unmasked log output in the
 * system (REQ-OBS-004; prerequisite for the Phase-2 Loki ingestion, REQ-OBS-007).
 */
class PiiMaskingLogstashEncoderTest {

  private PiiMaskingLogstashEncoder encoder;
  private LoggerContext context;
  private Logger logger;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    // LogstashEncoder reads the MDC at encode time; an empty LoggerContext has no MDCAdapter set,
    // which throws NPE inside MdcJsonProvider. Attach the standard logback MDC adapter so the
    // encoder sees an empty-but-valid MDC.
    context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    logger = context.getLogger(PiiMaskingLogstashEncoderTest.class);
    encoder = new PiiMaskingLogstashEncoder();
    encoder.setContext(context);
    encoder.start();
  }

  @Test
  void shouldMaskEmailInsideJson() {
    // Given / When
    String json = encode("User email is test.user@example.com");

    // Then
    assertFalse(json.contains("test.user@example.com"), "raw email must not leak: " + json);
    assertTrue(json.contains("***@***.***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldMaskJwtInsideJson() {
    // Given
    String token =
        "eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    // When
    String json = encode("Received JWT " + token);

    // Then
    assertFalse(json.contains(token), "raw JWT must not leak: " + json);
    assertTrue(json.contains("JWT_***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldMaskBearerToken() {
    // Given / When
    String json = encode("Authorization: Bearer 1234567890abcdef");

    // Then
    assertFalse(json.contains("1234567890abcdef"), "bearer secret must not leak: " + json);
    assertTrue(json.contains("Bearer ***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldKeepJsonStructureIntact() {
    // Given / When
    String json = encode("Email user@example.org used token=my-secret");

    // Then: the masker only inserts alphanumerics, so the surrounding JSON must still start with
    // '{' and end with '}' (LogstashEncoder appends a trailing newline).
    String trimmed = json.trim();
    assertTrue(
        trimmed.startsWith("{") && trimmed.endsWith("}"),
        "encoded output must remain a JSON object: " + json);
    assertFalse(json.contains("user@example.org"));
    assertFalse(json.contains("my-secret"));
  }

  private String encode(String message) {
    ILoggingEvent event =
        new LoggingEvent(
            PiiMaskingLogstashEncoderTest.class.getName(), logger, Level.INFO, message, null, null);
    byte[] raw = encoder.encode(event);
    return new String(raw, StandardCharsets.UTF_8);
  }
}
