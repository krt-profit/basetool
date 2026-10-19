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

package de.greluc.krt.profit.basetool.frontend.refinery.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/**
 * Form-binding object for Refinery Good input.
 *
 * <p>{@code outputQuantity} is required and at least 1. {@code inputQuantity} is optional; the
 * controller sends an empty one as 0, which marks it as not entered (REQ-REFINERY-025). An empty
 * {@code quality} is sent as 0.
 */
@Data
public class RefineryGoodForm {
  private UUID inputMaterialId;

  @Min(0)
  private Integer inputQuantity;

  private UUID outputMaterialId;

  @NotNull
  @Min(1)
  private Integer outputQuantity;

  @Min(0)
  @Max(1000)
  private Integer quality = 0;
}
