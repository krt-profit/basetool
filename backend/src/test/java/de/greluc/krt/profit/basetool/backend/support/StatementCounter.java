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

import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionEventListener;
import org.jetbrains.annotations.NotNull;
import org.springframework.util.function.ThrowingSupplier;

/**
 * Counts the JDBC statements Hibernate prepares on the calling thread, so a statement bound
 * (REQ-DATA-003) measures its own work and not the scheduled jobs and async listeners that share
 * the application context.
 *
 * <p>{@code application-test.yml} registers this class as Hibernate's automatic session event
 * listener, one instance per session. It counts the same event as {@code
 * Statistics#getPrepareStatementCount()}, which sums it over every thread of the session factory.
 */
public final class StatementCounter implements SessionEventListener {

  private static final ThreadLocal<AtomicLong> RUNNING = new ThreadLocal<>();

  /**
   * Runs the work on the calling thread and counts the statements Hibernate prepares for it there.
   *
   * <p>A MockMvc request runs on the calling thread, so its count covers the whole request.
   *
   * @param work the work to measure, such as a MockMvc request with its expectations
   * @return the number of statements prepared on this thread while the work ran
   * @throws Exception whatever the work throws
   */
  public static long preparedBy(@NotNull ThrowingSupplier<?> work) throws Exception {
    AtomicLong statements = new AtomicLong();
    RUNNING.set(statements);
    try {
      work.getWithException();
    } finally {
      RUNNING.remove();
    }
    return statements.get();
  }

  /** Counts one statement when a {@link #preparedBy} measurement runs on this thread. */
  @Override
  public void jdbcPrepareStatementStart() {
    AtomicLong statements = RUNNING.get();
    if (statements != null) {
      statements.incrementAndGet();
    }
  }
}
