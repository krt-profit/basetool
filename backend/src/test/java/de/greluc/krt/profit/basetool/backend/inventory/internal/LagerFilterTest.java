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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.ServletRequestDataBinder;

/** Pure tests of {@link LagerFilter}: defaults, exclusions, catalog guard and request binding. */
class LagerFilterTest {

  @Test
  void noneNarrowsNothingAndDefaultsToMaterialCatalog() {
    LagerFilter filter = LagerFilter.NONE;

    assertThat(filter.catalog()).isEqualTo(InventoryCatalog.MATERIAL);
    assertThat(filter.hasMaterials()).isFalse();
    assertThat(filter.materialIdsOrNull()).isNull();
    assertThat(filter.locationIdsOrNull()).isNull();
    assertThat(filter.contradictory()).isFalse();
    assertThat(filter.attributes().isEmpty()).isTrue();
  }

  @Test
  void nullListsBecomeEmptyAndNullEntriesAreDropped() {
    UUID id = UUID.randomUUID();
    LagerFilter filter =
        LagerFilter.builder().materialIds(Arrays.asList(id, null)).gameItemIds(null).build();

    assertThat(filter.materialIds()).containsExactly(id);
    assertThat(filter.materialIdsOrNull()).containsExactly(id);
    assertThat(filter.gameItemIds()).isEmpty();
  }

  @Test
  void personalAndNonPersonalExcludeEachOther() {
    assertThat(
            LagerFilter.builder().personalOnly(true).nonPersonalOnly(true).build().contradictory())
        .isTrue();
    assertThat(LagerFilter.builder().personalOnly(true).build().contradictory()).isFalse();
  }

  @Test
  void stolenAndNonStolenExcludeEachOther() {
    LagerFilter both = LagerFilter.builder().stolenOnly(true).nonStolenOnly(true).build();

    assertThat(both.contradictory()).isTrue();
    assertThat(both.stolenContradiction()).isTrue();
    assertThat(LagerFilter.builder().nonStolenOnly(true).build().contradictory()).isFalse();
  }

  @Test
  void personalPairIsNoStolenContradiction() {
    LagerFilter filter = LagerFilter.builder().personalOnly(true).nonPersonalOnly(true).build();

    assertThat(filter.stolenContradiction()).isFalse();
  }

  @Test
  void itemCatalogRejectsMaterialOnlyFilters() {
    LagerFilter.LagerFilterBuilder item = LagerFilter.builder().catalog(InventoryCatalog.ITEM);

    assertThatThrownBy(() -> item.minQuality(500).build().requireMatchingCatalog())
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                item.minQuality(null)
                    .missionIds(List.of(UUID.randomUUID()))
                    .build()
                    .requireMatchingCatalog())
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                item.missionIds(null)
                    .materialIds(List.of(UUID.randomUUID()))
                    .build()
                    .requireMatchingCatalog())
        .isInstanceOf(BadRequestException.class);
    assertThatCode(
            () ->
                LagerFilter.builder()
                    .catalog(InventoryCatalog.ITEM)
                    .build()
                    .requireMatchingCatalog())
        .doesNotThrowAnyException();
  }

  @Test
  void materialCatalogRejectsItemOnlyFilters() {
    assertThatThrownBy(
            () ->
                LagerFilter.builder()
                    .gameItemIds(List.of(UUID.randomUUID()))
                    .build()
                    .requireMatchingCatalog())
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                LagerFilter.builder()
                    .kinds(List.of(GameItemKind.values()[0]))
                    .build()
                    .requireMatchingCatalog())
        .isInstanceOf(BadRequestException.class);
    assertThatCode(() -> LagerFilter.builder().minQuality(500).build().requireMatchingCatalog())
        .doesNotThrowAnyException();
  }

  @Test
  void bindsTheUnchangedQueryParameterNamesFromTheRequest() {
    UUID material = UUID.randomUUID();
    UUID location = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("materialIds", material.toString());
    request.addParameter("locationIds", location.toString());
    request.addParameter("minQuality", "700");
    request.addParameter("personalOnly", "true");
    request.addParameter("nonStolenOnly", "true");
    request.addParameter("catalog", "MATERIAL");

    ServletRequestDataBinder binder = new ServletRequestDataBinder(null, "filter");
    binder.setTargetType(org.springframework.core.ResolvableType.forClass(LagerFilter.class));
    binder.construct(request);

    assertThat(binder.getBindingResult().getAllErrors()).isEmpty();
    LagerFilter bound = (LagerFilter) binder.getTarget();
    assertThat(bound.materialIds()).containsExactly(material);
    assertThat(bound.locationIds()).containsExactly(location);
    assertThat(bound.minQuality()).isEqualTo(700);
    assertThat(bound.personalOnly()).isTrue();
    assertThat(bound.nonPersonalOnly()).isFalse();
    assertThat(bound.nonStolenOnly()).isTrue();
    assertThat(bound.catalog()).isEqualTo(InventoryCatalog.MATERIAL);
  }

  @Test
  void bindsAnEmptyRequestToTheDefaults() {
    ServletRequestDataBinder binder = new ServletRequestDataBinder(null, "filter");
    binder.setTargetType(org.springframework.core.ResolvableType.forClass(LagerFilter.class));
    binder.construct(new MockHttpServletRequest());

    assertThat(binder.getTarget()).isEqualTo(LagerFilter.NONE);
  }
}
