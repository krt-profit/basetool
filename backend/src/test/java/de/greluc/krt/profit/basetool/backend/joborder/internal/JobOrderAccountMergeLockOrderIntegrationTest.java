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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The account merge takes the job-order money identity lock before it locks the two account rows
 * (REQ-SEC-046), in the order every money writer takes them: a writer that holds the identity lock
 * and then references the account row, as a money notice does when it inserts its notification,
 * never deadlocks against a merge of that account. Writes commit.
 */
@SpringBootTest
class JobOrderAccountMergeLockOrderIntegrationTest {

  /** How long either side may take before the test calls it hung. */
  private static final long TIMEOUT_SECONDS = 30;

  /** How often the test looks for the merge waiting on the identity lock. */
  private static final long POLL_MILLIS = 50;

  @Autowired private UserAccountMergeService accountMerge;
  @Autowired private JobOrderMoneyIdentityLock identityLock;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  private UUID source;
  private UUID target;
  private UUID admin;

  @BeforeEach
  void setUp() {
    source = account("merge-lock-src-");
    target = account("merge-lock-tgt-");
    admin = account("merge-lock-admin-");
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  @Test
  void aMoneyWriterHoldingTheIdentityLockAndAMergeOfItsAccountBothComplete() throws Exception {
    CountDownLatch identityHeld = new CountDownLatch(1);
    CountDownLatch mergeWaiting = new CountDownLatch(1);
    Future<?> writer =
        pool.submit(() ->
            transactionTemplate.executeWithoutResult(_ -> {
              identityLock.lockAccounts(source);
              identityHeld.countDown();
              awaitQuietly(mergeWaiting);
              jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR KEY SHARE", source);
            }));
    assertThat(identityHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

    Future<?> merge = pool.submit(() -> accountMerge.merge(source, target, admin));
    awaitAnAdvisoryLockWaiter();
    mergeWaiting.countDown();

    writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    merge.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Waits until a session waits for an advisory lock, which is where the merge stops while the
   * writer holds the identity lock.
   *
   * @throws InterruptedException when the test thread is interrupted
   */
  private void awaitAnAdvisoryLockWaiter() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(POLL_MILLIS);
    }
    throw new AssertionError("The merge never waited for the identity lock");
  }

  /**
   * Waits for a latch inside the writer's transaction, failing the writer when it never opens.
   *
   * @param latch the latch the test thread opens
   */
  private static void awaitQuietly(@NotNull CountDownLatch latch) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("The merge never started");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the merge", e);
    }
  }

  /**
   * Creates a bare account.
   *
   * @param prefix the username prefix
   * @return the account's id
   */
  private @NotNull UUID account(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    return userRepository.saveAndFlush(user).getId();
  }
}
