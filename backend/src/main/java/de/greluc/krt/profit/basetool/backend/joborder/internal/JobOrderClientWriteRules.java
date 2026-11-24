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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCaller;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientWriteRefusedException.Refusal;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The rules every client write on an existing job order shares, decided on the loaded order in one
 * fixed order (REQ-OXCH-005): visibility, writer, the edited section's version, responsibility,
 * status. The write's own rule follows them. The edit module runs the checks before and again under
 * the order locks, so the decision under the locks is the one that counts.
 */
@Component
@RequiredArgsConstructor
class JobOrderClientWriteRules {

  private final JobOrderAccessPolicy accessPolicy;
  private final JobOrderWriters writers;
  private final JobOrderResponsibilityGuard responsibilityGuard;

  /**
   * Builds the shared checks of one client write on the loaded order.
   *
   * <p>A hidden order is refused like an unknown one with {@link NotFoundException}; an order of
   * another kind than aUEC or one the actor does not write as the write needs with {@link
   * AccessDeniedException}; a stale section version with {@link
   * org.springframework.orm.ObjectOptimisticLockingFailureException}; an invalid responsibility and
   * a status the write does not admit with {@link JobOrderClientWriteRefusedException}.
   *
   * @param caller the writer rule, read check and actor of the write
   * @param write the write's writer decision, edited section and admitted statuses
   * @return the checks of the loaded order
   */
  @NotNull
  Consumer<JobOrder> checks(@NotNull JobOrderCaller caller, @NotNull Write write) {
    return order -> {
      if (!accessPolicy.canRead(caller.read(), order)) {
        throw new NotFoundException("JobOrder not found: " + order.getId());
      }
      if (order.getType() != JobOrderType.AUEC
          || !write.writes().test(writers.resolve(caller.rule(), caller.actor()), order)) {
        throw new AccessDeniedException("Caller may not edit job order " + order.getId());
      }
      OptimisticLock.checkRequired(
          write.section().apply(order), write.version(), JobOrder.class, order.getId());
      if (!responsibilityGuard.isValid(order)) {
        throw new JobOrderClientWriteRefusedException(Refusal.RESPONSIBILITY_INVALID);
      }
      if (!write.admits().test(order.getStatus())) {
        throw new JobOrderClientWriteRefusedException(Refusal.CLOSED);
      }
    };
  }

  /**
   * One client write on an existing order as the shared rules see it.
   *
   * @param writes whether the actor's writer units let it make the write on the loaded order
   * @param section the current version of the section the write edits
   * @param version the section version the client echoed
   * @param admits whether the order's status admits the write
   */
  record Write(
      @NotNull BiPredicate<JobOrderWriters.Scope, JobOrder> writes,
      @NotNull Function<JobOrder, Long> section,
      long version,
      @NotNull Predicate<JobOrderStatus> admits) {}
}
