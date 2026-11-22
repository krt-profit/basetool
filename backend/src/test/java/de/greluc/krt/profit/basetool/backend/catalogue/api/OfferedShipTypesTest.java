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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The rule which ship types the pickers offer and the write guard on it (REQ-P4K-080). */
class OfferedShipTypesTest {

  private static final Instant STAMPED = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * Builds a ship type the P4K matched, offered unless a test changes it.
   *
   * @return the ship type, with an id of its own
   */
  private static ShipType listed() {
    ShipType shipType = new ShipType();
    shipType.setId(UUID.randomUUID());
    shipType.setName("Listed ship");
    shipType.setP4kUuid(UUID.randomUUID());
    return shipType;
  }

  /**
   * The ship types no picker offers: one the game files do not list, a hidden, a swept and one
   * hidden through a review case.
   *
   * @return the ship types
   */
  static Stream<ShipType> notOffered() {
    ShipType unknownToTheGame = listed();
    unknownToTheGame.setP4kUuid(null);
    ShipType hidden = listed();
    hidden.setHidden(true);
    ShipType swept = listed();
    swept.setP4kDeletedAt(STAMPED);
    ShipType hiddenByReview = listed();
    hiddenByReview.setP4kHiddenAt(STAMPED);
    return Stream.of(unknownToTheGame, hidden, swept, hiddenByReview);
  }

  @Test
  void aListedShipTypeIsOffered() {
    ShipType offered = listed();

    assertThat(OfferedShipTypes.isOffered(offered)).isTrue();
    assertThat(OfferedShipTypes.isNotInGame(offered)).isFalse();
    assertThat(OfferedShipTypes.requireOffered(offered)).isSameAs(offered);
  }

  @ParameterizedTest
  @MethodSource("notOffered")
  void aShipTypeNoPickerOffersIsRefused(ShipType shipType) {
    assertThat(OfferedShipTypes.isOffered(shipType)).isFalse();
    assertThatThrownBy(() -> OfferedShipTypes.requireOffered(shipType))
        .isInstanceOf(ShipTypeNotOfferedException.class);
  }

  @Test
  void onlyAShipTypeWithoutAP4kGuidIsNotInTheGame() {
    ShipType unknownToTheGame = listed();
    unknownToTheGame.setP4kUuid(null);
    ShipType hidden = listed();
    hidden.setHidden(true);

    assertThat(OfferedShipTypes.isNotInGame(unknownToTheGame)).isTrue();
    assertThat(OfferedShipTypes.isNotInGame(hidden)).isFalse();
  }

  @ParameterizedTest
  @MethodSource("notOffered")
  void anEntryKeepingItsShipTypeStaysEditable(ShipType shipType) {
    ShipType sameRowLoadedAgain = listed();
    sameRowLoadedAgain.setId(shipType.getId());

    assertThat(OfferedShipTypes.requireOfferedOrCurrent(shipType, sameRowLoadedAgain))
        .isSameAs(shipType);
  }

  @ParameterizedTest
  @MethodSource("notOffered")
  void anEntryChangingToAShipTypeNoPickerOffersIsRefused(ShipType shipType) {
    assertThatThrownBy(() -> OfferedShipTypes.requireOfferedOrCurrent(shipType, listed()))
        .isInstanceOf(ShipTypeNotOfferedException.class);
    assertThatThrownBy(() -> OfferedShipTypes.requireOfferedOrCurrent(shipType, null))
        .isInstanceOf(ShipTypeNotOfferedException.class);
  }

  @Test
  void anEntryChangingToAnOfferedShipTypePasses() {
    ShipType offered = listed();
    ShipType current = listed();
    current.setP4kUuid(null);

    assertThat(OfferedShipTypes.requireOfferedOrCurrent(offered, current)).isSameAs(offered);
  }

  @Test
  void theRefusalCarriesItsCodeAndStatus() {
    ShipTypeNotOfferedException refusal = new ShipTypeNotOfferedException();

    assertThat(refusal.code()).isEqualTo("CATALOGUE_SHIP_TYPE_NOT_OFFERED");
    assertThat(refusal.status().value()).isEqualTo(400);
  }
}
