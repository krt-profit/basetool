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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockDecisions.BookOutRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockDecisions.LotRebook;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockDecisions.PersonalRebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockDecisions.SplitRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryStockDecisions.Take;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InventoryStockDecisions} on plain rows: every refusal of every operation,
 * and the takes of a lot rebooking (REQ-INV-065).
 */
class InventoryStockDecisionsTest {

  /**
   * Builds a row of an SCU material.
   *
   * @param amount the row's amount
   * @param personal the personal marker
   * @return the row
   */
  private static InventoryItem scuRow(double amount, boolean personal) {
    return row(material(QuantityType.SCU), null, amount, personal);
  }

  /**
   * Builds a row of a game item.
   *
   * @param amount the row's amount
   * @return the shared row
   */
  private static InventoryItem itemRow(double amount) {
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    return row(null, gameItem, amount, false);
  }

  /**
   * Builds a row of a piece-counted material.
   *
   * @param amount the row's amount
   * @return the shared row
   */
  private static InventoryItem pieceRow(double amount) {
    return row(material(QuantityType.PIECE), null, amount, false);
  }

  private static Material material(QuantityType type) {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setQuantityType(type);
    return material;
  }

  private static InventoryItem row(
      Material material, GameItem gameItem, double amount, boolean personal) {
    InventoryItem row = new InventoryItem();
    row.setId(UUID.randomUUID());
    row.setMaterial(material);
    row.setGameItem(gameItem);
    row.setAmount(amount);
    row.setPersonal(personal);
    return row;
  }

  @Nested
  class WholeUnits {

    @Test
    void itemsAndPieceMaterialsCountWholeUnits_scuMaterialsDoNot() {
      assertThat(itemRow(1).countsWholeUnits()).isTrue();
      assertThat(pieceRow(1).countsWholeUnits()).isTrue();
      assertThat(scuRow(1, false).countsWholeUnits()).isFalse();
      assertThat(InventoryItem.countsWholeUnits(null, null)).isFalse();
    }

    @Test
    void theFractionalDetailNamesTheKindOfStock() {
      assertThat(itemRow(1).wholeUnitAmountDetail())
          .isEqualTo("Amount must be a whole number for item stock");
      assertThat(pieceRow(1).wholeUnitAmountDetail())
          .isEqualTo("Amount must be a whole number for PIECE materials");
    }
  }

  @Nested
  class BookOut {

    @Test
    void anAmountWithinTheRowIsAllowed() {
      assertThat(InventoryStockDecisions.bookOut(scuRow(10, false), 2.5, true)).isNull();
    }

    @Test
    void moreThanTheRowHoldsIsRefused() {
      assertThat(InventoryStockDecisions.bookOut(scuRow(10, false), 10.001, false))
          .isEqualTo(BookOutRefusal.EXCEEDS_STOCK);
    }

    @Test
    void aFractionalPartOfWholeUnitStockIsRefused() {
      assertThat(InventoryStockDecisions.bookOut(pieceRow(5), 1.5, false))
          .isEqualTo(BookOutRefusal.FRACTIONAL_AMOUNT);
    }

    @Test
    void aFractionalPartOfItemStockIsRefused() {
      assertThat(InventoryStockDecisions.bookOut(itemRow(10), 1.5, false))
          .isEqualTo(BookOutRefusal.FRACTIONAL_AMOUNT);
    }

    @Test
    void aFractionalWholeRowOfWholeUnitStockIsAllowed() {
      assertThat(InventoryStockDecisions.bookOut(pieceRow(2.5), 2.5, false)).isNull();
      assertThat(InventoryStockDecisions.bookOut(itemRow(1.5), 1.5, false)).isNull();
    }

    @Test
    void aFractionalPartOfAFractionalWholeUnitRowIsRefused() {
      assertThat(InventoryStockDecisions.bookOut(pieceRow(1.5), 0.5, false))
          .isEqualTo(BookOutRefusal.FRACTIONAL_AMOUNT);
      assertThat(InventoryStockDecisions.bookOut(itemRow(1.5), 0.5, false))
          .isEqualTo(BookOutRefusal.FRACTIONAL_AMOUNT);
    }

    @Test
    void missionDeductionsOnItemStockAreRefused() {
      assertThat(InventoryStockDecisions.bookOut(itemRow(3), 1, true))
          .isEqualTo(BookOutRefusal.MISSION_EARMARK_ON_ITEM_STOCK);
    }
  }

  @Nested
  class RebookPersonal {

    @Test
    void aMissingOrNonPositiveAmountIsRefused() {
      InventoryItem row = scuRow(4, false);
      assertThat(InventoryStockDecisions.rebookPersonal(row, null))
          .isEqualTo(PersonalRebookRefusal.AMOUNT_NOT_POSITIVE);
      assertThat(InventoryStockDecisions.rebookPersonal(row, 0.0))
          .isEqualTo(PersonalRebookRefusal.AMOUNT_NOT_POSITIVE);
      assertThat(InventoryStockDecisions.rebookPersonal(row, -5.0))
          .isEqualTo(PersonalRebookRefusal.AMOUNT_NOT_POSITIVE);
    }

    @Test
    void moreThanTheRowHoldsIsRefused() {
      assertThat(InventoryStockDecisions.rebookPersonal(scuRow(4, false), 5.0))
          .isEqualTo(PersonalRebookRefusal.EXCEEDS_STOCK);
    }

    @Test
    void aFractionalPartOfWholeUnitStockIsRefused() {
      assertThat(InventoryStockDecisions.rebookPersonal(itemRow(4), 0.5))
          .isEqualTo(PersonalRebookRefusal.FRACTIONAL_AMOUNT);
      assertThat(InventoryStockDecisions.rebookPersonal(itemRow(1.5), 0.5))
          .isEqualTo(PersonalRebookRefusal.FRACTIONAL_AMOUNT);
      assertThat(InventoryStockDecisions.rebookPersonal(pieceRow(1.5), 0.5))
          .isEqualTo(PersonalRebookRefusal.FRACTIONAL_AMOUNT);
    }

    @Test
    void aFractionalWholeRowOfWholeUnitStockMayMove() {
      assertThat(InventoryStockDecisions.rebookPersonal(itemRow(1.5), 1.5)).isNull();
      assertThat(InventoryStockDecisions.rebookPersonal(pieceRow(1.5), 1.5)).isNull();
    }

    @Test
    void anEarmarkedSharedRowCannotBecomePersonal() {
      InventoryItem jobOrder = scuRow(4, false);
      InventoryAllocations.addJobOrder(jobOrder, UUID.randomUUID(), 1, false);
      InventoryItem mission = scuRow(4, false);
      InventoryAllocations.addMission(mission, UUID.randomUUID(), 1);

      assertThat(InventoryStockDecisions.rebookPersonal(jobOrder, 1.0))
          .isEqualTo(PersonalRebookRefusal.EARMARKED);
      assertThat(InventoryStockDecisions.rebookPersonal(mission, 1.0))
          .isEqualTo(PersonalRebookRefusal.EARMARKED);
    }

    @Test
    void aDonationPledgeDoesNotKeepARowShared() {
      InventoryItem row = scuRow(4, false);
      InventoryAllocations.addDonation(row, UUID.randomUUID(), 1);

      assertThat(InventoryStockDecisions.rebookPersonal(row, 1.0)).isNull();
    }

    @Test
    void aPersonalRowMayBeSharedAnyway() {
      assertThat(InventoryStockDecisions.rebookPersonal(scuRow(4, true), 4.0)).isNull();
    }
  }

  @Nested
  class Split {

    @Test
    void aPartAboveTheOfferAndTheEarmarksIsAllowed() {
      InventoryItem row = scuRow(10, false);
      InventoryAllocations.addJobOrder(row, UUID.randomUUID(), 3, false);

      assertThat(InventoryStockDecisions.split(row, 4, 3)).isNull();
    }

    @Test
    void aNonPositivePartIsRefused() {
      assertThat(InventoryStockDecisions.split(scuRow(10, false), 0, 0))
          .isEqualTo(SplitRefusal.AMOUNT_NOT_POSITIVE);
    }

    @Test
    void moreThanTheRowHoldsIsRefused() {
      assertThat(InventoryStockDecisions.split(scuRow(10, false), 11, 0))
          .isEqualTo(SplitRefusal.EXCEEDS_STOCK);
    }

    @Test
    void aFractionalPartOfWholeUnitStockIsRefused() {
      assertThat(InventoryStockDecisions.split(pieceRow(4), 1.5, 0))
          .isEqualTo(SplitRefusal.FRACTIONAL_AMOUNT);
    }

    @Test
    void aRestBelowTheOfferIsRefused() {
      assertThat(InventoryStockDecisions.split(scuRow(10, false), 8, 3))
          .isEqualTo(SplitRefusal.BELOW_OFFER);
    }

    @Test
    void aRestBelowAnyEarmarkSumIsRefused() {
      InventoryItem jobOrder = scuRow(10, false);
      InventoryAllocations.addJobOrder(jobOrder, UUID.randomUUID(), 5, false);
      InventoryItem mission = scuRow(10, false);
      InventoryAllocations.addMission(mission, UUID.randomUUID(), 5);
      InventoryItem donation = scuRow(10, false);
      InventoryAllocations.addDonation(donation, UUID.randomUUID(), 5);

      assertThat(InventoryStockDecisions.split(jobOrder, 6, 0))
          .isEqualTo(SplitRefusal.EARMARKS_EXCEED_REST);
      assertThat(InventoryStockDecisions.split(mission, 6, 0))
          .isEqualTo(SplitRefusal.EARMARKS_EXCEED_REST);
      assertThat(InventoryStockDecisions.split(donation, 6, 0))
          .isEqualTo(SplitRefusal.EARMARKS_EXCEED_REST);
    }
  }

  @Nested
  class RebookLot {

    @Test
    void takesFromTheSourcesInOrderUntilTheAmountIsMet() {
      InventoryItem first = scuRow(3, true);
      InventoryItem second = scuRow(5, true);

      LotRebook decision =
          InventoryStockDecisions.rebookLot(List.of(first, second), Map.of(), 6, true, false);

      assertThat(decision.refusal()).isNull();
      assertThat(decision.takes()).containsExactly(new Take(first, 3), new Take(second, 3));
    }

    @Test
    void moreThanTheSourcesHoldIsRefused() {
      LotRebook decision =
          InventoryStockDecisions.rebookLot(List.of(scuRow(3, true)), Map.of(), 4, true, false);

      assertThat(decision.refusal()).isEqualTo(RebookRefusal.INSUFFICIENT_STOCK);
      assertThat(decision.takes()).isEmpty();
    }

    @Test
    void anEarmarkedPoolRowCannotBecomePersonal() {
      InventoryItem shared = scuRow(5, false);
      InventoryAllocations.addMission(shared, UUID.randomUUID(), 1);

      assertThat(
              InventoryStockDecisions.rebookLot(List.of(shared), Map.of(), 1, false, false)
                  .refusal())
          .isEqualTo(RebookRefusal.STOCK_EARMARKED);
    }

    @Test
    void aShareKeepsTheEarmarkedPartBack() {
      InventoryItem personal = scuRow(5, true);
      InventoryAllocations.addDonation(personal, UUID.randomUUID(), 2);

      assertThat(
              InventoryStockDecisions.rebookLot(List.of(personal), Map.of(), 4, true, false)
                  .refusal())
          .isEqualTo(RebookRefusal.STOCK_EARMARKED);
      assertThat(
              InventoryStockDecisions.rebookLot(List.of(personal), Map.of(), 3, true, false)
                  .takes())
          .containsExactly(new Take(personal, 3));
    }

    @Test
    void theOfferedPartStaysBehind() {
      InventoryItem personal = scuRow(5, true);
      Map<UUID, Double> offered = Map.of(personal.getId(), 3.0);

      assertThat(
              InventoryStockDecisions.rebookLot(List.of(personal), offered, 3, true, false)
                  .refusal())
          .isEqualTo(RebookRefusal.STOCK_OFFERED);
      assertThat(
              InventoryStockDecisions.rebookLot(List.of(personal), offered, 2, true, false).takes())
          .containsExactly(new Take(personal, 2));
    }

    @Test
    void wholeUnitLotsMoveOnlyWholePiecesOffARow() {
      InventoryItem first = pieceRow(2.5);
      InventoryItem second = pieceRow(4);

      LotRebook decision =
          InventoryStockDecisions.rebookLot(List.of(first, second), Map.of(), 4, true, true);

      assertThat(decision.takes()).containsExactly(new Take(first, 2), new Take(second, 2));
    }
  }
}
