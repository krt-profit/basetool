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

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

/**
 * Form-backing object for a single material row in the store dialog of a refinery order.
 *
 * <p>Amount validation follows the project-wide convention for amount input fields: decimal number,
 * &gt;= 0, max. 3 decimal places. The note is optional and is attached to the created {@code
 * InventoryItem} on storage.
 */
@Data
public class RefineryOrderStoreItemForm {

  @NotNull private UUID materialId;

  private String materialName;

  private String quantityType;

  @NotNull private UUID locationId;

  @NotNull
  @Min(0)
  @Max(1000)
  private Integer quality;

  @NotNull
  @DecimalMin(value = "0.0", inclusive = true)
  @Digits(integer = 15, fraction = 3)
  private Double amount;

  private Boolean amountFixed;

  private UUID userId;

  private UUID jobOrderId;

  @Size(max = 1000)
  private String note;

  /**
   * Picker output stamping the resulting inventory item's owning OrgUnit. Pre-filled with the
   * order's own owning OrgUnit and only meaningful when the receiving member ({@link #userId})
   * belongs to more than one OrgUnit; {@code null} lets the backend auto-stamp a single-membership
   * receiver. Mirrors {@code RefineryOrderStoreItemDto#owningOrgUnitId}.
   */
  private UUID owningOrgUnitId;

  /**
   * Marks the resulting inventory row as the receiver's private stock instead of shared squadron
   * stock (REQ-INV-035), mirroring {@code RefineryOrderStoreItemDto#personal}. Bound from the store
   * dialog's personal-entry checkbox, so an unticked box arrives as {@code false} via Spring's
   * hidden checkbox marker. A personal row carries no earmark: the backend rejects the combination
   * with {@link #jobOrderId} (HTTP 400) and applies no mission earmark.
   */
  private Boolean personal = false;
}
