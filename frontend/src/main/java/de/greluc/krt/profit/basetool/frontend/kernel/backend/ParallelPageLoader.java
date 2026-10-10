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

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PreDestroy;
import java.util.Map;
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
 * caller's request context on each (REQ-FE-030).
 *
 * <p>Every {@code ThreadLocalAccessor} of the {@link ContextRegistry} — the relays {@code
 * ReactorContextPropagationConfig} registers (org unit, correlation id, user locale, client IP) and
 * those libraries register — is captured on the caller and restored on the worker through one
 * {@link ContextSnapshot}, the same accessors Reactor's automatic propagation uses. The security
 * context, the request attributes and the MDC, which have no accessor of their own here, are copied
 * explicitly. A holder the caller has not set is cleared on the worker.
 *
 * <p>Use only inside a servlet request, and join every returned future before the controller method
 * returns.
 */
@Component
public class ParallelPageLoader {

  /** Virtual-thread executor running the page-load tasks. */
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("page-loader-", 0L).factory());

  /** Captures and restores every registered thread-local accessor. */
  private final ContextSnapshotFactory snapshots;

  /**
   * Creates the loader on the global {@link ContextRegistry}, the one Reactor propagates through.
   */
  public ParallelPageLoader() {
    this(ContextRegistry.getInstance());
  }

  /**
   * Creates the loader on a given registry.
   *
   * @param registry the registry whose accessors are captured and restored
   */
  ParallelPageLoader(@NotNull ContextRegistry registry) {
    this.snapshots =
        ContextSnapshotFactory.builder().contextRegistry(registry).clearMissing(true).build();
  }

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
    ContextSnapshot snapshot = snapshots.captureAll();
    SecurityContext securityContext = SecurityContextHolder.getContext();
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    Map<String, String> mdc = MDC.getCopyOfContextMap();

    return CompletableFuture.supplyAsync(
        () -> {
          try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
            try {
              applyRequestState(securityContext, requestAttributes, mdc);
              return task.get();
            } finally {
              clearRequestState();
            }
          }
        },
        executor);
  }

  /**
   * Installs the captured security context, request attributes and MDC on the current worker
   * thread.
   */
  private static void applyRequestState(
      @NotNull SecurityContext securityContext,
      @Nullable RequestAttributes requestAttributes,
      @Nullable Map<String, String> mdc) {
    SecurityContextHolder.setContext(securityContext);
    if (requestAttributes != null) {
      RequestContextHolder.setRequestAttributes(requestAttributes);
    }
    if (mdc != null) {
      MDC.setContextMap(mdc);
    }
  }

  /** Removes the security context, request attributes and MDC from the worker thread. */
  private static void clearRequestState() {
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
