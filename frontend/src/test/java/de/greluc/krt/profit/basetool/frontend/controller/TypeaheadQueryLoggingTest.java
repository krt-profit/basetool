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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ui.ExtendedModelMap;

/**
 * Pins the logging contract of the type-ahead / search proxies (audit finding M3).
 *
 * <p>Two things are under test on every one of them. <b>Level:</b> these endpoints fire one request
 * per keystroke, so with the backend down a single member typing produces one log record per
 * character — a flood an ordinary user triggers by accident, which REQ-OBS-001 puts at DEBUG. Only
 * {@code AdminBlueprintsPageController}'s {@code catch (Exception)} catch-all stays at ERROR.
 * <b>Sanitising:</b> the query is a raw {@code @RequestParam}, so a member pasting a newline plus a
 * fabricated log prefix could otherwise forge a second log line (CWE-117) — it must go through
 * {@code LogSafe}, which replaces every ISO control character with {@code '?'}.
 */
class TypeaheadQueryLoggingTest {

  /**
   * A query whose control characters would break the single-line log format if they were not
   * stripped, carrying a plausible forged record after the newline.
   */
  private static final String FORGED_QUERY = "quantanium\nERROR --- [main] a.b.C : all clear";

  private final List<Runnable> detachers = new ArrayList<>();

  @AfterEach
  void detachAppenders() {
    detachers.forEach(Runnable::run);
    detachers.clear();
  }

  /**
   * Attaches a capturing appender to the given class's logger and enables DEBUG on it.
   *
   * @param type the class whose logger is captured
   * @return the appender collecting that logger's records for the rest of the test
   */
  private ListAppender<ILoggingEvent> capture(Class<?> type) {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    detachers.add(
        () -> {
          logger.detachAppender(appender);
          logger.setLevel(previous);
        });
    return appender;
  }

  /**
   * Asserts that the single captured record has the expected level and shows the sanitised query
   * rather than the raw one.
   *
   * @param appender the capturing appender
   * @param expected the level the record must carry
   */
  private static void assertSanitisedAt(ListAppender<ILoggingEvent> appender, Level expected) {
    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(expected);
              assertThat(event.getFormattedMessage())
                  .contains("quantanium?ERROR")
                  .doesNotContain("\n");
            });
  }

  @Test
  void uexTypeahead_logsSanitisedQueryAtDebugNotWarn() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(anyString(), anyTypeRef(), eq(FORGED_QUERY)))
        .thenThrow(new RuntimeException("backend down"));
    ListAppender<ILoggingEvent> appender = capture(PersonalInventoryPageController.class);

    assertThat(new PersonalInventoryPageController(client).uexSearch(FORGED_QUERY, 25)).isEmpty();

    assertSanitisedAt(appender, Level.DEBUG);
  }

  @Test
  void personalBlueprintTypeahead_logsSanitisedQueryAtDebugNotWarn() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(anyString(), anyTypeRef(), eq(FORGED_QUERY)))
        .thenThrow(new RuntimeException("backend down"));
    ListAppender<ILoggingEvent> appender = capture(PersonalInventoryBlueprintsPageController.class);

    assertThat(new PersonalInventoryBlueprintsPageController(client).search(FORGED_QUERY, 25))
        .isEmpty();

    assertSanitisedAt(appender, Level.DEBUG);
  }

  @Test
  void defaultBlueprintTypeahead_logsSanitisedQueryAtDebugNotWarn() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(anyString(), anyTypeRef(), eq(FORGED_QUERY)))
        .thenThrow(new RuntimeException("backend down"));
    ListAppender<ILoggingEvent> appender = capture(AdminDefaultBlueprintsPageController.class);

    assertThat(new AdminDefaultBlueprintsPageController(client).search(FORGED_QUERY, 25)).isEmpty();

    assertSanitisedAt(appender, Level.DEBUG);
  }

  /** A reachable-but-failing backend is an expected outcome, so it stays at DEBUG. */
  @Test
  void adminBlueprintSearch_logsSanitisedQueryAtDebugOnBackendServiceException() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(anyString(), anyTypeRef(), eq(FORGED_QUERY)))
        .thenThrow(new BackendServiceException("relay failed", null, 503));
    ListAppender<ILoggingEvent> appender = capture(AdminBlueprintsPageController.class);

    new AdminBlueprintsPageController(client)
        .listBlueprints(FORGED_QUERY, 0, null, new ExtendedModelMap());

    assertSanitisedAt(appender, Level.DEBUG);
  }

  /**
   * The {@code catch (Exception)} catch-all is a genuinely unexpected failure and keeps its ERROR
   * level — REQ-OBS-001 sanctions exactly that. Only the sanitising changed here.
   */
  @Test
  void adminBlueprintSearch_keepsErrorLevelForTheUnexpectedCatchAll() {
    BackendApiClient client = mock(BackendApiClient.class);
    when(client.get(anyString(), anyTypeRef(), eq(FORGED_QUERY)))
        .thenThrow(new IllegalStateException("boom"));
    ListAppender<ILoggingEvent> appender = capture(AdminBlueprintsPageController.class);

    new AdminBlueprintsPageController(client)
        .listBlueprints(FORGED_QUERY, 0, null, new ExtendedModelMap());

    assertSanitisedAt(appender, Level.ERROR);
  }
}
