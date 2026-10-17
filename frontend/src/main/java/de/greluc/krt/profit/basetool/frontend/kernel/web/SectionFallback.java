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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Runs the backend call behind one section of a page so that a failure leaves the rest of the page
 * rendering (REQ-FE-036).
 *
 * <p>A lost session is never a section failure: a {@link ReauthenticationRequiredException}, or a
 * failure whose cause chain carries the OAuth2 re-authentication signal, propagates to {@link
 * GlobalExceptionHandler}, which sends the browser to the login. Every other runtime failure is
 * logged once and answered with the fallback: a mapped {@link BackendServiceException} at {@code
 * DEBUG}, anything else at {@code WARN}.
 */
public final class SectionFallback {

  /** Upper bound on the cause-chain walk for a wrapped re-authentication failure. */
  private static final int MAX_CAUSE_DEPTH = 25;

  private SectionFallback() {}

  /**
   * Loads the value of one page section, or the fallback when the call fails.
   *
   * @param log the caller's logger
   * @param section a constant name of the section for the log line, never user data
   * @param call the backend call
   * @param fallback the value the section shows when the call fails
   * @param <T> the section's value type
   * @return the call's result, or {@code fallback} when the call threw
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public static <T> T load(
      @NotNull Logger log,
      @NotNull String section,
      @NotNull Supplier<? extends T> call,
      T fallback) {
    try {
      return call.get();
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (RuntimeException e) {
      rethrowReauthSignal(section, e);
      logFailure(log, section, e);
      return fallback;
    }
  }

  /**
   * Runs the work of one page section and reports whether it succeeded, so the caller can mark the
   * section unavailable.
   *
   * @param log the caller's logger
   * @param section a constant name of the section for the log line, never user data
   * @param work the section's backend calls and model writes
   * @return {@code true} when the work completed, {@code false} when it threw
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public static boolean run(
      @NotNull Logger log, @NotNull String section, @NotNull Runnable work) {
    try {
      work.run();
      return true;
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (RuntimeException e) {
      rethrowReauthSignal(section, e);
      logFailure(log, section, e);
      return false;
    }
  }

  /**
   * Writes the one log line of a section failure.
   *
   * @param log the caller's logger
   * @param section the section name
   * @param failure the caught failure
   */
  private static void logFailure(
      @NotNull Logger log, @NotNull String section, @NotNull RuntimeException failure) {
    if (failure instanceof BackendServiceException) {
      log.debug("Page section '{}' unavailable", section, failure);
    } else {
      log.warn("Page section '{}' unavailable", section, failure);
    }
  }

  /**
   * Turns a failure that carries the OAuth2 re-authentication signal, such as the {@code
   * CompletionException} of a joined {@code ParallelPageLoader} future, into the exception the
   * global handler redirects on.
   *
   * @param section the section name for the exception message
   * @param failure the caught failure
   * @throws ReauthenticationRequiredException when {@code failure}'s cause chain carries the signal
   */
  private static void rethrowReauthSignal(
      @NotNull String section, @NotNull RuntimeException failure) {
    Throwable current = failure.getCause();
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof ReauthenticationRequiredException reauth) {
        throw reauth;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    if (ReauthenticationRequiredException.isReauthSignal(failure)) {
      throw new ReauthenticationRequiredException(
          "Page section '" + section + "' lost the OAuth2 session", failure);
    }
  }
}
