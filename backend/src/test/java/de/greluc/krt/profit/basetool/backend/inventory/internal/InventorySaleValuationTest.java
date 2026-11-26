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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSaleQuote.TerminalSalePrice;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryGameItemReferenceDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link InventorySaleValuation} (REQ-INV-072): the legitimate amount of an SCU
 * material group or of the marked entries at the member's price basis in whole aUEC, stolen stock
 * left out, and no value for a {@code PIECE} material, a game item or a material no terminal buys.
 */
class InventorySaleValuationTest {

  private static final UUID USER = UUID.randomUUID();
  private static final MaterialSaleQuote QUOTE =
      new MaterialSaleQuote(
          new TerminalSalePrice(new BigDecimal("3000"), "Alpha"),
          new TerminalSalePrice(new BigDecimal("2000"), "Bravo"),
          new BigDecimal("2500.50"),
          3);

  private final UUID agricium = UUID.randomUUID();
  private final List<Collection<UUID>> lookups = new ArrayList<>();
  private final MemberSaleValueSettings settings = mock(MemberSaleValueSettings.class);

  @ParameterizedTest
  @CsvSource({
    "AVERAGE, 2500.50,      , 25005",
    "HIGHEST, 3000,    Alpha, 30000",
    "LOWEST,  2000,    Bravo, 20000"
  })
  void valuesTheAmountAtTheMembersBasis(
      SalePriceBasis basis, String price, String terminal, String value) {
    when(settings.basisFor(USER)).thenReturn(basis);
    GroupedInventoryDto group = materialGroup(agricium, QuantityType.SCU, stack(10.0, false));

    InventorySaleValueDto saleValue =
        valuation(Map.of(agricium, QUOTE)).valued(List.of(group), USER).getFirst().saleValue();

    assertThat(saleValue)
        .isEqualTo(
            new InventorySaleValueDto(
                basis, new BigDecimal(price), terminal, 3, new BigDecimal(value), 0.0));
  }

  @Test
  void roundsTheValueHalfUpToWholeAuec() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.LOWEST);
    MaterialSaleQuote quote =
        new MaterialSaleQuote(
            new TerminalSalePrice(new BigDecimal("2"), "Alpha"),
            new TerminalSalePrice(new BigDecimal("2"), "Alpha"),
            new BigDecimal("2"),
            1);
    GroupedInventoryDto group = materialGroup(agricium, QuantityType.SCU, stack(1.25, false));

    InventorySaleValueDto saleValue =
        valuation(Map.of(agricium, quote)).valued(List.of(group), USER).getFirst().saleValue();

    assertThat(saleValue).isNotNull();
    assertThat(saleValue.value()).isEqualByComparingTo("3");
  }

  @Test
  void leavesStolenStacksOutOfTheValueAndReportsTheirAmount() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.HIGHEST);
    GroupedInventoryDto group =
        materialGroup(agricium, QuantityType.SCU, stack(80.0, false), stack(40.25, true));

    InventorySaleValueDto saleValue =
        valuation(Map.of(agricium, QUOTE)).valued(List.of(group), USER).getFirst().saleValue();

    assertThat(saleValue).isNotNull();
    assertThat(saleValue.value()).isEqualByComparingTo("240000");
    assertThat(saleValue.unvaluedStolenAmount()).isEqualTo(40.25);
  }

  @Test
  void looksUpOnlyScuMaterials_andLeavesPieceItemAndUnpricedGroupsUnvalued() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.AVERAGE);
    UUID hadanite = UUID.randomUUID();
    UUID unpriced = UUID.randomUUID();
    GroupedInventoryDto piece = materialGroup(hadanite, QuantityType.PIECE, stack(14.0, false));
    GroupedInventoryDto noBuyer = materialGroup(unpriced, QuantityType.SCU, stack(3.0, false));
    GroupedInventoryDto item =
        new GroupedInventoryDto(
            null,
            new InventoryGameItemReferenceDto(UUID.randomUUID(), "Drive", null, "GENERIC"),
            2.0,
            null,
            null,
            List.of(stack(2.0, false)));
    GroupedInventoryDto priced = materialGroup(agricium, QuantityType.SCU, stack(1.0, false));

    List<GroupedInventoryDto> valued =
        valuation(Map.of(agricium, QUOTE, hadanite, QUOTE))
            .valued(List.of(piece, noBuyer, item, priced), USER);

    assertThat(lookups).containsExactly(Set.of(unpriced, agricium));
    assertThat(valued)
        .extracting(GroupedInventoryDto::saleValue)
        .containsExactly(
            null,
            null,
            null,
            new InventorySaleValueDto(
                SalePriceBasis.AVERAGE,
                new BigDecimal("2500.50"),
                null,
                3,
                new BigDecimal("2501"),
                0.0));
  }

  @Test
  void withoutAnyPriceReturnsTheGroupsUnchangedAndReadsNoSetting() {
    List<GroupedInventoryDto> groups =
        List.of(materialGroup(agricium, QuantityType.SCU, stack(1.0, false)));

    assertThat(valuation(Map.of()).valued(groups, USER)).isSameAs(groups);
    verifyNoInteractions(settings);
  }

  @Test
  void totalsTheMarkedMaterials_roundingEachLikeItsGroup_andCountsTheUnpricedOnes() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.LOWEST);
    UUID laranite = UUID.randomUUID();
    UUID hadanite = UUID.randomUUID();
    UUID unpriced = UUID.randomUUID();
    MaterialSaleQuote twoPerScu =
        new MaterialSaleQuote(
            new TerminalSalePrice(new BigDecimal("2"), "Alpha"),
            new TerminalSalePrice(new BigDecimal("2"), "Alpha"),
            new BigDecimal("2"),
            1);

    InventorySelectionSaleValueDto value =
        valuation(Map.of(agricium, QUOTE, laranite, twoPerScu, hadanite, QUOTE))
            .valuedSelection(
                List.of(
                    slice(agricium, QuantityType.SCU, false, 10.0),
                    slice(agricium, QuantityType.SCU, true, 2.0),
                    slice(laranite, QuantityType.SCU, false, 1.25),
                    slice(hadanite, QuantityType.PIECE, false, 5.0),
                    slice(unpriced, QuantityType.SCU, false, 3.0)),
                USER);

    assertThat(lookups).containsExactly(Set.of(agricium, laranite, unpriced));
    assertThat(value)
        .isEqualTo(
            new InventorySelectionSaleValueDto(
                SalePriceBasis.LOWEST, new BigDecimal("20003"), 2, true));
  }

  @Test
  void markingEveryEntryOfAGroupGivesTheGroupsValue() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.HIGHEST);
    InventorySaleValuation valuation = valuation(Map.of(agricium, QUOTE));
    GroupedInventoryDto group =
        materialGroup(agricium, QuantityType.SCU, stack(80.0, false), stack(40.25, true));

    InventorySaleValueDto groupValue =
        valuation.valued(List.of(group), USER).getFirst().saleValue();
    InventorySelectionSaleValueDto selectionValue =
        valuation.valuedSelection(
            List.of(
                slice(agricium, QuantityType.SCU, false, 80.0),
                slice(agricium, QuantityType.SCU, true, 40.25)),
            USER);

    assertThat(groupValue).isNotNull();
    assertThat(selectionValue.total()).isEqualByComparingTo(groupValue.value());
    assertThat(selectionValue.stolenExcluded()).isTrue();
  }

  @Test
  void aSelectionWithoutPricedStockTotalsZeroAtTheMembersBasis() {
    when(settings.basisFor(USER)).thenReturn(SalePriceBasis.AVERAGE);

    InventorySelectionSaleValueDto value =
        valuation(Map.of(agricium, QUOTE))
            .valuedSelection(List.of(slice(agricium, QuantityType.SCU, true, 4.0)), USER);

    assertThat(value.basis()).isEqualTo(SalePriceBasis.AVERAGE);
    assertThat(value.total()).isEqualByComparingTo("0");
    assertThat(value.unpricedMaterials()).isZero();
    assertThat(value.stolenExcluded()).isTrue();
  }

  private static InventorySaleSlice slice(
      UUID materialId, QuantityType quantityType, boolean stolen, double amount) {
    return new InventorySaleSlice(materialId, quantityType, stolen, amount);
  }

  private InventorySaleValuation valuation(Map<UUID, MaterialSaleQuote> quotes) {
    return new InventorySaleValuation(
        materialIds -> {
          lookups.add(materialIds);
          return quotes;
        },
        settings);
  }

  private static GroupedInventoryDto materialGroup(
      UUID materialId, QuantityType quantityType, InventoryStackDto... stacks) {
    double total = 0;
    for (InventoryStackDto stack : stacks) {
      total += stack.totalAmount();
    }
    return new GroupedInventoryDto(
        new MaterialReferenceDto(materialId, "Material", quantityType),
        null,
        total,
        500.0,
        500,
        List.of(stacks));
  }

  private static InventoryStackDto stack(double amount, boolean stolen) {
    return new InventoryStackDto(
        null, null, 500, false, stolen, null, amount, 500.0, 500, 1, false);
  }
}
