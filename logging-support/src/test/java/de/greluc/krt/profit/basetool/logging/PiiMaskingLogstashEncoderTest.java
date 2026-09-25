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
 * Pins that the prod JSON sink of all three applications scrubs PII (e-mail / JWT / bearer token)
 * before it is written. Each module's JSON appender once used the stock {@code LogstashEncoder} and
 * was an unmasked log output (audit M-5, epic #936 Phase 1).
 */
class PiiMaskingLogstashEncoderTest {

  private PiiMaskingLogstashEncoder encoder;
  private LoggerContext context;
  private Logger logger;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    logger = context.getLogger(PiiMaskingLogstashEncoderTest.class);
    encoder = new PiiMaskingLogstashEncoder();
    encoder.setContext(context);
    encoder.start();
  }

  @Test
  void shouldMaskEmailInsideJson() {
    String json = encode("User email is test.user@example.com");
    assertFalse(json.contains("test.user@example.com"), "raw email must not leak: " + json);
    assertTrue(json.contains("***@***.***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldMaskJwtInsideJson() {
    String token =
        "eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
    String json = encode("Received JWT " + token);
    assertFalse(json.contains(token), "raw JWT must not leak: " + json);
    assertTrue(json.contains("JWT_***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldMaskBearerToken() {
    String json = encode("Authorization: Bearer 1234567890abcdef");
    assertFalse(json.contains("1234567890abcdef"), "bearer secret must not leak: " + json);
    assertTrue(json.contains("Bearer ***"), "masked placeholder must be present: " + json);
  }

  @Test
  void shouldKeepJsonStructureIntact() {
    String json = encode("Email user@example.org used token=my-secret");
    String trimmed = json.trim();
    assertTrue(
        trimmed.startsWith("{") && trimmed.endsWith("}"),
        "encoded output must remain a JSON object: " + json);
    assertFalse(json.contains("user@example.org"));
    assertFalse(json.contains("my-secret"));
  }

  @Test
  void shouldHandBackTheEncodersOwnBytesWhenNothingIsMasked() {
    ILoggingEvent event =
        new LoggingEvent(
            PiiMaskingLogstashEncoderTest.class.getName(),
            logger,
            Level.INFO,
            "Staged REFINERY handoff (ttl=PT30M)",
            null,
            null);

    String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

    assertTrue(json.contains("Staged REFINERY handoff (ttl=PT30M)"), "unchanged message: " + json);
    assertFalse(json.contains("***"), "nothing to mask, so no placeholder: " + json);
  }

  private String encode(String message) {
    ILoggingEvent event =
        new LoggingEvent(
            PiiMaskingLogstashEncoderTest.class.getName(), logger, Level.INFO, message, null, null);
    byte[] raw = encoder.encode(event);
    return new String(raw, StandardCharsets.UTF_8);
  }
}
