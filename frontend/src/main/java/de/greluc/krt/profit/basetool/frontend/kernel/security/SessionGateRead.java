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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A session gate's backend read of its status, the same for every gate: a backend that cannot
 * answer leaves the read unanswered, so the gate lets the session through and caches nothing; a
 * lost session propagates (REQ-SEC-012); any other failure propagates too.
 */
@Slf4j
public final class SessionGateRead {

  private SessionGateRead() {}

  /**
   * Runs a gate's status read.
   *
   * @param call the typed client's read
   * @param <T> the status type
   * @return the answered read, or an unanswered one when the backend failed
   * @throws ReauthenticationRequiredException when the session can no longer reach the backend
   */
  public static <T> @NotNull Answer<T> of(@NotNull Supplier<T> call) {
    try {
      return new Answer<>(true, call.get());
    } catch (BackendServiceException failure) {
      SectionFallback.rethrowIfReauthenticationRequired(failure);
      log.debug("Session gate status unreadable; letting the request through (backend enforces).");
      return new Answer<>(false, null);
    } catch (RuntimeException failure) {
      SectionFallback.rethrowIfReauthenticationRequired(failure);
      throw failure;
    }
  }

  /**
   * What a gate's status read returned.
   *
   * @param answered whether the backend answered
   * @param body the answer, or {@code null} when the backend sent no body or did not answer
   * @param <T> the status type
   */
  public record Answer<T>(boolean answered, @Nullable T body) {}
}
