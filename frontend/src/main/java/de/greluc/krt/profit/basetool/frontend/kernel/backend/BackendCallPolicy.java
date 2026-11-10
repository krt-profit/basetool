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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Protects complete backend operations through body consumption and decoding. */
@Component
public class BackendCallPolicy {

  /** Explicit end-to-end budget for a request with a longer export response timeout. */
  public static final String RESPONSE_BUDGET =
      BackendCallPolicy.class.getName() + ".responseBudget";

  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final TimeLimiter timeLimiter;
  private final Bulkhead bulkhead;
  private final Duration transferBudget;

  /**
   * Uses the shared backend resilience instances and their metric/event registrations.
   *
   * @param circuitBreakers the circuit-breaker registry
   * @param retries the retry registry
   * @param timeLimiters the time-limiter registry
   * @param bulkheads the concurrency-limit registry
   * @param httpProperties the configured export response budget
   */
  public BackendCallPolicy(
      @NotNull CircuitBreakerRegistry circuitBreakers,
      @NotNull RetryRegistry retries,
      @NotNull TimeLimiterRegistry timeLimiters,
      @NotNull BulkheadRegistry bulkheads,
      @NotNull AppHttpProperties httpProperties) {
    circuitBreaker = circuitBreakers.circuitBreaker("backendApi");
    retry = retries.retry("backendApi");
    timeLimiter = timeLimiters.timeLimiter("backendApi");
    bulkhead = bulkheads.bulkhead("backendApi");
    transferBudget = httpProperties.exportResponseTimeout();
  }

  /**
   * Returns the configured total budget for a binary transfer.
   *
   * @return the export response budget
   */
  public @NotNull Duration transferBudget() {
    return transferBudget;
  }

  /**
   * Applies the backend policy to the caller-observed operation.
   *
   * @param method the request method, limiting retries to safe reads
   * @param operation the complete request and decode operation
   * @param responseBudget an explicit transfer budget, or null for the normal time limiter
   * @param retryAllowed whether the operation can be replayed before any output was exposed
   * @param <T> the decoded result
   * @return the protected operation
   */
  public <T> @NotNull Mono<T> protect(
      @NotNull HttpMethod method,
      @NotNull Mono<T> operation,
      @Nullable Duration responseBudget,
      boolean retryAllowed) {
    return protect(method, operation, responseBudget, retryAllowed, null);
  }

  <T> @NotNull Mono<T> protect(
      @NotNull HttpMethod method,
      @NotNull Mono<T> operation,
      @Nullable Duration responseBudget,
      boolean retryAllowed,
      @Nullable BackendResponseStatus responseStatus) {
    Mono<Decoded<T>> protectedCall =
        operation
            .map(value -> new Decoded<>(value, null))
            .defaultIfEmpty(new Decoded<>(null, null))
            .onErrorResume(
                WebClientResponseException.class,
                failure ->
                    failure.getStatusCode().is4xxClientError()
                        ? Mono.just(new Decoded<>(null, failure))
                        : Mono.error(failure))
            .transformDeferred(BulkheadOperator.of(bulkhead))
            .transformDeferred(mono ->
                responseBudget == null
                    ? mono.transformDeferred(TimeLimiterOperator.of(timeLimiter))
                    : mono.timeout(responseBudget))
            .onErrorResume(failure ->
                responseStatus != null && responseStatus.refusal() != null
                    ? Mono.just(new Decoded<>(null, responseStatus.refusal()))
                    : Mono.error(failure));
    if (retryAllowed
        && (method == HttpMethod.GET
            || method == HttpMethod.HEAD
            || method == HttpMethod.OPTIONS
            || method == HttpMethod.TRACE)) {
      protectedCall = protectedCall.transformDeferred(RetryOperator.of(retry));
    }
    return protectedCall
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .flatMap(decoded ->
            decoded.refusal() == null
                ? Mono.justOrEmpty(decoded.value())
                : Mono.error(decoded.refusal()));
  }

  /** Retains HTTP client refusals outside retry and circuit-breaker failure accounting. */
  private record Decoded<T>(@Nullable T value, @Nullable WebClientResponseException refusal) {}
}
