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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Serializes SC Wiki request starts and retries bounded transient read failures. */
@RequiredArgsConstructor
final class ScWikiRequestExecutor {

  /** Longest upstream cooldown retained without overflowing monotonic time arithmetic. */
  private static final Duration MAX_TRACKED_COOLDOWN = Duration.ofDays(1);

  /** Largest exponential shift allowed by the bounded attempt configuration. */
  private static final int MAX_BACKOFF_SHIFT = 4;

  /** Shared response policy for version, list and detail requests. */
  private static final NonSuccessStatusHandler NON_SUCCESS_STATUS_HANDLER =
      new NonSuccessStatusHandler();

  /** Client whose request factory owns the connection and read timeouts. */
  private final RestClient client;

  /** Limits shared by every version, page and detail request. */
  private final ScWikiProperties properties;

  /** Earliest next request start, measured against the monotonic clock. */
  private long nextRequestNanos = System.nanoTime();

  /** Shared upstream cooldown, measured against the monotonic clock. */
  private long retryAfterNanos = System.nanoTime();

  /**
   * Fetches a resource, returning null for an absent resource.
   *
   * @param uriTemplate relative URI template
   * @param variables template variable values
   * @return the successful body, or null on HTTP 404
   */
  @Nullable
  String get(String uriTemplate, Object... variables) {
    return getResult(uriTemplate, variables).body();
  }

  /**
   * Fetches a resource while retaining whether the source explicitly returned HTTP 404.
   *
   * @param uriTemplate relative URI template
   * @param variables template variable values
   * @return successful transport response or an explicit missing-resource response
   */
  Response getResult(String uriTemplate, Object... variables) {
    int attempts = Math.max(1, properties.maxAttempts());
    for (int attempt = 1; ; attempt++) {
      awaitPermit();
      try {
        ResponseEntity<String> response =
            client
                .get()
                .uri(uriTemplate, variables)
                .retrieve()
                .onStatus(NON_SUCCESS_STATUS_HANDLER)
                .toEntity(String.class);
        return new Response(false, response.getBody());
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() == 404) {
          return new Response(true, null);
        }
        if (!retryable(e.getStatusCode().value())) {
          throw e;
        }
        Duration delay = retryDelay(attempt, e.getResponseHeaders());
        if (e.getResponseHeaders() != null
            && e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER) != null) {
          defer(delay);
        }
        if (attempt >= attempts || delay.compareTo(maxRetryDelay()) > 0) {
          throw e;
        }
        defer(delay);
      } catch (ResourceAccessException e) {
        if (attempt >= attempts) {
          throw e;
        }
        defer(backoff(attempt));
      }
    }
  }

  /** Waits for the shared request-start permit, including any upstream cooldown. */
  private synchronized void awaitPermit() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("SC Wiki request thread is interrupted");
    }
    long now = System.nanoTime();
    long delay = Math.max(nextRequestNanos - now, retryAfterNanos - now);
    if (retryAfterNanos - now > maxRetryDelay().toNanos()) {
      throw new IllegalStateException("SC Wiki upstream cooldown exceeds the request wait limit");
    }
    if (delay > 0) {
      try {
        TimeUnit.NANOSECONDS.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("SC Wiki request interrupted before dispatch", e);
      }
    }
    int rate = Math.max(1, properties.requestsPerSecond());
    nextRequestNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1) / rate;
  }

  /**
   * Extends the cooldown seen by every request thread.
   *
   * @param delay minimum pause before another request
   */
  private synchronized void defer(Duration delay) {
    Duration bounded = delay.compareTo(MAX_TRACKED_COOLDOWN) > 0 ? MAX_TRACKED_COOLDOWN : delay;
    retryAfterNanos = Math.max(retryAfterNanos, System.nanoTime() + bounded.toNanos());
  }

  /**
   * Reads Retry-After as delta seconds or an HTTP date, falling back to exponential backoff.
   *
   * @param attempt failed attempt number
   * @param headers upstream response headers
   * @return the requested cooldown
   */
  private Duration retryDelay(int attempt, @Nullable HttpHeaders headers) {
    String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (value == null || value.isBlank()) {
      return backoff(attempt);
    }
    try {
      return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
    } catch (NumberFormatException _) {
      try {
        Duration remaining =
            Duration.between(
                Instant.now(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        return remaining.isNegative() ? Duration.ZERO : remaining;
      } catch (DateTimeParseException _) {
        return backoff(attempt);
      }
    }
  }

  /**
   * Computes the bounded exponential cooldown.
   *
   * @param attempt failed attempt number
   * @return cooldown before the next attempt
   */
  private Duration backoff(int attempt) {
    long delay = (long) properties.retryDelayMillis() << Math.min(attempt - 1, MAX_BACKOFF_SHIFT);
    return Duration.ofMillis(Math.min(delay, properties.maxRetryDelayMillis()));
  }

  /**
   * Returns the longest cooldown this caller will wait.
   *
   * @return configured maximum retry delay
   */
  private Duration maxRetryDelay() {
    return Duration.ofMillis(properties.maxRetryDelayMillis());
  }

  /**
   * Identifies statuses for which repeating a read can succeed.
   *
   * @param status HTTP status
   * @return whether the status permits a bounded retry
   */
  private static boolean retryable(int status) {
    return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
  }

  /** Classifies non-2xx responses as errors while retaining the source response diagnostics. */
  private static final class NonSuccessStatusHandler extends DefaultResponseErrorHandler {

    /**
     * Classifies responses without a successful representation as errors.
     *
     * @param statusCode source HTTP status
     * @return whether the response is outside the 2xx range
     */
    @Override
    protected boolean hasError(@NotNull HttpStatusCode statusCode) {
      return !statusCode.is2xxSuccessful();
    }
  }

  /**
   * Retains an explicit absence separately from an unusable successful body.
   *
   * @param missing whether the source returned HTTP 404
   * @param body successful response body, absent on HTTP 404 or a bodyless response
   */
  record Response(boolean missing, @Nullable String body) {}
}
