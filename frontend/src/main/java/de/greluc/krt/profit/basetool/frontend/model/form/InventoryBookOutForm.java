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

import de.greluc.krt.profit.basetool.frontend.model.dto.CheckoutType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

/** Form-binding object for Inventory Book Out input. */
@Data
public class InventoryBookOutForm {

  @NotNull
  @Min(0)
  private Double amount;

  private Double targetAmount;

  private Double maxAmount;

  private UUID targetUserId;

  private UUID targetLocationId;

  private CheckoutType type;

  private String terminal;

  @Min(0)
  private BigDecimal sellAmount;

  private Boolean isGlobal;

  private Long version;

  /**
   * R5.d.g owner-picker output: the {@code OrgUnit} the destination's new inventory row should be
   * stamped on. Only honoured for {@link CheckoutType#TRANSFER}. {@code null} preserves the legacy
   * "stamp target user's home Staffel" behaviour on the backend (covers the single-membership case
   * 100 % of users hit today).
   */
  private UUID targetOwningOrgUnitId;

  /**
   * Per-action stock-merge opt-in (REQ-INV-026) for a {@link CheckoutType#TRANSFER}: the modal
   * checkbox to merge the moved SCU quantity into a matching target stack. Ignored for a {@code
   * PIECE} material (which always merges) and for {@code DISCARD}/{@code SELL}; {@code null}/{@code
   * false} keeps the moved row separate.
   */
  private Boolean mergeStock;
}
