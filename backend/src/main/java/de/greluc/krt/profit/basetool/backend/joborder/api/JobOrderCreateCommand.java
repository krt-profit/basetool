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
import org.jetbrains.annotations.Nullable;

/**
 * The web's aUEC create command, for a caller that passes its own writer rule and actor; the order
 * locks, unit checks, audit, notices and live update are the web's (ADR-0405).
 */
public interface JobOrderCreateCommand {

  /**
   * Creates an aUEC order and its target in one transaction, joining the caller's. Under the order
   * locks it resolves both units, requires the responsible one to be an eligible Profit unit, and
   * rechecks the pair with the given rule; nothing is filled in.
   *
   * @param order the pair, contact, comment and target
   * @param rule the authority that decides whether the actor writes the pair
   * @param actor who creates; the created notice excludes its member
   * @return the new order's id
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when a unit is unknown
   *     or the responsible unit is not an eligible Profit unit
   * @throws org.springframework.security.access.AccessDeniedException when the rule does not let
   *     the actor write the pair
   */
  @NotNull
  UUID createAuec(
      @NotNull AuecOrder order, @NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor);

  /**
   * The terms of a new aUEC order.
   *
   * @param responsibleOrgUnitId the eligible Profit unit that processes the order
   * @param requestingOrgUnitId the unit that commissions it
   * @param handle the contact, at most 200 characters
   * @param comment the optional comment, at most 1000 characters
   * @param target the positive whole aUEC amount requested
   */
  record AuecOrder(
      @NotNull UUID responsibleOrgUnitId,
      @NotNull UUID requestingOrgUnitId,
      @NotNull String handle,
      @Nullable String comment,
      @NotNull BigDecimal target) {}
}
