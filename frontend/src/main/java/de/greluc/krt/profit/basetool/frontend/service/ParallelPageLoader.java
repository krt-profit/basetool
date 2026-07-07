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
 * Helper for page controllers that need to fetch several independent backend resources to render a
 * single view. Wraps each supplier in a virtual-thread task that re-establishes the calling
 * thread's request-scoped context (SecurityContext, ServletRequestAttributes, the project's custom
 * {@link CorrelationContext} / {@link ActiveSquadronContext} / {@link ClientIpContext}
 * thread-locals, and the SLF4J MDC map) before running it.
 *
 * <p>Why all of this matters: the frontend's outbound WebClient pipeline reads from these
 * thread-locals at filter-assembly time — the OAuth2 bearer-token relay needs {@link
 * SecurityContextHolder} and {@link RequestContextHolder}, the squadron-relay header needs {@link
 * ActiveSquadronContext}, the correlation-id propagation needs {@link CorrelationContext}, and the
 * {@code X-Forwarded-For} relay that lets the backend's per-IP rate limiter see the real client
 * (instead of collapsing every caller onto the one frontend-container IP and sharing a single
 * org-wide bucket) needs {@link ClientIpContext}. A worker thread that does not see these would
 * silently fall through to "no header" / "anonymous" paths, which the backend would then resolve as
 * "all squadrons", one shared rate-limit bucket, or reject as unauthenticated. The propagation here
 * mirrors the manual capture-and-restore pattern used elsewhere in the codebase for cross-thread
 * context flow (see {@link CorrelationContext}'s class Javadoc).
 *
 * <p>Use only inside a servlet request: the helper assumes a live {@link RequestAttributes} on the
 * calling thread and a live {@link HttpServletRequest} for the duration of the parallel calls.
 * Callers must {@code .join()} (or {@link CompletableFuture#allOf(CompletableFuture...)
 * allOf(...).join()}) on every returned future before the controller method returns, otherwise the
 * Tomcat request thread may complete and the captured {@link RequestAttributes} become stale.
 */
@Component
public class ParallelPageLoader {

  /**
   * Virtual-thread executor used to run the supplied page-load tasks. Virtual threads keep the
   * helper cheap to use even when a controller fires four or five small lookups, and they line up
   * with the rest of the application's {@code spring.threads.virtual.enabled=true} setup.
   */
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("page-loader-", 0L).factory());

  /**
   * Submits the given supplier to the virtual-thread pool with full request-scoped context
   * propagation. The returned future resolves on whichever virtual thread ran the supplier; the
   * caller is expected to either {@code .join()} it on the request thread or compose it via {@link
   * CompletableFuture#allOf(CompletableFuture...)} before the controller method returns.
   *
   * <p>Exceptions thrown by the supplier are wrapped in {@link
   * java.util.concurrent.CompletionException} on the future. Callers that want to degrade
   * gracefully should apply {@link CompletableFuture#exceptionally(java.util.function.Function)}
   * themselves; the helper does not attempt to interpret or recover from supplier failures.
   *
   * @param task the work to run on a worker thread; must not be {@code null}.
   * @param <T> return type of the task.
   * @return a future that completes with the supplier's result.
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
   * Installs the captured request-scoped state on the current (virtual-worker) thread so the
   * WebClient pipeline assembles requests with the same authentication, squadron, client IP,
   * correlation id and logging context the originating request thread had.
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
