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

package de.greluc.krt.profit.basetool.backend.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Async} and provides the dedicated bounded executors for asynchronous workloads.
 *
 * <p>Each pool rejects with a {@link java.util.concurrent.RejectedExecutionException} when full
 * instead of growing without bound.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

  /** Spring-bean name of the UEX executor, referenced from {@code @Async("uexExecutor")}. */
  public static final String UEX_EXECUTOR = "uexExecutor";

  /**
   * Spring-bean name of the SC Wiki executor, referenced from {@code @Async("scWikiExecutor")}.
   * Distinct from {@link #UEX_EXECUTOR} so a slow Wiki response cannot starve the UEX sync (and
   * vice-versa).
   */
  public static final String SCWIKI_EXECUTOR = "scWikiExecutor";

  /**
   * Spring-bean name of the P4K import executor, referenced from {@code @Async("importExecutor")}.
   * A single worker thread so catalog imports (preview / apply) run strictly one at a time.
   */
  public static final String IMPORT_EXECUTOR = "importExecutor";

  /**
   * Bean name of the executor for after-commit notification creation, referenced from
   * {@code @Async("notificationExecutor")}.
   */
  public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

  /**
   * Bean name of the executor for after-commit transactional mail (REQ-NOTIF-014), referenced from
   * {@code @Async("mailExecutor")}.
   */
  public static final String MAIL_EXECUTOR = "mailExecutor";

  /**
   * Bounded executor for the UEX sync dispatched by {@link
   * de.greluc.krt.profit.basetool.backend.service.UexScheduler}.
   *
   * <p>Two core threads, at most four, a 100-slot queue, abort on overflow; waits up to 20 s for
   * in-flight tasks on shutdown and propagates the submitter's MDC.
   *
   * @return configured UEX async executor
   */
  @NotNull
  @Bean(name = UEX_EXECUTOR)
  public Executor uexExecutor() {
    return buildExecutor(2, 4, 100, "uex-async-", 20);
  }

  /**
   * Bounded executor for the SC Wiki sync dispatched by {@code ScWikiScheduler}.
   *
   * <p>Two core threads and no queue, so an overlapping submission beyond the pool is rejected
   * rather than left waiting; propagates the submitter's MDC.
   *
   * @return configured SC Wiki async executor
   */
  @NotNull
  @Bean(name = SCWIKI_EXECUTOR)
  public Executor scWikiExecutor() {
    return buildExecutor(2, 2, 0, "scwiki-async-", 20);
  }

  /**
   * Single-thread executor for the P4K catalog import dispatched by {@code P4kImportJobRunner}, so
   * import runs never overlap.
   *
   * <p>Queues up to 20 runs and waits up to 60 s on shutdown; a job still running at exit is
   * reconciled to {@code FAILED} on next startup. Propagates the submitter's MDC.
   *
   * @return configured P4K import async executor
   */
  @NotNull
  @Bean(name = IMPORT_EXECUTOR)
  public Executor importExecutor() {
    return buildExecutor(1, 1, 20, "p4k-import-", 60);
  }

  /**
   * Bounded executor for after-commit notification creation.
   *
   * <p>Two core threads, at most four, a 200-slot queue, abort on overflow; propagates the
   * publishing request's MDC.
   *
   * @return configured notification async executor
   */
  @NotNull
  @Bean(name = NOTIFICATION_EXECUTOR)
  public Executor notificationExecutor() {
    return buildExecutor(2, 4, 200, "notification-async-", 20);
  }

  /**
   * Bounded executor for after-commit transactional mail, separate from {@link
   * #notificationExecutor()} so a stalled SMTP relay cannot block notifications.
   *
   * <p>Two core threads, at most four, a 200-slot queue, abort on overflow; propagates the deciding
   * request's MDC.
   *
   * @return configured mail async executor
   */
  @NotNull
  @Bean(name = MAIL_EXECUTOR)
  public Executor mailExecutor() {
    return buildExecutor(2, 4, 200, "mail-async-", 20);
  }

  /**
   * Builds a bounded {@link ThreadPoolTaskExecutor} with abort policy, graceful shutdown and MDC
   * propagation.
   *
   * @param corePoolSize the number of always-alive worker threads
   * @param maxPoolSize the maximum number of worker threads
   * @param queueCapacity the work-queue depth; {@code 0} hands off directly
   * @param threadNamePrefix the prefix of the worker thread names
   * @param awaitTerminationSeconds the shutdown wait for in-flight tasks
   * @return an initialised executor ready to accept work
   */
  @NotNull
  private Executor buildExecutor(
      int corePoolSize,
      int maxPoolSize,
      int queueCapacity,
      String threadNamePrefix,
      int awaitTerminationSeconds) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(corePoolSize);
    executor.setMaxPoolSize(maxPoolSize);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix(threadNamePrefix);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
    executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
    executor.initialize();
    return executor;
  }

  /**
   * {@link TaskDecorator} that copies the submitting thread's {@link MDC} context onto the worker
   * thread for the task's duration.
   *
   * <p>MDC is cleared afterwards so reused pool threads never inherit a previous task's fields; a
   * missing snapshot starts the task with an empty MDC.
   */
  static class MdcPropagatingTaskDecorator implements TaskDecorator {

    @NotNull
    @Override
    public Runnable decorate(@NotNull Runnable runnable) {
      Map<String, String> snapshot = MDC.getCopyOfContextMap();
      return () -> {
        try {
          if (snapshot != null) {
            MDC.setContextMap(snapshot);
          }
          runnable.run();
        } finally {
          MDC.clear();
        }
      };
    }
  }
}
