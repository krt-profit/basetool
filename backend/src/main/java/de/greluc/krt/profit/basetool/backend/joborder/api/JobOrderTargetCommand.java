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

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The web's aUEC target command for a caller that passes its own writer rule, read check and actor;
 * the order and account locks, the target version, the responsibility gate, the target history, the
 * audit, the notices and the live update are the web's (ADR-0405).
 */
public interface JobOrderTargetCommand {

  /**
   * Replaces an aUEC order's target in one transaction, joining the caller's. Under the order locks
   * it requires the read check, a valid responsibility, an active member, a writer of either side
   * the rule covers and an order that is not cancelled, then the observed target version; a target
   * at or below the net handover completes the order and a raised one reopens a completed order, as
   * in the web.
   *
   * @param orderId the aUEC order
   * @param target the new positive whole aUEC amount
   * @param targetVersion the target version the caller last read
   * @param rule the authority that decides whether the actor writes the order
   * @param read the read check the order must pass
   * @param actor who changes the target; the history entry names its member, and the notice
   *     excludes it
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown or not an aUEC order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the target is not
   *     a positive whole amount or the order's responsibility is invalid
   * @throws org.springframework.security.access.AccessDeniedException when the read check or the
   *     rule refuses the actor, or the actor's member is no active account
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException when the order
   *     is cancelled or its completion basis changed while waiting
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the target version
   *     is stale
   */
  void changeTarget(
      @NotNull UUID orderId,
      @NotNull BigDecimal target,
      long targetVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor);
}
