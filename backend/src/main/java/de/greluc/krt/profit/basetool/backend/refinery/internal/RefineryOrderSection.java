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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The independently locked sections of a refinery order and their counters (REQ-REFINERY-029).
 *
 * <p>The counters are only checked and raised on an order loaded through {@link
 * RefineryOrderRepository#lockById}: the row lock makes the check and the raise one atomic step.
 */
enum RefineryOrderSection {
  /** Location, method, timer, status, goods and the store marker. */
  GAME_DATA {
    @Override
    @Nullable
    Long read(@NotNull RefineryOrder order) {
      return order.getGameDataVersion();
    }

    @Override
    void write(@NotNull RefineryOrder order, long value) {
      order.setGameDataVersion(value);
    }
  },
  /** Expenses, other expenses and ore sales. */
  FINANCES {
    @Override
    @Nullable
    Long read(@NotNull RefineryOrder order) {
      return order.getFinancesVersion();
    }

    @Override
    void write(@NotNull RefineryOrder order, long value) {
      order.setFinancesVersion(value);
    }
  };

  /**
   * Reads this section's counter of an order.
   *
   * @param order the order
   * @return the counter, {@code null} only on a transient request order without an echo
   */
  @Nullable
  abstract Long read(@NotNull RefineryOrder order);

  /**
   * Writes this section's counter of an order.
   *
   * @param order the order
   * @param value the new counter
   */
  abstract void write(@NotNull RefineryOrder order, long value);

  /**
   * Refuses a write whose echo of this section's counter differs from the locked order's.
   *
   * @param order the locked, managed order
   * @param echoed the counter the caller last saw; {@code null} is a conflict
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echo is absent
   *     or stale
   */
  void check(@NotNull RefineryOrder order, @Nullable Long echoed) {
    OptimisticLock.check(read(order), echoed, RefineryOrder.class, order.getId());
  }

  /**
   * Raises this section's counter of the locked order by one.
   *
   * @param order the locked, managed order
   * @throws ArithmeticException when the counter is already at {@link Long#MAX_VALUE}
   */
  void advance(@NotNull RefineryOrder order) {
    write(order, Math.addExact(Objects.requireNonNull(read(order)), 1L));
  }
}
