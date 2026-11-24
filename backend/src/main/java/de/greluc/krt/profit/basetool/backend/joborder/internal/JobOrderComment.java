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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The comment of a job order as an edit part of its own, guarded by the comment revision beside the
 * contact's (REQ-ORDERS-066).
 *
 * <p>Callers hold the order's row lock, so the in-memory check and raise cannot interleave with
 * another writer.
 */
final class JobOrderComment {

  /** Non-instantiable static-helper holder. */
  private JobOrderComment() {}

  /**
   * Checks the comment revision the caller observed against the order's.
   *
   * @param order the locked order
   * @param observed the comment revision the caller last read; {@code null} is a conflict
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code observed}
   *     is missing or stale
   */
  static void check(@NotNull JobOrder order, @Nullable Long observed) {
    OptimisticLock.check(order.getCommentVersion(), observed, JobOrder.class, order.getId());
  }

  /**
   * Stores the trimmed comment when the stored value changes, raises the comment revision and
   * records the client the write came through, none for a web write (REQ-ORDERS-078).
   *
   * @param order the locked order whose comment revision the caller has checked
   * @param comment the requested comment; blank clears it
   * @param actor who writes, and through which channel
   * @return whether the stored comment changed
   */
  static boolean replace(
      @NotNull JobOrder order, @Nullable String comment, @NotNull JobOrderActor actor) {
    String normalized = StringNormalization.trimToNull(comment);
    if (Objects.equals(order.getComment(), normalized)) {
      return false;
    }
    order.setComment(normalized);
    order.setCommentVersion(Math.incrementExact(order.getCommentVersion()));
    order.setCommentClientId(actor.clientId());
    return true;
  }
}
