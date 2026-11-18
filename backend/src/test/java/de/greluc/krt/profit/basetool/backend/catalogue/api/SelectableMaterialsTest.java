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

/**
 * The shared lookup that refuses an invisible, a P4K-retired or a sourceless hidden material on a
 * book-in (REQ-P4K-013, REQ-P4K-075).
 */
@ExtendWith(MockitoExtension.class)
class SelectableMaterialsTest {

  @Mock private MaterialRepository materialRepository;

  private SelectableMaterials lookup;

  @BeforeEach
  void setUp() {
    lookup = new SelectableMaterials(materialRepository);
  }

  @Test
  void aListedMaterialIsReturned() {
    Material laranite = material();

    assertThat(lookup.require(laranite.getId())).isSameAs(laranite);
  }

  @Test
  void anUnknownIdIsNotFound() {
    UUID id = UUID.randomUUID();
    when(materialRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> lookup.require(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aMaterialHiddenAsSourcelessIsRefusedWithTheCatalogueCode() {
    Material boron = material();
    boron.setSourcelessHiddenAt(Instant.now());

    assertThatThrownBy(() -> lookup.require(boron.getId()))
        .isInstanceOfSatisfying(
            RetiredMaterialException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CATALOGUE_MATERIAL_RETIRED");
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.extraProperties()).containsEntry("materialId", boron.getId().toString());
            });
  }

  @Test
  void aSourcelessMarkedMaterialAPriceBroughtBackIsSelectable() {
    Material boron = material();
    boron.setSourcelessHiddenAt(Instant.now());
    boron.setPriceSell(1.5);

    assertThat(lookup.require(boron.getId())).isSameAs(boron);
  }

  @Test
  void aMaterialTheSweepStampedOrAnAdminHidIsRefused() {
    Material swept = material();
    swept.setP4kDeletedAt(Instant.now());
    Material hidden = material();
    hidden.setP4kHiddenAt(Instant.now());

    assertThatThrownBy(() -> lookup.require(swept.getId()))
        .isInstanceOf(RetiredMaterialException.class);
    assertThatThrownBy(() -> lookup.require(hidden.getId()))
        .isInstanceOf(RetiredMaterialException.class);
  }

  @Test
  void anInvisibleMaterialIsRefused() {
    Material invisible = material();
    invisible.setIsVisible(false);

    assertThatThrownBy(() -> lookup.require(invisible.getId()))
        .isInstanceOf(RetiredMaterialException.class);
  }

  /**
   * Builds a visible material without a game source and registers it with the repository mock.
   *
   * @return the material
   */
  private Material material() {
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Laranite");
    when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
    return material;
  }
}
