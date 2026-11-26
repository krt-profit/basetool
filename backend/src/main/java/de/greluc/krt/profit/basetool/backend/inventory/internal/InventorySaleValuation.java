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
import java.util.LinkedHashMap;
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
 * Values the materials of "Mein Lager" at the terminal price the member chose in the profile
 * (REQ-INV-072): per material the legitimate amount times the price, in whole aUEC, either for
 * every shown material group or for the entries the member marked.
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
    return groups.stream().map(group -> valuedGroup(group, quotes, basis)).toList();
  }

  /**
   * Totals the sale value of the marked entries at the member's price basis, rounding each material
   * to whole aUEC like its group, so marking every entry of the shown groups gives the groups'
   * total.
   *
   * @param slices the marked entries' amounts per material and „gestohlen" marker
   * @param userId the member whose price basis applies
   * @return the total with the basis, the unpriced materials and whether stolen stock was left out
   */
  @NotNull
  public InventorySelectionSaleValueDto valuedSelection(
      @NotNull List<InventorySaleSlice> slices, @NotNull UUID userId) {
    Map<UUID, MaterialQuanta> byMaterial = new LinkedHashMap<>();
    for (InventorySaleSlice slice : slices) {
      byMaterial.merge(
          slice.materialId(),
          MaterialQuanta.of(slice.amount(), slice.stolen()),
          MaterialQuanta::plus);
    }
    Set<UUID> valuable =
        slices.stream()
            .filter(slice -> slice.quantityType() != QuantityType.PIECE)
            .map(InventorySaleSlice::materialId)
            .collect(Collectors.toUnmodifiableSet());
    Map<UUID, MaterialSaleQuote> quotes = materialSalePrices.quotes(valuable);
    SalePriceBasis basis = memberSaleValueSettings.basisFor(userId);
    BigDecimal total = BigDecimal.ZERO;
    int unpriced = 0;
    boolean stolenExcluded = false;
    for (Map.Entry<UUID, MaterialQuanta> material : byMaterial.entrySet()) {
      MaterialSaleQuote quote =
          valuable.contains(material.getKey()) ? quotes.get(material.getKey()) : null;
      if (quote == null) {
        unpriced++;
        continue;
      }
      InventorySaleValueDto value = saleValue(quote, basis, material.getValue());
      total = total.add(value.value());
      stolenExcluded |= value.unvaluedStolenAmount() > 0;
    }
    return new InventorySelectionSaleValueDto(basis, total, unpriced, stolenExcluded);
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
  private static GroupedInventoryDto valuedGroup(
      @NotNull GroupedInventoryDto group,
      @NotNull Map<UUID, MaterialSaleQuote> quotes,
      @NotNull SalePriceBasis basis) {
    UUID materialId = valuableMaterialId(group);
    MaterialSaleQuote quote = materialId == null ? null : quotes.get(materialId);
    if (quote == null) {
      return group;
    }
    MaterialQuanta quanta = MaterialQuanta.NONE;
    for (InventoryStackDto stack : group.stacks()) {
      quanta = quanta.plus(MaterialQuanta.of(stack.totalAmount(), stack.stolen()));
    }
    return group.withSaleValue(saleValue(quote, basis, quanta));
  }

  /**
   * Values one material's amount at the member's price basis.
   *
   * @param quote the material's sale prices
   * @param basis the member's price basis
   * @param quanta the material's amount, all of it and the part marked „gestohlen"
   * @return the price of the basis and the legitimate amount times it, in whole aUEC
   */
  @NotNull
  private static InventorySaleValueDto saleValue(
      @NotNull MaterialSaleQuote quote,
      @NotNull SalePriceBasis basis,
      @NotNull MaterialQuanta quanta) {
    TerminalSalePrice terminal =
        switch (basis) {
          case AVERAGE -> null;
          case HIGHEST -> quote.highest();
          case LOWEST -> quote.lowest();
        };
    BigDecimal price = terminal == null ? quote.average() : terminal.pricePerScu();
    BigDecimal valuedScu =
        BigDecimal.valueOf(ScuAmounts.fromQuanta(quanta.total() - quanta.stolen()));
    return new InventorySaleValueDto(
        basis,
        price,
        terminal == null ? null : terminal.terminalName(),
        quote.terminals(),
        valuedScu.multiply(price).setScale(WHOLE_AUEC, RoundingMode.HALF_UP),
        ScuAmounts.fromQuanta(quanta.stolen()));
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

  /**
   * One material's amount in exact SCU quanta ({@link ScuAmounts#toQuanta}).
   *
   * @param total every counted quantum
   * @param stolen the quanta marked „gestohlen", part of {@code total}
   */
  private record MaterialQuanta(long total, long stolen) {

    /** No amount at all. */
    private static final MaterialQuanta NONE = new MaterialQuanta(0, 0);

    /**
     * Converts one summed amount.
     *
     * @param amount the amount, {@code null} counting as none
     * @param stolen whether the amount is marked „gestohlen", {@code null} counting as not
     * @return the amount's quanta
     */
    @NotNull
    private static MaterialQuanta of(@Nullable Double amount, @Nullable Boolean stolen) {
      long quanta = ScuAmounts.toQuanta(amount == null ? 0.0 : amount);
      return new MaterialQuanta(quanta, Boolean.TRUE.equals(stolen) ? quanta : 0);
    }

    /**
     * Adds another amount of the same material.
     *
     * @param other the other amount
     * @return the sum of both
     */
    @NotNull
    private MaterialQuanta plus(@NotNull MaterialQuanta other) {
      return new MaterialQuanta(total + other.total, stolen + other.stolen);
    }
  }
}
