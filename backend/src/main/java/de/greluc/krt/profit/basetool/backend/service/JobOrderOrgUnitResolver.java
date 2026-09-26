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
 * picker output, applying the profit-eligibility rule. Stateless and read-only.
 */
@Service
@RequiredArgsConstructor
public class JobOrderOrgUnitResolver {

  /** Resolves picker output ids to managed {@link OrgUnit} entities. */
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves the responsible (processing) org unit of a new job order, which must be a
   * profit-eligible Staffel or Spezialkommando.
   *
   * @param responsibleOrgUnitId picker output from the create DTO.
   * @return the resolved responsible org unit; never {@code null}.
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
   * Resolves the requesting (customer) org unit from the picker output; any org-unit kind is
   * accepted.
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
