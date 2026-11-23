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
 * The web's aUEC target command for a client's write, with the caller's own writer rule, read check
 * and actor; the order and account locks, the target version, the target history, the audit, the
 * notices and the live update are the web's, the order of the refusals is the exchange's
 * (REQ-OXCH-005, REQ-OXCH-007, ADR-0408).
 */
public interface JobOrderTargetCommand {

  /**
   * Replaces an active aUEC order's target in either direction in one transaction, joining the
   * caller's. Under the order locks it requires, in this order, the read check, an aUEC order and a
   * writer of a side the rule covers, the observed target version, a valid responsibility, an
   * active order and a new target above the order's net handover, so the change never completes or
   * reopens the order.
   *
   * @param orderId the aUEC order
   * @param target the new positive whole aUEC amount
   * @param targetVersion the target version the caller last read
   * @param rule the authority that decides whether the actor writes the order
   * @param read the read check the order must pass
   * @param actor who changes the target; the history entry names its member and client, and the
   *     notice excludes the member
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown or fails the read check, alike
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the target is not
   *     a positive whole amount
   * @throws org.springframework.security.access.AccessDeniedException when the order is no aUEC
   *     order, the rule refuses the actor, or the actor's member is no active account
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the target version
   *     is stale
   * @throws JobOrderClientWriteRefusedException when the responsibility is invalid, the order is
   *     not active or the target does not lie above the net handover
   */
  void changeTarget(
      @NotNull UUID orderId,
      @NotNull BigDecimal target,
      long targetVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor);
}
