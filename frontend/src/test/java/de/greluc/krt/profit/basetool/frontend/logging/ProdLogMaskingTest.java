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

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import de.greluc.krt.profit.basetool.logging.PiiMaskingLogstashEncoder;
import de.greluc.krt.profit.basetool.testsupport.logging.ProfiledLogbackConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sends one event with a bearer token and an e-mail address through the frontend's real {@code
 * logback-spring.xml} under the {@code prod} profile and checks that the JSON and plain-text sinks
 * both wrote them masked, i.e. that the shared {@link PiiMaskingLogstashEncoder} and masking layout
 * are wired (ADR-0205).
 */
class ProdLogMaskingTest {

  private static final String SECRET = "abc123DEFsecret";
  private static final String ADDRESS = "alice@example.com";

  @Test
  void theProdSinksMaskWhatReachesThem(@TempDir Path logs) throws IOException {
    LoggerContext context =
        ProfiledLogbackConfig.configure("logback-spring.xml", Set.of("prod"), logs);
    try {
      Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      AsyncAppender async = (AsyncAppender) root.getAppender("ASYNC_JSON_FILE");
      assertThat(async).as("the prod profile attaches the JSON sink").isNotNull();
      RollingFileAppender<?> json = (RollingFileAppender<?>) async.getAppender("JSON_FILE");
      assertThat(json.getEncoder()).isInstanceOf(PiiMaskingLogstashEncoder.class);

      context.getMDCAdapter().put("correlationId", "corr-1");
      context
          .getLogger("de.greluc.krt.profit.basetool.frontend.Probe")
          .warn("relaying Bearer {} for {}", SECRET, ADDRESS);
    } finally {
      context.stop();
    }

    String jsonLine = Files.readString(logs.resolve("frontend.json"), StandardCharsets.UTF_8);
    assertThat(jsonLine)
        .contains("Bearer ***", "***@***.***", "\"correlationId\":\"corr-1\"")
        .contains("\"app\":\"basetool-frontend\"")
        .doesNotContain(SECRET, ADDRESS);

    String textLine = Files.readString(logs.resolve("frontend.log"), StandardCharsets.UTF_8);
    assertThat(textLine).contains("Bearer ***", "***@***.***").doesNotContain(SECRET, ADDRESS);
  }
}
