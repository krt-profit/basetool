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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Inventory input. */
@Data
public class InventoryForm {
  @NotNull private UUID materialId;

  @NotNull private UUID locationId;

  @NotNull
  @Min(0)
  @Max(1000)
  private Integer quality;

  @NotNull
  @Min(0)
  private Double amount;

  private UUID jobOrderId;

  private UUID missionId;

  private UUID userId;

  private Boolean isGlobal;
  private Boolean personal = false;

  private String source;

  private Long version;

  /**
   * R5.d owner-picker output: the {@code OrgUnit} the new inventory row should land on. {@code
   * null} when the target user has at most one org-unit membership (in that case the fragment stays
   * hidden and the backend stamps the user's home Staffel via the legacy path).
   */
  private UUID owningOrgUnitId;

  /**
   * Per-action stock-merge opt-in (REQ-INV-026): the modal checkbox the user ticks to merge this
   * SCU book-in into a matching existing stack. Ignored for a {@code PIECE} material (which always
   * merges); defaults to {@code false} so an untouched SCU book-in stays append-only.
   */
  private Boolean mergeStock = false;
}
