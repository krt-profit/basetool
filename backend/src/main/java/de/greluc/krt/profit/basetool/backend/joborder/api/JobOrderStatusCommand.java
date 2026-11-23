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
 * The web's job-order status command for a client's write, with the caller's own writer rule, read
 * check and actor; the order locks, the lifecycle version, the audit, the notices and the live
 * update are the web's, the order of the refusals is the exchange's (REQ-OXCH-005, ADR-0408).
 */
public interface JobOrderStatusCommand {

  /**
   * Cancels an active aUEC order in one transaction, joining the caller's. Under the order locks it
   * requires, in this order, the read check, an aUEC order and a writer of a side the rule lets
   * cancel, the observed lifecycle version, a valid responsibility, an active order and no money
   * operation that reached its accepted, durable submission boundary (REQ-ORDERS-063); a
   * cancellation ends the order's open unit commitments and earmarks as in the web.
   *
   * @param orderId the order
   * @param lifecycleVersion the lifecycle version the caller last read
   * @param rule the authority that decides whether the actor writes the order
   * @param read the read check the order must pass
   * @param actor who cancels; the notices exclude its member
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown or fails the read check, alike
   * @throws org.springframework.security.access.AccessDeniedException when the order is no aUEC
   *     order or the rule refuses the actor
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the lifecycle
   *     version is stale
   * @throws JobOrderClientWriteRefusedException when the responsibility is invalid, the order is
   *     not active or its money started
   */
  void cancel(
      @NotNull UUID orderId,
      long lifecycleVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor);
}
