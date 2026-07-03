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

/**
 * Form-binding object for Refinery Good input.
 *
 * <p>{@code outputQuantity} is required and must be at least 1 — the user explicitly enters the
 * expected yield per material and the value drives the SCU display + later refinery payout. {@code
 * quality} defaults to 0; if the user leaves the field empty the controller normalises the bound
 * {@code null} back to 0 before sending to the backend (see {@code RefineryOrderWriteController}).
 */
@Data
public class RefineryGoodForm {
  private UUID inputMaterialId;

  @NotNull
  @Min(1)
  private Integer inputQuantity;

  private UUID outputMaterialId;

  @NotNull
  @Min(1)
  private Integer outputQuantity;

  @Min(0)
  @Max(1000)
  private Integer quality = 0;
}
