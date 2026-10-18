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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the game-item projection of {@link JobOrderItemHandoverMapper}. */
class JobOrderItemHandoverMapperTest {

  private final JobOrderItemHandoverMapper mapper =
      mock(JobOrderItemHandoverMapper.class, CALLS_REAL_METHODS);

  @Test
  void handoverEntryReferenceCarriesAComponentsTypeSizeGradeAndClass() {
    GameItem shield = gameItem("FR-66");
    shield.setWikiType("Shield");
    shield.setSizeClass(1);
    shield.setGrade("A");
    shield.setItemClass(ShipComponentClass.MILITARY);

    assertThat(mapper.toGameItemReference(shield))
        .isEqualTo(
            new GameItemReferenceDto(
                shield.getId(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY"));
  }

  @Test
  void handoverEntryReferenceOfANonComponentCarriesNoComponentData() {
    GameItem paint = gameItem("Paint");
    paint.setWikiType("Paints");
    paint.setGrade("A");

    assertThat(mapper.toGameItemReference(paint))
        .isEqualTo(new GameItemReferenceDto(paint.getId(), "Paint", "VEHICLE_ITEM"));
  }

  @Test
  void handoverEntryReferenceOfNoItemIsNull() {
    assertThat(mapper.toGameItemReference(null)).isNull();
  }

  private static GameItem gameItem(String name) {
    GameItem item = new GameItem();
    item.setId(UUID.randomUUID());
    item.setName(name);
    item.setKind(GameItemKind.VEHICLE_ITEM);
    return item;
  }
}
