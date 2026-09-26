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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.logging.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.logging.ClientIpContext;
import de.greluc.krt.profit.basetool.frontend.logging.CorrelationContext;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Runs independent backend fetches for one page in parallel on virtual threads, re-establishing the
 * caller's request context on each: security context, request attributes, {@link
 * CorrelationContext}, {@link ActiveSquadronContext}, {@link ClientIpContext} and the MDC, which
 * the outbound WebClient pipeline needs for its headers.
 *
 * <p>Use only inside a servlet request, and join every returned future before the controller method
 * returns.
 */
@Component
public class ParallelPageLoader {

  /** Virtual-thread executor running the page-load tasks. */
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("page-loader-", 0L).factory());

  /**
   * Submits the supplier to the virtual-thread executor with the caller's request context
   * propagated. Supplier exceptions complete the future exceptionally, wrapped in {@link
   * java.util.concurrent.CompletionException}.
   *
   * @param task the work to run on a worker thread; must not be {@code null}
   * @param <T> return type of the task
   * @return a future completing with the supplier's result
   */
  @NotNull
  public <T> CompletableFuture<T> loadAsync(@NotNull Supplier<T> task) {
    UUID activeSquadron = ActiveSquadronContext.get();
    String correlationId = CorrelationContext.get();
    String clientIp = ClientIpContext.get();
    SecurityContext securityContext = SecurityContextHolder.getContext();
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    Map<String, String> mdc = MDC.getCopyOfContextMap();

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            applyContext(
                activeSquadron, correlationId, clientIp, securityContext, requestAttributes, mdc);
            return task.get();
          } finally {
            clearContext();
          }
        },
        executor);
  }

  /**
   * Installs the captured request-scoped state (authentication, squadron pin, client IP,
   * correlation id, MDC) on the current worker thread.
   */
  private static void applyContext(
      @Nullable UUID activeSquadron,
      @Nullable String correlationId,
      @Nullable String clientIp,
      @NotNull SecurityContext securityContext,
      @Nullable RequestAttributes requestAttributes,
      @Nullable Map<String, String> mdc) {
    if (activeSquadron != null) {
      ActiveSquadronContext.set(activeSquadron);
    }
    if (correlationId != null) {
      CorrelationContext.set(correlationId);
    }
    if (clientIp != null) {
      ClientIpContext.set(clientIp);
    }
    SecurityContextHolder.setContext(securityContext);
    if (requestAttributes != null) {
      RequestContextHolder.setRequestAttributes(requestAttributes);
    }
    if (mdc != null) {
      MDC.setContextMap(mdc);
    }
  }

  /** Removes every thread-local entry the helper might have populated. */
  private static void clearContext() {
    ActiveSquadronContext.clear();
    CorrelationContext.clear();
    ClientIpContext.clear();
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
    MDC.clear();
  }

  /** Shuts down the virtual-thread executor on bean destruction. */
  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
