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

package de.greluc.krt.profit.basetool.frontend.model.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Refinery Order input. */
@Data
public class RefineryOrderForm {
  private String startedAt;

  @NotNull
  @Min(0)
  private Integer durationHours = 0;

  @NotNull
  @Min(0)
  @Max(59)
  private Integer durationMinutes = 0;

  @Min(0)
  private Double expenses = 0d;

  /**
   * Other costs in addition to expenses. Number >= 0, default 0. Optional - empty/0 is stored as
   * null.
   */
  @Min(0)
  private Double otherExpenses = 0d;

  /**
   * Revenue from selling raw ores (Ore Sales). Integer >= 0, default 0. Optional - empty/0 is
   * stored as null.
   */
  @Min(0)
  private Double oreSales = 0d;

  private UUID ownerId;
  private UUID refiningMethodId;
  private UUID locationId;
  private UUID missionId;
  private de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderStatus status;
  private Long version;
  private String source;

  /**
   * R5.d owner-picker output: the {@code OrgUnit} the new refinery order should be stamped on.
   * {@code null} when the chosen owner has at most one org-unit membership (fragment is hidden in
   * that case and the backend falls back to the owner's home Staffel).
   */
  private UUID owningOrgUnitId;

  private List<@Valid RefineryGoodForm> goods = new ArrayList<>();

  /** Seeds the form with one empty good row so the "add good" UI starts non-empty. */
  public RefineryOrderForm() {
    goods.add(new RefineryGoodForm());
  }
}
