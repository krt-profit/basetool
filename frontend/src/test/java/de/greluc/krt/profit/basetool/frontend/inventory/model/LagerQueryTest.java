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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.ServletRequestDataBinder;

/** Pure tests of {@link LagerQuery}: parsing, emptiness, backend parameters and URL round-trip. */
class LagerQueryTest {

  private static final UUID MATERIAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID GAME_ITEM = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void emptyNarrowsNothingAndEmitsNoParameters() {
    LagerQuery query = LagerQuery.empty();

    assertThat(query.itemsView()).isFalse();
    assertThat(query.anyActive()).isFalse();
    assertThat(query.backendParams()).isEmpty();
    assertThat(query.urlParams()).isEmpty();
  }

  @Test
  void emptyItemsIsTheItemViewWithoutNarrowing() {
    LagerQuery query = LagerQuery.emptyItems();

    assertThat(query.itemsView()).isTrue();
    assertThat(query.anyActive()).isFalse();
    assertThat(query.backendParams().keySet()).containsExactly("catalog");
  }

  @Test
  void bindsTheUnchangedUrlParameterNames() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addParameter("view", "items");
    request.addParameter("gameItemIds", GAME_ITEM.toString());
    request.addParameter("locationIds", LOCATION.toString());
    request.addParameter("personalOnly", "true");
    request.addParameter("kinds", "VEHICLE_ITEM");
    request.addParameter("sizes", "2");
    ServletRequestDataBinder binder = new ServletRequestDataBinder(null, "lagerQuery");
    binder.setTargetType(org.springframework.core.ResolvableType.forClass(LagerQuery.class));
    binder.construct(request);

    LagerQuery query = (LagerQuery) binder.getTarget();

    assertThat(binder.getBindingResult().hasErrors()).isFalse();
    assertThat(query.itemsView()).isTrue();
    assertThat(query.gameItemIds()).containsExactly(GAME_ITEM);
    assertThat(query.locationIds()).containsExactly(LOCATION);
    assertThat(query.personalOnly()).isTrue();
    assertThat(query.nonPersonalOnly()).isFalse();
    assertThat(query.attributes().kinds()).containsExactly("VEHICLE_ITEM");
    assertThat(query.attributes().sizes()).containsExactly(2);
  }

  @Test
  void anUnboundRequestIsEmpty() {
    ServletRequestDataBinder binder = new ServletRequestDataBinder(null, "lagerQuery");
    binder.setTargetType(org.springframework.core.ResolvableType.forClass(LagerQuery.class));
    binder.construct(new MockHttpServletRequest());

    assertThat(binder.getTarget()).isEqualTo(LagerQuery.empty());
  }

  @Test
  void materialViewEmitsOnlyTheMaterialDimensionsToTheBackend() {
    LagerQuery query =
        LagerQuery.fromParams(
            Map.of(
                "materialIds", List.of(MATERIAL.toString()),
                "locationIds", List.of(LOCATION.toString()),
                "minQuality", List.of("3"),
                "gameItemIds", List.of(GAME_ITEM.toString()),
                "nonStolenOnly", List.of("true")));

    MultiValueMap<String, String> params = query.backendParams();

    assertThat(params.keySet())
        .containsExactly("materialIds", "locationIds", "minQuality", "nonStolenOnly");
    assertThat(params.getFirst("minQuality")).isEqualTo("3");
    assertThat(params.getFirst("nonStolenOnly")).isEqualTo("true");
    assertThat(query.anyActive()).isTrue();
  }

  @Test
  void itemsViewEmitsCatalogItemAndTheItemDimensionsToTheBackend() {
    LagerQuery query =
        LagerQuery.fromParams(
            Map.of(
                "view", List.of("items"),
                "materialIds", List.of(MATERIAL.toString()),
                "gameItemIds", List.of(GAME_ITEM.toString()),
                "componentTypes", List.of("SHIELD", "QUANTUM_DRIVE"),
                "grades", List.of(" A", "")));

    MultiValueMap<String, String> params = query.backendParams();

    assertThat(params.keySet())
        .containsExactly("catalog", "gameItemIds", "componentTypes", "grades");
    assertThat(params.get("catalog")).containsExactly("ITEM");
    assertThat(params.get("componentTypes")).containsExactly("SHIELD", "QUANTUM_DRIVE");
    assertThat(params.get("grades")).containsExactly("A");
  }

  @Test
  void parseThenUrlRoundTripsEveryDimension() {
    LagerQuery material =
        LagerQuery.fromParams(
            Map.of(
                "materialIds", List.of(MATERIAL.toString()),
                "locationIds", List.of(LOCATION.toString()),
                "minQuality", List.of("700"),
                "personalOnly", List.of("true"),
                "stolenOnly", List.of("true")));
    LagerQuery items =
        LagerQuery.fromParams(
            Map.of(
                "view", List.of("items"),
                "gameItemIds", List.of(GAME_ITEM.toString()),
                "kinds", List.of("VEHICLE_ITEM"),
                "sizes", List.of("1", "3"),
                "itemClasses", List.of("MILITARY"),
                "nonPersonalOnly", List.of("true")));

    assertThat(LagerQuery.fromParams(material.urlParams())).isEqualTo(material);
    assertThat(LagerQuery.fromParams(items.urlParams())).isEqualTo(items);
    assertThat(items.urlParams().getFirst("view")).isEqualTo("items");
  }

  @Test
  void backendParamsAndUrlParamsShareTheFilterParameterNames() {
    LagerQuery query =
        LagerQuery.fromParams(Map.of("view", List.of("items"), "kinds", List.of("VEHICLE_ITEM")));

    assertThat(query.urlParams().keySet()).containsExactly("view", "kinds");
    assertThat(query.backendParams().keySet()).containsExactly("catalog", "kinds");
  }

  @Test
  void withoutPersonalScopeDropsTheSquadronWideIrrelevantFlags() {
    LagerQuery query =
        LagerQuery.fromParams(
            Map.of(
                "personalOnly", List.of("true"),
                "nonPersonalOnly", List.of("true"),
                "stolenOnly", List.of("true")));

    LagerQuery squadron = query.withoutPersonalScope();

    assertThat(squadron.personalOnly()).isFalse();
    assertThat(squadron.nonPersonalOnly()).isFalse();
    assertThat(squadron.stolenOnly()).isTrue();
    assertThat(squadron.backendParams().keySet()).containsExactly("stolenOnly");
  }

  @Test
  void contradictoryFlagPairsPassThroughForTheBackendToAnswerEmpty() {
    LagerQuery query =
        LagerQuery.fromParams(
            Map.of(
                "personalOnly", List.of("true"),
                "nonPersonalOnly", List.of("true"),
                "stolenOnly", List.of("true"),
                "nonStolenOnly", List.of("true")));

    assertThat(query.backendParams().keySet())
        .containsExactly("personalOnly", "nonPersonalOnly", "stolenOnly", "nonStolenOnly");
    assertThat(LagerQuery.fromParams(query.urlParams())).isEqualTo(query);
    assertThat(query.anyActive()).isTrue();
  }

  @Test
  void parameterSlotsNameEveryParameterTheScriptsAppend() {
    assertThat(LagerQuery.parameterSlots())
        .containsExactly(
            "view=view",
            "materials=materialIds",
            "gameItems=gameItemIds",
            "locations=locationIds",
            "minQuality=minQuality",
            "jobOrders=jobOrderIds",
            "missions=missionIds",
            "personal=personalOnly",
            "nonPersonal=nonPersonalOnly",
            "stolen=stolenOnly",
            "nonStolen=nonStolenOnly");
  }

  @Test
  void aNonItemsViewValueIsTheMaterialView() {
    assertThat(LagerQuery.fromParams(Map.of("view", List.of("other"))).itemsView()).isFalse();
  }

  @Test
  void filterParameterNamesListEveryNameOnce() {
    assertThat(LagerQuery.filterParameterNames())
        .containsExactly(
            "materialIds",
            "gameItemIds",
            "locationIds",
            "minQuality",
            "jobOrderIds",
            "missionIds",
            "personalOnly",
            "nonPersonalOnly",
            "stolenOnly",
            "nonStolenOnly",
            "kinds",
            "componentTypes",
            "sizes",
            "grades",
            "itemClasses");
    assertThat(LagerQuery.attributeFilters())
        .containsExactly(
            "kinds:kind",
            "componentTypes:componentType",
            "sizes:size",
            "grades:grade",
            "itemClasses:itemClass");
  }
}
