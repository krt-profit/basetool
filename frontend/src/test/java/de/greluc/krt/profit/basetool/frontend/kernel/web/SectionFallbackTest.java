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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** Pins the section-fallback contract of {@link SectionFallback} (REQ-FE-036). */
class SectionFallbackTest {

  private final Logger log = mock(Logger.class);

  @Test
  void loadReturnsTheCallsResultWithoutLogging() {
    assertThat(SectionFallback.load(log, "rows", () -> List.of("a"), List.of())).containsExactly("a");
    verify(log, never()).warn(anyString(), any(Object.class), any(Object.class));
  }

  @Test
  void loadAnswersAFailureWithTheFallbackAndLogsItOnce() {
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
    verify(log).warn("Page section '{}' unavailable", "rows", failure);
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
    verify(log, never()).warn(anyString(), any(Object.class), any(Object.class));
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
    verify(log).warn(eq("Page section '{}' unavailable"), eq("stats"), any(Object.class));
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
