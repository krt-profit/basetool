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

package de.greluc.krt.profit.basetool.ingest.ratelimit;

import lombok.Getter;

/**
 * Thrown by {@link SubjectRateLimiter} when the authenticated caller has exhausted their
 * per-subject ingest budget. Translated by the gateway's {@code GlobalExceptionHandler} into an RFC
 * 7807 {@code 429 Too Many Requests} with a {@code Retry-After} header carrying {@link
 * #getRetryAfterSeconds()}.
 */
@Getter
public class RateLimitedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Seconds until the caller's bucket refills enough for one more request; sent as the {@code
   * Retry-After} header.
   */
  private final long retryAfterSeconds;

  /**
   * Creates the exception with the time the client should wait before retrying.
   *
   * @param retryAfterSeconds seconds until the caller's bucket refills enough for one more request
   */
  public RateLimitedException(long retryAfterSeconds) {
    super("Ingest per-subject rate limit exceeded");
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
