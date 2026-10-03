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

package de.greluc.krt.profit.basetool.backend.exception;

import java.util.Map;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpHeaders;

/**
 * Thrown by a controller whose own budget refused the call, as opposed to the request filters'
 * budgets (REQ-API-004).
 *
 * <p>Mapped to HTTP {@code 429} with the code {@code RATE_LIMIT_EXCEEDED} ({@link
 * AppExceptionKind#RATE_LIMIT_EXCEEDED}) and a {@code Retry-After} header.
 */
@Getter
public final class RateLimitExceededException extends AppException {

  /** The rate limiters' own retry header, sent beside {@code Retry-After}. */
  static final String RETRY_AFTER_SECONDS_HEADER = "X-Rate-Limit-Retry-After-Seconds";

  /** Whole seconds until the budget admits the next call; at least one. */
  private final long retryAfterSeconds;

  /**
   * Creates the refusal.
   *
   * @param message verbatim {@code detail} or an i18n key
   * @param retryAfterSeconds whole seconds until the budget admits the next call; raised to one
   */
  public RateLimitExceededException(String message, long retryAfterSeconds) {
    super(AppExceptionKind.RATE_LIMIT_EXCEEDED, message);
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  /**
   * Returns the {@code Retry-After} header and the limiters' {@code
   * X-Rate-Limit-Retry-After-Seconds} twin, both carrying {@link #retryAfterSeconds}.
   *
   * @return the two retry headers
   */
  @NotNull
  @Unmodifiable
  @Override
  public Map<String, String> responseHeaders() {
    String seconds = String.valueOf(retryAfterSeconds);
    return Map.of(HttpHeaders.RETRY_AFTER, seconds, RETRY_AFTER_SECONDS_HEADER, seconds);
  }
}
