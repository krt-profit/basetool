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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** Pins the section-fallback contract of {@link SectionFallback} (REQ-FE-036). */
class SectionFallbackTest {

  private final Logger log = (Logger) LoggerFactory.getLogger(SectionFallbackTest.class);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private Level previousLevel;

  @BeforeEach
  void attach() {
    appender.start();
    log.addAppender(appender);
    previousLevel = log.getLevel();
    log.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detach() {
    log.detachAppender(appender);
    log.setLevel(previousLevel);
  }

  @Test
  void loadReturnsTheCallsResultWithoutLogging() {
    assertThat(SectionFallback.load(log, "rows", () -> List.of("a"), List.of()))
        .containsExactly("a");
    assertThat(appender.list).isEmpty();
  }

  @Test
  void loadAnswersAFailureWithTheFallbackAndLogsItOnceAtWarn() {
    IllegalStateException failure = new IllegalStateException("boom");

    List<String> result =
        SectionFallback.load(
            log,
            "rows",
            () -> {
              throw failure;
            },
            List.of());

    assertThat(result).isEmpty();
    assertThat(appender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).isEqualTo("Page section 'rows' unavailable");
              assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
            });
  }

  @Test
  void aBackendFailureLogsAtDebugWhateverTheCallerNames() {
    SectionFallback.load(
        log,
        org.slf4j.event.Level.ERROR,
        "rows",
        () -> {
          throw new BackendServiceException("down", null, 503);
        },
        List.of());

    assertThat(appender.list)
        .singleElement()
        .extracting(ILoggingEvent::getLevel)
        .isEqualTo(Level.DEBUG);
  }

  @Test
  void anUnexpectedFailureLogsAtTheLevelTheCallerNames() {
    SectionFallback.run(
        log,
        org.slf4j.event.Level.DEBUG,
        "search",
        () -> {
          throw new IllegalStateException("boom");
        });

    assertThat(appender.list)
        .singleElement()
        .extracting(ILoggingEvent::getLevel)
        .isEqualTo(Level.DEBUG);
  }

  @Test
  void loadRethrowsReauthentication() {
    ReauthenticationRequiredException reauth = reauth();

    assertThatThrownBy(
            () ->
                SectionFallback.load(
                    log,
                    "rows",
                    () -> {
                      throw reauth;
                    },
                    List.of()))
        .isSameAs(reauth);
    assertThat(appender.list).isEmpty();
  }

  @Test
  void loadUnwrapsReauthenticationFromAJoinedFuture() {
    ReauthenticationRequiredException reauth = reauth();

    assertThatThrownBy(
            () ->
                SectionFallback.load(
                    log,
                    "rows",
                    () -> {
                      throw new CompletionException(reauth);
                    },
                    List.of()))
        .isSameAs(reauth);
  }

  @Test
  void loadTurnsABareClientAuthorizationFailureIntoReauthentication() {
    ClientAuthorizationException signal = clientAuthorizationFailure();

    assertThatThrownBy(
            () ->
                SectionFallback.load(
                    log,
                    "rows",
                    () -> {
                      throw new IllegalStateException(signal);
                    },
                    List.of()))
        .isInstanceOf(ReauthenticationRequiredException.class)
        .hasRootCause(signal);
  }

  @Test
  void runReportsSuccessAndFailure() {
    AtomicBoolean ran = new AtomicBoolean();

    assertThat(SectionFallback.run(log, "stats", () -> ran.set(true))).isTrue();
    assertThat(ran).isTrue();
    assertThat(
            SectionFallback.run(
                log,
                "stats",
                () -> {
                  throw new IllegalStateException("boom");
                }))
        .isFalse();
    assertThat(appender.list)
        .singleElement()
        .extracting(ILoggingEvent::getLevel)
        .isEqualTo(Level.WARN);
  }

  @Test
  void runRethrowsReauthentication() {
    ReauthenticationRequiredException reauth = reauth();

    assertThatThrownBy(
            () ->
                SectionFallback.run(
                    log,
                    "stats",
                    () -> {
                      throw reauth;
                    }))
        .isSameAs(reauth);
  }

  private static ReauthenticationRequiredException reauth() {
    return new ReauthenticationRequiredException("expired", clientAuthorizationFailure());
  }

  private static ClientAuthorizationException clientAuthorizationFailure() {
    return new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "keycloak");
  }
}
