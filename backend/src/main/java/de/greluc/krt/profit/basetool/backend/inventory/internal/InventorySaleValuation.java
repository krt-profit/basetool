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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSalePrices;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote.TerminalSalePrice;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Values the material groups of "Mein Lager" at the terminal price the member chose in the profile
 * (REQ-INV-072): the legitimate amount times the price, in whole aUEC.
 *
 * <p>Stock marked „gestohlen" is left out, since no regular terminal buys it; a {@code PIECE}
 * material and a material no terminal buys stay unvalued, since UEX prices are per SCU.
 */
@Service
@RequiredArgsConstructor
public class InventorySaleValuation {

  /** Scale of a sale value: whole aUEC. */
  private static final int WHOLE_AUEC = 0;

  private final MaterialSalePrices materialSalePrices;
  private final MemberSaleValueSettings memberSaleValueSettings;

  /**
   * Attaches the sale value to every material group that has one, at the member's price basis and
   * with one price lookup for all groups.
   *
   * @param groups the member's grouped listing
   * @param userId the member whose price basis applies
   * @return the groups in the same order, each priced SCU material group carrying its value
   */
  @NotNull
  public List<GroupedInventoryDto> valued(
      @NotNull List<GroupedInventoryDto> groups, @NotNull UUID userId) {
    Set<UUID> materialIds =
        groups.stream()
            .map(InventorySaleValuation::valuableMaterialId)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    Map<UUID, MaterialSaleQuote> quotes = materialSalePrices.quotes(materialIds);
    if (quotes.isEmpty()) {
      return groups;
    }
    SalePriceBasis basis = memberSaleValueSettings.basisFor(userId);
    return groups.stream().map(group -> valued(group, quotes, basis)).toList();
  }

  /**
   * Values one group when its material is priced.
   *
   * @param group the group
   * @param quotes the sale prices per material id
   * @param basis the member's price basis
   * @return the group with its sale value, or unchanged when it has none
   */
  @NotNull
  private static GroupedInventoryDto valued(
      @NotNull GroupedInventoryDto group,
      @NotNull Map<UUID, MaterialSaleQuote> quotes,
      @NotNull SalePriceBasis basis) {
    UUID materialId = valuableMaterialId(group);
    MaterialSaleQuote quote = materialId == null ? null : quotes.get(materialId);
    if (quote == null) {
      return group;
    }
    long totalQuanta = 0;
    long stolenQuanta = 0;
    for (InventoryStackDto stack : group.stacks()) {
      long quanta = ScuAmounts.toQuanta(stack.totalAmount() == null ? 0.0 : stack.totalAmount());
      totalQuanta += quanta;
      if (Boolean.TRUE.equals(stack.stolen())) {
        stolenQuanta += quanta;
      }
    }
    TerminalSalePrice terminal =
        switch (basis) {
          case AVERAGE -> null;
          case HIGHEST -> quote.highest();
          case LOWEST -> quote.lowest();
        };
    BigDecimal price = terminal == null ? quote.average() : terminal.pricePerScu();
    BigDecimal valuedScu = BigDecimal.valueOf(ScuAmounts.fromQuanta(totalQuanta - stolenQuanta));
    return group.withSaleValue(
        new InventorySaleValueDto(
            basis,
            price,
            terminal == null ? null : terminal.terminalName(),
            quote.terminals(),
            valuedScu.multiply(price).setScale(WHOLE_AUEC, RoundingMode.HALF_UP),
            ScuAmounts.fromQuanta(stolenQuanta)));
  }

  /**
   * The material of a group that can carry a sale value: an SCU material.
   *
   * @param group the group
   * @return the material id, or {@code null} for a game-item group or a {@code PIECE} material
   */
  @Nullable
  private static UUID valuableMaterialId(@NotNull GroupedInventoryDto group) {
    MaterialReferenceDto material = group.material();
    if (material == null || material.quantityType() == QuantityType.PIECE) {
      return null;
    }
    return material.id();
  }
}
