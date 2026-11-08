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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Ship types match by GUID and class name first, by UEX id and alias next, by name last, and a
 * duplicate name never picks one of its ship types; a retired ship type answers only where no live
 * one does (REQ-P4K-032, REQ-P4K-013, ADR-0341).
 */
class ShipTypeMatcherTest {

  @Test
  void twoShipTypesWithTheSameNormalisedNameAreAmbiguous() {
    ShipType dash = ship("Hornet-F7C", null);
    ShipType space = ship("Hornet F7C", null);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(dash, space));

    ShipTypeMatch match = ShipTypeMatcher.resolveName(index, "HORNET_F7C");

    assertThat(match.candidates()).containsExactlyInAnyOrder(dash, space);
    assertThat(match.single()).isNull();
    assertThat(ShipTypeMatcher.resolve(index, "HORNET_F7C", null)).isNull();
  }

  @Test
  void anAmbiguousNameFallsBackToTheSlugInTheHangarImport() {
    ShipType dash = ship("Hornet-F7C", null);
    dash.setUexSlug("hornet-f7c");
    ShipType space = ship("Hornet F7C", null);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(dash, space));

    assertThat(ShipTypeMatcher.resolve(index, "HORNET_F7C", "hornet-f7c")).isSameAs(dash);
  }

  @Test
  void aUniqueNameStillResolvesThroughTheTolerantStages() {
    ShipType titan = ship("Avenger Titan", null);
    ShipType cutlass = ship("Cutlass Black", null);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(titan, cutlass));

    assertThat(ShipTypeMatcher.resolveName(index, "avenger titan").single()).isSameAs(titan);
    assertThat(ShipTypeMatcher.resolveName(index, "Titan").single()).isSameAs(titan);
    assertThat(ShipTypeMatcher.resolveName(index, "Drake Cutlass Black").single())
        .isSameAs(cutlass);
    assertThat(ShipTypeMatcher.resolveName(index, "Carrack").isEmpty()).isTrue();
  }

  @Test
  void aTokenAbbreviationOfSeveralShipTypesIsAmbiguous() {
    ShipType black = ship("Cutlass Black", null);
    ShipType red = ship("Cutlass Red", null);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(black, red));

    assertThat(ShipTypeMatcher.resolveName(index, "Cutlass").candidates())
        .containsExactlyInAnyOrder(black, red);
  }

  @Test
  void aVariantClassNameResolvesToItsOwnShipTypeElseToItsBase() {
    ShipType base = ship("Hornet F7C Mk II", "ANVL_Hornet_F7C_Mk2");
    ShipType own = ship("Hornet F7C Mk II Heartseeker", "ANVL_Hornet_F7C_Mk2_Heartseeker");
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(
            List.of(base, own),
            new ShipTypeLinks(
                Map.of(
                    "anvl_hornet_f7c_mk2_heartseeker", base.getId(),
                    "anvl_hornet_f7c_mk2_ai_super", base.getId()),
                Map.of()));

    assertThat(ShipTypeMatcher.resolveClassName(index, "ANVL_Hornet_F7C_Mk2_Heartseeker").single())
        .isSameAs(own);
    assertThat(ShipTypeMatcher.resolveClassName(index, "anvl_hornet_f7c_mk2_ai_super").single())
        .isSameAs(base);
    assertThat(ShipTypeMatcher.resolveClassName(index, "ANVL_Unknown").isEmpty()).isTrue();
  }

  @Test
  void aGuidResolvesByTheP4kGuidOrTheWikiUuid() {
    ShipType p4k = ship("Avenger Titan", null);
    p4k.setP4kUuid(UUID.randomUUID());
    ShipType wiki = ship("Cutlass Black", null);
    wiki.setExternalUuid(UUID.randomUUID());
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(p4k, wiki));

    assertThat(ShipTypeMatcher.resolveGuid(index, p4k.getP4kUuid()).single()).isSameAs(p4k);
    assertThat(ShipTypeMatcher.resolveGuid(index, wiki.getExternalUuid()).single()).isSameAs(wiki);
    assertThat(ShipTypeMatcher.resolveGuid(index, UUID.randomUUID()).isEmpty()).isTrue();
  }

  @Test
  void aUexIdResolvesByTheVehicleIdThenByTheCuratedAlias() {
    ShipType direct = ship("Avenger Titan", null);
    direct.setUexVehicleId(7);
    ShipType aliased = ship("Prowler Utility", "ESPR_Prowler_Utility");
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(
            List.of(direct, aliased),
            new ShipTypeLinks(
                Map.of(), Map.of(7, List.of(aliased.getId()), 9, List.of(aliased.getId()))));

    assertThat(ShipTypeMatcher.resolveUexId(index, 7).single()).isSameAs(direct);
    assertThat(ShipTypeMatcher.resolveUexId(index, 9).single()).isSameAs(aliased);
    assertThat(ShipTypeMatcher.resolveUexId(index, 11).isEmpty()).isTrue();
  }

  @Test
  void aLiveShipTypeWinsOverARetiredOneWithTheSameKeys() {
    UUID guid = UUID.randomUUID();
    ShipType swept = ship("Avenger Titan", "AEGS_Avenger_Titan");
    swept.setP4kUuid(guid);
    swept.setUexVehicleId(7);
    swept.setP4kDeletedAt(Instant.now());
    ShipType hidden = ship("Avenger Titan (old)", null);
    hidden.setP4kHiddenAt(Instant.now());
    ShipType live = ship("Avenger-Titan", "aegs_avenger_titan");
    live.setExternalUuid(guid);
    live.setUexVehicleId(7);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(swept, hidden, live));

    assertThat(ShipTypeMatcher.resolveGuid(index, guid).single()).isSameAs(live);
    assertThat(ShipTypeMatcher.resolveClassName(index, "AEGS_Avenger_Titan").single())
        .isSameAs(live);
    assertThat(ShipTypeMatcher.resolveUexId(index, 7).single()).isSameAs(live);
    assertThat(ShipTypeMatcher.resolveName(index, "Avenger Titan").single()).isSameAs(live);
    assertThat(ShipTypeMatcher.resolve(index, "Avenger Titan", null)).isSameAs(live);
  }

  @Test
  void aRetiredShipTypeAnswersOnlyWhenNoLiveOneMatches() {
    ShipType swept = ship("Hull E", "RSI_Hull_E");
    swept.setP4kDeletedAt(Instant.now());
    ShipType live = ship("Avenger Titan", "AEGS_Avenger_Titan");
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(swept, live));

    assertThat(ShipTypeMatcher.resolveClassName(index, "RSI_Hull_E").single()).isSameAs(swept);
    assertThat(ShipTypeMatcher.resolve(index, "Hull E", null)).isSameAs(swept);
  }

  /**
   * Builds a ship type with an id.
   *
   * @param name the name
   * @param className the class name, or {@code null}
   * @return the ship type
   */
  private static @NotNull ShipType ship(@NotNull String name, @Nullable String className) {
    ShipType ship = new ShipType();
    ship.setId(UUID.randomUUID());
    ship.setName(name);
    ship.setClassName(className);
    return ship;
  }
}
