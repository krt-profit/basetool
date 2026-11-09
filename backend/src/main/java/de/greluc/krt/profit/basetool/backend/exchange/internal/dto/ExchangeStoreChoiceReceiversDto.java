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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The members a refinery pickup's store lines may book onto, as {@code
 * store-choice-receivers.schema.json} describes it (REQ-XCH-054).
 *
 * @param items the receivers, the member first, then ordered by name
 */
public record ExchangeStoreChoiceReceiversDto(@NotNull @Unmodifiable List<Receiver> items) {

  /**
   * One receiver.
   *
   * @param ref the member's pseudonym at the calling client, as a store line names its receiver
   * @param name the name the web shows
   * @param self whether this is the member the request acts for
   * @param orgUnits the org units the receiver directly belongs to, in the picker's order
   */
  @Schema(name = "StoreChoiceReceiver")
  public record Receiver(
      @NotNull String ref,
      @NotNull String name,
      boolean self,
      @NotNull @Unmodifiable List<ExchangeOrgUnitDto> orgUnits) {}
}
