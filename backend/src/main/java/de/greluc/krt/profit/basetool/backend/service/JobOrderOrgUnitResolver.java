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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the responsible (processing) and requesting (customer) org units of a job order from the
 * create/update picker output, applying the profit-eligibility rule. Extracted from {@code
 * JobOrderService} (L2, #921) so the org-unit resolution concern lives on its own, behind the same
 * rules verbatim.
 *
 * <p>There is no guest branch any more. Creating an order requires a login (ADR-0149), so the
 * forgiving fallback that routed an anonymous request to the configured intake Spezialkommando —
 * and the setting behind it — went with the public request form.
 *
 * <p>Read-only: every method only looks up {@link OrgUnit}s and applies validation; it holds no
 * state and never mutates an entity, so it runs inside the caller's transaction with no annotation
 * of its own.
 */
@Service
@RequiredArgsConstructor
public class JobOrderOrgUnitResolver {

  /** Resolves picker output ids to managed {@link OrgUnit} entities. */
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves the responsible (processing) org unit for a freshly-created job order.
   *
   * <p>The id is required and must resolve to a profit-eligible Staffel or Spezialkommando — only
   * Profit-side units process orders. Before ADR-0149 this method had a second, forgiving path for
   * anonymous callers; creating an order now requires a login, so one rule covers everyone.
   *
   * @param responsibleOrgUnitId picker output from the create DTO.
   * @return the resolved, profit-eligible responsible org unit; never {@code null}.
   * @throws BadRequestException when the id is missing, does not resolve, or names a unit that is
   *     not profit-eligible.
   */
  @NotNull
  public OrgUnit resolveResponsibleOrgUnit(@Nullable UUID responsibleOrgUnitId) {
    if (responsibleOrgUnitId == null) {
      throw new BadRequestException("responsibleOrgUnitId is required.");
    }
    OrgUnit orgUnit =
        orgUnitRepository
            .findById(responsibleOrgUnitId)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "responsibleOrgUnitId does not resolve to a known org unit: "
                            + responsibleOrgUnitId));
    if (!orgUnit.isProfitEligible()) {
      throw new BadRequestException(
          "The selected responsible org unit is not profit-eligible and cannot process orders: "
              + responsibleOrgUnitId);
    }
    return orgUnit;
  }

  /**
   * Resolves the requesting (customer / Auftraggeber) org unit from the picker output. Any org unit
   * is accepted — Staffel, Spezialkommando, Bereich or Organisationsleitung (epic #692): there is
   * no profit-eligibility or kind restriction on who may be named the customer (unlike the
   * responsible unit, which must be a profit-eligible Staffel/SK). Mandatory: the create/update
   * DTOs always carry it.
   *
   * @param requestingOrgUnitId picker output from the DTO.
   * @return the resolved requesting org unit; never {@code null}.
   * @throws BadRequestException when the id is missing or does not resolve to a known org unit.
   */
  @NotNull
  public OrgUnit resolveRequestingOrgUnit(@Nullable UUID requestingOrgUnitId) {
    if (requestingOrgUnitId == null) {
      throw new BadRequestException("requestingOrgUnitId is required.");
    }
    return orgUnitRepository
        .findById(requestingOrgUnitId)
        .orElseThrow(
            () ->
                new BadRequestException(
                    "requestingOrgUnitId does not resolve to a known org unit: "
                        + requestingOrgUnitId));
  }
}
