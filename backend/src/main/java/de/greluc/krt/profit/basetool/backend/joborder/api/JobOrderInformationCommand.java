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
import org.jetbrains.annotations.Nullable;

/**
 * The web's aUEC information command for a client's write, with the caller's own writer rule, read
 * check and actor, writing the comment alone; the order and account locks, the comment version, the
 * notices and the live update are the web's, the order of the refusals and the audit type are the
 * exchange's (REQ-OXCH-005, REQ-OXCH-008, ADR-0408).
 */
public interface JobOrderInformationCommand {

  /**
   * Replaces an aUEC order's comment in one transaction, joining the caller's, and leaves the
   * contact and its version untouched. Under the order locks it requires, in this order, the read
   * check, an aUEC order and a writer of either side the rule covers, the observed comment version,
   * a valid responsibility and an order that is not cancelled, then an active account of the
   * actor's member. A change records {@code JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT} without the text
   * and marks the comment with the actor's client.
   *
   * @param orderId the aUEC order
   * @param comment the new comment, at most 1000 characters; trimmed, and blank clears it
   * @param commentVersion the comment version the caller last read
   * @param rule the authority that decides whether the actor writes the order
   * @param read the read check the order must pass
   * @param actor who changes the comment; the notice excludes its member
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown or fails the read check, alike
   * @throws org.springframework.security.access.AccessDeniedException when the order is no aUEC
   *     order, the rule refuses the actor or the actor's member is no active account
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the comment
   *     version is stale
   * @throws JobOrderClientWriteRefusedException when the responsibility is invalid or the order is
   *     cancelled
   */
  void changeComment(
      @NotNull UUID orderId,
      @Nullable String comment,
      long commentVersion,
      @NotNull JobOrderWriterRule rule,
      @NotNull JobOrderReadCheck read,
      @NotNull JobOrderActor actor);
}
