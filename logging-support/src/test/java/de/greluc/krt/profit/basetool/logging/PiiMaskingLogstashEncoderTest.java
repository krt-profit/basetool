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
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.json.JsonMapper;

/** Tests that the prod JSON sink encoder scrubs e-mails, JWTs and bearer tokens before writing. */
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
    assertTrue(JsonMapper.builder().build().readTree(json).isObject());
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

  @Test
  void shouldMaskEscapedStructuredValuesAndRetainValidJson() {
    for (String secret :
        new String[] {
          "first\"secret-suffix",
          "first\\secret-suffix",
          "first\n\tsecret-suffix",
          "first\u00fcsecret-suffix"
        }) {
      var event =
          new LoggingEvent(
              getClass().getName(),
              logger,
              Level.INFO,
              "safe \"message\" \\ path\nUnicode \u00fc",
              null,
              null);
      event.addKeyValuePair(new KeyValuePair("password", secret));
      event.addKeyValuePair(new KeyValuePair("databasePassword", secret));
      event.addKeyValuePair(new KeyValuePair("access_token", secret));
      String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
      var parsed = JsonMapper.builder().build().readTree(json);
      assertEquals("***", parsed.get("password").asString());
      assertEquals("***", parsed.get("databasePassword").asString());
      assertEquals("***", parsed.get("access_token").asString());
      assertFalse(json.contains("secret-suffix"));
      assertEquals("safe \"message\" \\ path\nUnicode \u00fc", parsed.get("message").asString());
    }
  }

  @Test
  void shouldMaskMessageValuesBeforeEscaping() {
    var parsed =
        JsonMapper.builder().build().readTree(encode("password=abc\\def Unicode \u00fc\n"));
    assertEquals("password=***", parsed.get("message").asString());
  }

  @Test
  void shouldRemoveCredentialSuffixesInEscapedMessages() {
    for (String secret :
        new String[] {
          "abc\"secret-suffix", "abc\\secret-suffix", "abc\nsecret-suffix", "abc\u00fcsecret-suffix"
        }) {
      String json = encode("credential password=" + secret);
      assertEquals(
          "credential password=***",
          JsonMapper.builder().build().readTree(json).get("message").asString());
      assertFalse(json.contains("secret-suffix"));
    }
  }

  private String encode(String message) {
    ILoggingEvent event =
        new LoggingEvent(
            PiiMaskingLogstashEncoderTest.class.getName(), logger, Level.INFO, message, null, null);
    byte[] raw = encoder.encode(event);
    return new String(raw, StandardCharsets.UTF_8);
  }
}
