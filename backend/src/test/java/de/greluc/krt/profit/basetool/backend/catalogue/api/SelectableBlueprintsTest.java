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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** The shared lookup that refuses a P4K-retired blueprint on a write (REQ-P4K-013). */
@ExtendWith(MockitoExtension.class)
class SelectableBlueprintsTest {

  @Mock private BlueprintRepository blueprintRepository;

  private SelectableBlueprints lookup;

  @BeforeEach
  void setUp() {
    lookup = new SelectableBlueprints(blueprintRepository);
  }

  @Test
  void aListedBlueprintIsReturned() {
    Blueprint blueprint = blueprint();
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    assertThat(lookup.require(blueprint.getId())).isSameAs(blueprint);
  }

  @Test
  void anUnknownIdIsNotFound() {
    UUID id = UUID.randomUUID();
    when(blueprintRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookup.require(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aBlueprintTheSweepStampedIsRefusedWithTheCatalogueCode() {
    Blueprint blueprint = blueprint();
    blueprint.setP4kDeletedAt(Instant.now());
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    assertThatThrownBy(() -> lookup.require(blueprint.getId()))
        .isInstanceOfSatisfying(
            RetiredBlueprintException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CATALOGUE_BLUEPRINT_RETIRED");
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.extraProperties())
                  .containsEntry("blueprintId", blueprint.getId().toString());
            });
  }

  @Test
  void aBlueprintAnAdminHidIsRefused() {
    Blueprint blueprint = blueprint();
    blueprint.setP4kHiddenAt(Instant.now());
    when(blueprintRepository.findById(blueprint.getId())).thenReturn(Optional.of(blueprint));

    assertThatThrownBy(() -> lookup.require(blueprint.getId()))
        .isInstanceOf(RetiredBlueprintException.class);
  }

  private static Blueprint blueprint() {
    Blueprint blueprint = new Blueprint();
    blueprint.setId(UUID.randomUUID());
    blueprint.setOutputName("Arclight");
    return blueprint;
  }
}
