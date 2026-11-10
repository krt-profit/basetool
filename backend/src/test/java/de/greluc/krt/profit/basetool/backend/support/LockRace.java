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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Plays two transactions on PostgreSQL against each other: the first runs a step and stays open,
 * the second starts once the step returned and is seen waiting for a lock before the first commits.
 *
 * <p>Every piece of work runs on its own worker thread, as a signed-in member when a {@link Caller}
 * is given. Close the race after the test.
 */
public final class LockRace implements AutoCloseable {

  /** How long a step may take to reach its lock wait or its end. */
  private static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);

  /** How often the lock-wait probe looks at {@code pg_stat_activity}. */
  private static final Duration PROBE_INTERVAL = Duration.ofMillis(20);

  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;
  private final ExecutorService workers = Executors.newFixedThreadPool(2);

  /**
   * Creates a race over the test's transaction manager and database.
   *
   * @param transactions the template that opens the held transaction
   * @param jdbc the template that reads {@code pg_stat_activity}
   */
  public LockRace(@NotNull TransactionTemplate transactions, @NotNull JdbcTemplate jdbc) {
    this.transactions = transactions;
    this.jdbc = jdbc;
  }

  /**
   * A member a piece of work runs as, inside a request that may pin an org unit.
   *
   * @param userId the member's user id, the token's subject
   * @param role the member's role code
   * @param activeUnit the org unit the request pins, or {@code null}
   */
  public record Caller(@NotNull UUID userId, @NotNull String role, @Nullable UUID activeUnit) {

    /**
     * A member whose request pins no org unit.
     *
     * @param userId the member's user id
     * @param role the member's role code
     * @return the caller
     */
    public static @NotNull Caller of(@NotNull UUID userId, @NotNull String role) {
      return new Caller(userId, role, null);
    }
  }

  /**
   * A transaction whose step returned and that waits for {@link #commit()}.
   *
   * @param <T> the step's result
   */
  public static final class Held<T> {

    private final CountDownLatch commit;
    private final Future<T> result;

    private Held(@NotNull CountDownLatch commit, @NotNull Future<T> result) {
      this.commit = commit;
      this.result = result;
    }

    /**
     * Lets the held transaction commit and waits for it.
     *
     * @return the step's result
     * @throws Exception when the step or the commit failed, or did not end in time
     */
    public T commit() throws Exception {
      commit.countDown();
      return finish(result);
    }
  }

  /**
   * Runs a step in a transaction on a worker thread and returns once the step returned, with the
   * transaction still open.
   *
   * @param caller the member the step runs as, or {@code null} for none
   * @param step the step
   * @param <T> the step's result
   * @return the held transaction
   * @throws Exception when the step failed or did not return in time
   */
  public <T> @NotNull Held<T> hold(@Nullable Caller caller, @NotNull Supplier<T> step)
      throws Exception {
    CountDownLatch returned = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    Future<T> result =
        submit(
            caller,
            () ->
                transactions.execute(_ -> {
                  T value;
                  try {
                    value = step.get();
                  } finally {
                    returned.countDown();
                  }
                  await(commit);
                  return value;
                }));
    await(returned);
    if (result.isDone()) {
      finish(result);
    }
    return new Held<>(commit, result);
  }

  /**
   * Starts work on a worker thread.
   *
   * @param caller the member the work runs as, or {@code null} for none
   * @param work the work, which opens its own transaction
   * @param <T> the work's result
   * @return the pending result
   */
  public <T> @NotNull Future<T> start(@Nullable Caller caller, @NotNull Supplier<T> work) {
    return submit(caller, work);
  }

  /**
   * Starts work on a worker thread and returns once one more session of the database waits for a
   * lock than before.
   *
   * @param caller the member the work runs as, or {@code null} for none
   * @param work the work, which opens its own transaction
   * @param <T> the work's result
   * @return the pending result
   * @throws InterruptedException when the probe is interrupted
   */
  public <T> @NotNull Future<T> startWaiting(@Nullable Caller caller, @NotNull Supplier<T> work)
      throws InterruptedException {
    int waitingBefore = lockWaiters();
    Future<T> result = submit(caller, work);
    Instant deadline = Instant.now().plus(STEP_TIMEOUT);
    while (lockWaiters() <= waitingBefore && !result.isDone() && Instant.now().isBefore(deadline)) {
      Thread.sleep(PROBE_INTERVAL);
    }
    assertThat(result.isDone()).as("the second transaction ended without waiting").isFalse();
    assertThat(lockWaiters())
        .as("the second transaction waits for a lock")
        .isGreaterThan(waitingBefore);
    return result;
  }

  /**
   * Waits for a worker's result.
   *
   * @param future the worker
   * @param <T> the result
   * @return the result
   * @throws ExecutionException when the worker failed; its cause is the worker's exception
   * @throws InterruptedException when the wait is interrupted
   * @throws TimeoutException when the worker did not finish in time
   */
  public static <T> T finish(@NotNull Future<T> future)
      throws ExecutionException, InterruptedException, TimeoutException {
    return future.get(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    workers.shutdownNow();
  }

  /**
   * Submits work to a worker thread, signed in as the caller.
   *
   * @param caller the member, or {@code null} for none
   * @param work the work
   * @param <T> the result
   * @return the pending result
   */
  private <T> @NotNull Future<T> submit(@Nullable Caller caller, @NotNull Supplier<T> work) {
    return workers.submit(() -> {
      if (caller == null) {
        return work.get();
      }
      MockHttpServletRequest request = new MockHttpServletRequest();
      if (caller.activeUnit() != null) {
        request.addHeader(
            RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, caller.activeUnit().toString());
      }
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
      SecurityContextHolder.getContext()
          .setAuthentication(
              new JwtAuthenticationToken(
                  Jwt.withTokenValue("lock-race")
                      .header("alg", "none")
                      .subject(caller.userId().toString())
                      .build(),
                  List.of(new SimpleGrantedAuthority(Roles.authority(caller.role())))));
      try {
        return work.get();
      } finally {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
      }
    });
  }

  /**
   * Counts the sessions of this database that wait for a lock.
   *
   * @return the waiting sessions
   */
  private int lockWaiters() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                + " AND datname = current_database()",
            Integer.class);
    return count == null ? 0 : count;
  }

  /**
   * Waits for a latch, failing the test when it is not counted down in time.
   *
   * @param latch the latch
   */
  private static void await(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("step reached in time")
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
