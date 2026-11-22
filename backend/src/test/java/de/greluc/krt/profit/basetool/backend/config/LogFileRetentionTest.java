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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds the rolled file logs of the three applications to the 31-day log retention that the privacy
 * policy states (REQ-OBS-010): every rolling appender keeps at most 30 days beside the current one.
 */
class LogFileRetentionTest {

  /** Days of rolled history one appender may keep beside the file of the current day. */
  private static final int MAX_HISTORY_DAYS = 30;

  private static final Pattern MAX_HISTORY = Pattern.compile("<maxHistory>\\s*(\\d+)\\s*</");

  private static final Pattern ROLLING_APPENDER =
      Pattern.compile("class=\"ch\\.qos\\.logback\\.core\\.rolling\\.RollingFileAppender\"");

  /**
   * Asserts that the module's {@code logback-spring.xml} declares a {@code maxHistory} on every
   * rolling appender and none above {@value #MAX_HISTORY_DAYS} days.
   *
   * @param module the application module whose logging configuration is read
   * @throws IOException if the configuration cannot be read
   */
  @ParameterizedTest
  @ValueSource(strings = {"backend", "frontend", "ingest"})
  void everyRolledFileLogIsKeptAtMostThirtyDays(String module) throws IOException {
    String xml =
        Files.readString(
            RepositoryRoot.find().resolve(module + "/src/main/resources/logback-spring.xml"),
            StandardCharsets.UTF_8);

    List<Integer> histories =
        MAX_HISTORY.matcher(xml).results().map(LogFileRetentionTest::days).toList();
    long rollingAppenders = ROLLING_APPENDER.matcher(xml).results().count();

    assertThat(rollingAppenders).as("%s declares rolling file appenders", module).isPositive();
    assertThat(histories)
        .as("%s: one maxHistory per rolling appender", module)
        .hasSize((int) rollingAppenders);
    assertThat(histories)
        .as("%s: no rolled log outlives the 31-day retention of REQ-OBS-010", module)
        .allSatisfy(days -> assertThat(days).isBetween(1, MAX_HISTORY_DAYS));
  }

  /**
   * Reads the day count of one {@code maxHistory} match.
   *
   * @param match a match of {@link #MAX_HISTORY}
   * @return the declared number of days
   */
  private static int days(MatchResult match) {
    return Integer.parseInt(match.group(1));
  }
}
