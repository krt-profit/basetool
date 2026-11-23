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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@link JobOrderWriterRule} for one actor into the units that actor writes job orders
 * for; every command resolves it anew on each call (ADR-0405).
 */
@Component
@RequiredArgsConstructor
class JobOrderWriters {

  /** Source of the web's contextual Logistician scope with the honoured pin. */
  private final AccessGateService accessGateService;

  /** Source of the database rule's direct Logistician units. */
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * Resolves the units the actor writes for under the rule.
   *
   * @param rule the authority that decides
   * @param actor who acts; {@link JobOrderWriterRule#DIRECT_LOGISTICIAN} reads its member's units
   * @return the writer units under that rule
   */
  @NotNull
  Scope resolve(@NotNull JobOrderWriterRule rule, @NotNull JobOrderActor actor) {
    ScopePredicate units =
        switch (rule) {
          case CONTEXTUAL -> accessGateService.currentLogisticianScope();
          case DIRECT_LOGISTICIAN ->
              new ScopePredicate(false, null, directLogisticianUnits(actor.member()));
        };
    return new Scope(rule, units);
  }

  private @NotNull Set<UUID> directLogisticianUnits(@Nullable UUID member) {
    return member == null
        ? Set.of()
        : membershipQueryService.findDirectLogisticianOrgUnitIds(member);
  }

  /**
   * The units one actor writes job orders for under one rule.
   *
   * @param rule the rule the units were resolved under; it decides whether the responsible side
   *     counts
   * @param units every unit, the pinned unit, or the listed units
   */
  record Scope(@NotNull JobOrderWriterRule rule, @NotNull ScopePredicate units) {

    /**
     * Tells whether the actor writes for the unit as an order's requesting side.
     *
     * @param orgUnitId the unit
     * @return {@code true} iff the unit is a writer unit
     */
    boolean writesRequesting(@NotNull UUID orgUnitId) {
      return units.permits(orgUnitId);
    }

    /**
     * Tells whether the actor writes for the order as its requesting side.
     *
     * @param order the loaded order
     * @return {@code true} iff the order has a requesting unit the actor writes for
     */
    boolean writesRequesting(@NotNull JobOrder order) {
      UUID requesting = OrgUnit.idOf(order.getRequestingOrgUnit());
      return requesting != null && writesRequesting(requesting);
    }

    /**
     * Tells whether the actor writes for the unit as an order's responsible side.
     *
     * @param orgUnitId the unit
     * @return {@code true} iff the rule covers the responsible side and the unit is a writer unit
     */
    boolean writesResponsible(@NotNull UUID orgUnitId) {
      return rule.coversResponsibleSide() && units.permits(orgUnitId);
    }

    /**
     * Tells whether the actor writes for the order as its responsible side; an order without a
     * responsible unit counts only when the actor writes for every unit.
     *
     * @param order the loaded order
     * @return {@code true} iff the rule covers the responsible side and the actor writes it
     */
    boolean writesResponsible(@NotNull JobOrder order) {
      UUID responsible = OrgUnit.idOf(order.getResponsibleOrgUnit());
      return responsible == null
          ? rule.coversResponsibleSide() && everyUnit()
          : writesResponsible(responsible);
    }

    /**
     * Tells whether the actor may commission an order between the two units.
     *
     * @param responsibleOrgUnitId the proposed responsible unit
     * @param requestingOrgUnitId the proposed requesting unit
     * @return {@code true} iff the actor writes the requesting side, or the responsible side where
     *     the rule covers it
     */
    boolean permitsPair(@NotNull UUID responsibleOrgUnitId, @NotNull UUID requestingOrgUnitId) {
      return writesRequesting(requestingOrgUnitId) || writesResponsible(responsibleOrgUnitId);
    }

    /**
     * Tells whether the actor writes for either side of the order the rule covers, as the target
     * and information changes require.
     *
     * @param order the loaded order
     * @return {@code true} iff the actor writes the responsible or the requesting side
     */
    boolean writesEitherSide(@NotNull JobOrder order) {
      return writesResponsible(order) || writesRequesting(order);
    }

    /**
     * Tells whether the actor may move the order to the status (REQ-ORDERS-054): the responsible
     * writer sets every status, a requesting writer cancels and reopens a cancellation.
     *
     * @param order the loaded order in its current status
     * @param status the requested status
     * @return {@code true} iff a side the actor writes may set the status
     */
    boolean permitsStatus(@NotNull JobOrder order, @NotNull JobOrderStatus status) {
      if (writesResponsible(order)) {
        return true;
      }
      return switch (status) {
        case CANCELLED -> writesRequesting(order);
        case OPEN -> order.getStatus() == JobOrderStatus.CANCELLED && writesRequesting(order);
        case IN_PROGRESS, COMPLETED, REJECTED -> false;
      };
    }

    /**
     * Tells whether the actor writes for every unit, as an administrator without a pin does.
     *
     * @return {@code true} iff no unit list limits the scope
     */
    boolean everyUnit() {
      return units.adminAllScope();
    }

    /**
     * Lists the writer units when {@link #everyUnit()} does not hold.
     *
     * @return the pinned unit or the listed units; possibly empty
     */
    @NotNull
    Set<UUID> listedUnits() {
      Set<UUID> listed = new LinkedHashSet<>(units.memberOrgUnitIds());
      UUID pin = units.activeOrgUnitId();
      if (pin != null) {
        listed.add(pin);
      }
      return listed;
    }
  }
}
