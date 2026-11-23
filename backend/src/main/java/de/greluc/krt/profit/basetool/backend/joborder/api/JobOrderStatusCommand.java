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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The web's job-order status command for a caller that passes its own writer rule, read check and
 * actor; the order locks, the lifecycle version, the responsibility gate, the audit, the notices
 * and the live update are the web's (ADR-0405).
 */
public interface JobOrderStatusCommand {

  /**
   * Cancels an active order in one transaction, joining the caller's. Under the order locks it
   * requires a valid responsibility, then the read check and a writer of either side the rule
   * covers, then the observed lifecycle version; a cancellation ends the order's open unit
   * commitments and earmarks as in the web.
   *
   * @param orderId the order
   * @param lifecycleVersion the lifecycle version the caller last read
   * @param rule the authority that decides whether the actor writes the order
   * @param read the read check the order must pass
   * @param actor who cancels; the notices exclude its member
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the order's
   *     responsibility is invalid or the order is not active
   * @throws org.springframework.security.access.AccessDeniedException when the read check or the
   *     rule refuses the actor
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the lifecycle
   *     version is stale
   */
  void cancel(
      @NotNull UUID orderId,
      long lifecycleVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor);
}
