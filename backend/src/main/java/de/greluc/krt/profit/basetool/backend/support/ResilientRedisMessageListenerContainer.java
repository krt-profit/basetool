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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * A {@link RedisMessageListenerContainer} whose <em>first</em> subscription may fail without taking
 * the application down with it.
 *
 * <p><strong>The outage this exists to prevent (2026-09-02, 07:07:09Z).</strong> Redis was
 * recreated during a deploy. The backend, restarting into that window, logged {@code
 * ApplicationContextException: Failed to start bean 'liveSyncRedisMessageListenerContainer'} and
 * cancelled its context refresh — a crash loop of roughly one boot per minute, during which the
 * frontend had no API at all. The cause is upstream and deliberate: {@code start()} calls {@code
 * lazyListen()}, whose {@code InitialBackoffExecution} branch rethrows the connection failure
 * instead of backing off, and Spring's lifecycle processor turns any exception out of a {@link
 * org.springframework.context.SmartLifecycle#start()} into a failed refresh. A pub/sub fan-out the
 * application is designed to survive the loss of (ADR-0084, ADR-0143 — a Redis outage degrades
 * cross-replica sync to single-instance behaviour and never worse) must not be able to decide
 * whether the application exists at all.
 *
 * <p><strong>Why the retry has to stop first.</strong> Upstream {@code start()} is {@code if
 * (started.compareAndSet(false, true)) { lazyListen(); }} — the flag is set <em>before</em> the
 * throw, so a failed attempt leaves the container claiming to run while nothing is subscribed, and
 * a bare second {@code start()} is a silent no-op forever. {@code stop()} compare-and-sets the flag
 * back, and its {@code stopListening()} returns immediately because the failed attempt never
 * activated a listener. Hence the {@code super.stop(Runnable)} inside the catch rather than in the
 * retry — and {@code stop(Runnable)}, never the no-argument {@code stop()}, for the reason {@link
 * #NO_OP_CALLBACK} documents.
 *
 * <p>Only the <em>initial</em> subscription is covered here. Once the container is listening, a
 * later connection loss is handled by the container's own recovery back-off, which was never the
 * problem: the 07:05:44Z {@code ConnectionWatchdog} lines in the same export are that mechanism
 * working.
 *
 * <p>This is a safety net, not a licence. The first failure is logged at WARN and {@code
 * basetool_redis_fanout_subscribed} reads 0 for as long as the subscription is missing, so a
 * fan-out that never comes back is visible rather than merely survivable.
 */
@Slf4j
public class ResilientRedisMessageListenerContainer extends RedisMessageListenerContainer {

  /** Default delay between two subscription attempts, in milliseconds. */
  public static final long DEFAULT_RETRY_INTERVAL_MILLIS = 5_000L;

  /** Delay between two subscription attempts, in milliseconds. */
  private final long retryIntervalMillis;

  /**
   * Handed to {@code super.stop(Runnable)} where only the superclass's own teardown is wanted.
   *
   * <p>{@code super.stop()} would be wrong there: upstream's no-argument {@code stop()} body is
   * {@code stop(() -> {})}, a <em>virtual</em> call that lands back in {@link #stop(Runnable)} —
   * which sets {@link #stopRequested} and cancels the retry that the caller is about to schedule.
   * The first version of this class did exactly that, and retried precisely never.
   */
  private static final Runnable NO_OP_CALLBACK = () -> {};

  /** Guards {@link #retryExecutor} and {@link #pendingRetry}. */
  private final Object retryMonitor = new Object();

  /**
   * Bean name captured from {@link #setBeanName(String)}, used only to name this container in log
   * lines and in the retry thread's name. The superclass keeps its copy private.
   */
  private @Nullable String containerName;

  /**
   * Runs the retry task. Created on the first failed attempt and shut down as soon as the
   * subscription succeeds, so a healthy start leaves no thread behind. Guarded by {@link
   * #retryMonitor}.
   */
  private @Nullable ScheduledExecutorService retryExecutor;

  /** The scheduled, not yet run retry, kept so the shutdown paths can cancel it. */
  private @Nullable ScheduledFuture<?> pendingRetry;

  /**
   * Set by the shutdown paths and cleared by {@link #start()}; makes a retry that is already in
   * flight give up instead of resurrecting a container the context is tearing down.
   */
  private volatile boolean stopRequested;

  /** Creates a container that retries a failed initial subscription every five seconds. */
  public ResilientRedisMessageListenerContainer() {
    this(DEFAULT_RETRY_INTERVAL_MILLIS);
  }

  /**
   * Creates a container that retries a failed initial subscription at the given interval.
   *
   * @param retryIntervalMillis delay between two subscription attempts, in milliseconds; must be
   *     positive. Tests pass a short value; production uses {@link #DEFAULT_RETRY_INTERVAL_MILLIS}.
   * @throws IllegalArgumentException if the interval is not positive, which would busy-loop the
   *     retry thread.
   */
  public ResilientRedisMessageListenerContainer(long retryIntervalMillis) {
    if (retryIntervalMillis <= 0) {
      throw new IllegalArgumentException("retryIntervalMillis must be positive");
    }
    this.retryIntervalMillis = retryIntervalMillis;
  }

  /**
   * Records the bean name for log lines, then hands it to the superclass unchanged.
   *
   * @param name the bean name Spring assigned to this container.
   */
  @Override
  public void setBeanName(@NotNull String name) {
    this.containerName = name;
    super.setBeanName(name);
  }

  /**
   * Subscribes to the configured channels, scheduling a retry instead of propagating a failure.
   *
   * <p>Overridden solely to swallow that failure: an exception out of a {@code SmartLifecycle}
   * start aborts the entire context refresh, which is the crash loop the class Javadoc describes.
   */
  @Override
  public void start() {
    stopRequested = false;
    attemptStart(false);
  }

  /**
   * Stops the container and abandons any pending retry.
   *
   * <p>Upstream's no-argument {@code stop()} delegates here, and so does {@code destroy()} through
   * it, so this single override covers every shutdown path.
   *
   * @param callback upstream's completion callback, invoked by the superclass.
   */
  @Override
  public void stop(@NotNull Runnable callback) {
    stopRequested = true;
    shutdownRetries(true);
    super.stop(callback);
  }

  /**
   * Stops the container and releases the retry thread for good.
   *
   * @throws Exception if the superclass fails to release its own resources.
   */
  @Override
  public void destroy() throws Exception {
    stopRequested = true;
    shutdownRetries(true);
    super.destroy();
  }

  /**
   * Runs one subscription attempt, scheduling the next one when it fails.
   *
   * @param isRetry {@code false} for the attempt made during context refresh, {@code true} for a
   *     scheduled one. It decides the log level, so an outage lasting hours costs one WARN and then
   *     DEBUG lines rather than a line every five seconds.
   */
  private void attemptStart(boolean isRetry) {
    try {
      super.start();
      if (stopRequested) {
        // Shutdown began while this attempt was in flight: do not leave behind a subscription that
        // the lifecycle processor has already walked past and will never stop.
        super.stop(NO_OP_CALLBACK);
        return;
      }
      shutdownRetries(false);
      if (isRetry) {
        log.info("Redis fan-out subscription re-established for container '{}'.", describe());
      }
    } catch (RuntimeException ex) {
      // Upstream sets the started flag before it throws, so without this the container claims to be
      // running and every later start() is a silent no-op. See the class Javadoc. It must be
      // super.stop(Runnable) and not super.stop(): the latter dispatches virtually back into this
      // class's override, which would cancel the retry scheduled two lines below.
      super.stop(NO_OP_CALLBACK);
      if (isRetry) {
        log.debug("Redis fan-out subscription retry for '{}' failed again.", describe(), ex);
      } else {
        log.warn(
            "Redis is unreachable: container '{}' starts unsubscribed and retries every {} ms."
                + " Cross-instance fan-out stays inactive until it succeeds ({}).",
            describe(),
            retryIntervalMillis,
            ex.toString());
      }
      scheduleRetry();
    }
  }

  /** Schedules the next attempt, unless the container is being shut down. */
  private void scheduleRetry() {
    synchronized (retryMonitor) {
      if (stopRequested) {
        return;
      }
      if (retryExecutor == null) {
        retryExecutor = Executors.newSingleThreadScheduledExecutor(this::newRetryThread);
      }
      pendingRetry =
          retryExecutor.schedule(this::retryOnce, retryIntervalMillis, TimeUnit.MILLISECONDS);
    }
  }

  /** Runs one scheduled attempt, checking the shutdown flag first. */
  private void retryOnce() {
    if (!stopRequested) {
      attemptStart(true);
    }
  }

  /**
   * Creates the daemon thread the retry runs on.
   *
   * @param runnable the retry task.
   * @return a daemon thread named after the container, so a thread dump attributes it; daemon
   *     because a fan-out that never reconnects must not keep the JVM alive.
   */
  private @NotNull Thread newRetryThread(@NotNull Runnable runnable) {
    Thread thread = new Thread(runnable, "redis-subscribe-retry-" + describe());
    thread.setDaemon(true);
    return thread;
  }

  /**
   * Cancels a pending retry and releases the retry thread.
   *
   * @param interruptRunning {@code true} from the shutdown paths, where a retry already in flight
   *     must not outlive the call; {@code false} from the success path, which runs <em>on</em> the
   *     retry thread and would otherwise interrupt itself.
   */
  private void shutdownRetries(boolean interruptRunning) {
    synchronized (retryMonitor) {
      if (pendingRetry != null) {
        pendingRetry.cancel(interruptRunning);
        pendingRetry = null;
      }
      if (retryExecutor != null) {
        retryExecutor.shutdown();
        retryExecutor = null;
      }
    }
  }

  /**
   * Names this container for a log line and for the retry thread.
   *
   * @return the bean name once Spring has assigned one, else the simple class name.
   */
  private @NotNull String describe() {
    return containerName != null ? containerName : getClass().getSimpleName();
  }
}
