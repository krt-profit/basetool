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

import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The squadron-write guard on storage places (REQ-P4K-039). */
class OfferedLocationsTest {

  @Test
  void anOfferedPlacePasses() {
    Location offered = NotOfferedPlaces.place(false, false);

    assertThat(OfferedLocations.isOffered(offered)).isTrue();
    assertThat(OfferedLocations.requireOffered(offered)).isSameAs(offered);
    assertThat(OfferedLocations.require(Optional.of(offered), "missing")).isSameAs(offered);
  }

  @ParameterizedTest
  @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
  void aPlaceNoPickerOffersIsRefused(Location place) {
    assertThat(OfferedLocations.isOffered(place)).isFalse();
    assertThatThrownBy(() -> OfferedLocations.requireOffered(place))
        .isInstanceOf(LocationNotOfferedException.class);
    assertThatThrownBy(() -> OfferedLocations.require(Optional.of(place), "missing"))
        .isInstanceOf(LocationNotOfferedException.class);
  }

  @Test
  void aMissingPlaceIsNotFound() {
    assertThatThrownBy(() -> OfferedLocations.require(Optional.empty(), "missing"))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("missing");
  }

  @ParameterizedTest
  @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
  void anEntryKeepingItsPlaceStaysEditable(Location place) {
    Location sameRowLoadedAgain = NotOfferedPlaces.place(true, true);
    sameRowLoadedAgain.setId(place.getId());

    assertThat(OfferedLocations.requireOfferedOrCurrent(place, sameRowLoadedAgain)).isSameAs(place);
  }

  @ParameterizedTest
  @MethodSource("de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places")
  void anEntryMovingOntoAPlaceNoPickerOffersIsRefused(Location place) {
    Location current = NotOfferedPlaces.place(false, false);

    assertThatThrownBy(() -> OfferedLocations.requireOfferedOrCurrent(place, current))
        .isInstanceOf(LocationNotOfferedException.class);
    assertThatThrownBy(() -> OfferedLocations.requireOfferedOrCurrent(place, null))
        .isInstanceOf(LocationNotOfferedException.class);
  }

  @Test
  void anEntryMovingOntoAnOfferedPlacePasses() {
    Location offered = NotOfferedPlaces.place(false, false);
    Location current = NotOfferedPlaces.place(true, false);

    assertThat(OfferedLocations.requireOfferedOrCurrent(offered, current)).isSameAs(offered);
  }
}
