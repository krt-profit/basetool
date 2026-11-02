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

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reduces the cross-order material-demand overview to the open buckets in brief (REQ-ORDERS-034,
 * REQ-UI-034), so a caller that shows a few rows does not carry the whole overview.
 */
@Service
@RequiredArgsConstructor
public class MaterialDemandSummaryService {

  private final JobOrderMaterialDemandService overviewService;

  /**
   * Summarises the caller's open material demand.
   *
   * <p>The overview is already scoped to the caller's visible orders; {@code orgUnitId} only
   * narrows it to one responsible unit.
   *
   * @param orgUnitId the responsible org unit to keep, {@code null} for every unit in scope
   * @param limit the most rows to return
   * @return the number of open buckets and the first {@code limit} of them
   */
  @NotNull
  @Transactional(readOnly = true)
  public MaterialDemandSummaryDto summary(@Nullable UUID orgUnitId, int limit) {
    List<MaterialDemandSummaryRowDto> open =
        overviewService.getMaterialDemandOverview().groups().stream()
            .filter(group ->
                orgUnitId == null
                    || (group.orgUnit() != null && orgUnitId.equals(group.orgUnit().id())))
            .flatMap(group ->
                group.materials().stream()
                    .filter(row -> row.outstandingAmount() > 0)
                    .map(row ->
                        new MaterialDemandSummaryRowDto(
                            row.material(),
                            row.qualityRequirement(),
                            row.qualityTier(),
                            row.outstandingAmount(),
                            group.orgUnit())))
            .toList();
    return new MaterialDemandSummaryDto(open.size(), open.stream().limit(limit).toList());
  }
}
