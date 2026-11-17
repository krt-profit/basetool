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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
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
 * one does (REQ-P4K-032, REQ-P4K-013, ADR-0341). The hangar import also matches a name without its
 * manufacturer word and takes the rows of one ship as one ship (REQ-HANGAR-019).
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
  }

  @Test
  void theHangarImportTakesTwoRowsOfTheSameNormalisedNameAsOneShip() {
    ShipType dash = ship("Hornet-F7C", null);
    ShipType space = ship("Hornet F7C", null);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(dash, space));

    ImportShipMatch.Candidate ship =
        ShipTypeMatcher.resolveImport(index, "HORNET_F7C", null).single();

    assertThat(ship).isNotNull();
    assertThat(ship.rows()).containsExactlyInAnyOrder(dash, space);
  }

  @Test
  void anAmbiguousNameFallsBackToTheSlugInTheHangarImport() {
    ShipType black = ship("Cutlass Black", null);
    ShipType red = ship("Cutlass Red", null);
    red.setUexSlug("cutlass-red");
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(black, red));

    assertThat(ShipTypeMatcher.resolveImport(index, "Cutlass", null).ships()).hasSize(2);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Cutlass", "cutlass-red")))
        .isSameAs(red);
  }

  @Test
  void theHangarImportMatchesACatalogueNameWithoutItsManufacturerWord() {
    Manufacturer esperia = manufacturer("Esperia", null, null);
    ShipType talon = shipBy("Esperia Talon", esperia);
    ShipType shrike = shipBy("Esperia Talon Shrike", esperia);
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(talon, shrike));

    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Talon", null))).isSameAs(talon);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "talon shrike", null)))
        .isSameAs(shrike);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Esperia Talon", null)))
        .isSameAs(talon);
    assertThat(ShipTypeMatcher.resolveName(index, "Talon").candidates())
        .containsExactlyInAnyOrder(talon, shrike);
  }

  @Test
  void initialsNicknameAndAbbreviationNameTheManufacturer() {
    ShipType zeus =
        shipBy("RSI Zeus Mk II ES", manufacturer("Roberts Space Industries", null, null));
    ShipType nomad = shipBy("C.O. Nomad", manufacturer("Consolidated Outland", null, null));
    ShipType fortune =
        shipBy(
            "MISC Fortune", manufacturer("Musashi Industrial and Starflight Concern", null, null));
    ShipType mole = shipBy("ARGO MOLE", manufacturer("Argo Astronautics", "Argo", "ARGO"));
    ShipType kraken = shipBy("Kraken", manufacturer("Drake Interplanetary", "Drake", "DRAK"));
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(List.of(zeus, nomad, fortune, mole, kraken));

    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "zeus mk ii es", null)))
        .isSameAs(zeus);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Nomad", null))).isSameAs(nomad);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "fortune", null))).isSameAs(fortune);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Mole", null))).isSameAs(mole);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Kraken", null))).isSameAs(kraken);
  }

  @Test
  void aShipTypeWithoutOrWithTheWrongManufacturerStillLosesItsManufacturerWord() {
    Manufacturer rsi = manufacturer("Roberts Space Industries", null, null);
    Manufacturer vanduul = manufacturer("Vanduul", null, null);
    ShipType andromeda = ship("Constellation Mk IV Andromeda", null);
    ShipType rsiAndromeda = shipBy("RSI Constellation Mk IV Andromeda", rsi);
    ShipType stinger = shipBy("Esperia Stinger", vanduul);
    ShipType scythe = shipBy("Vanduul Scythe", manufacturer("Esperia", null, null));
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(List.of(andromeda, rsiAndromeda, stinger, scythe));

    ImportShipMatch.Candidate ship =
        ShipTypeMatcher.resolveImport(index, "Constellation Andromeda", null).single();

    assertThat(ship).isNotNull();
    assertThat(ship.rows()).containsExactly(rsiAndromeda, andromeda);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Stinger", null))).isSameAs(stinger);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Scythe", null))).isSameAs(scythe);
  }

  @Test
  void anExactMatchBringsTheOtherRowsOfItsShipAlong() {
    ShipType carrack = ship("Carrack", null);
    ShipType anvilCarrack = shipBy("Anvil Carrack", manufacturer("Anvil Aerospace", null, null));
    ShipType expedition =
        shipBy("Anvil Carrack Expedition", manufacturer("Anvil Aerospace", null, null));
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(List.of(carrack, anvilCarrack, expedition));

    ImportShipMatch.Candidate ship = ShipTypeMatcher.resolveImport(index, "Carrack", null).single();

    assertThat(ship).isNotNull();
    assertThat(ship.rows()).containsExactly(anvilCarrack, carrack);
    assertThat(ship.canonical()).isSameAs(anvilCarrack);
  }

  @Test
  void aNameOfSeveralShipsOffersEachShipOnce() {
    Manufacturer rsi = manufacturer("Roberts Space Industries", null, null);
    ShipType taurus = ship("Constellation Mk IV Taurus", null);
    ShipType rsiTaurus = shipBy("RSI Constellation Mk IV Taurus", rsi);
    ShipType wikelo = shipBy("Constellation Taurus Wikelo War Special", rsi);
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(List.of(taurus, rsiTaurus, wikelo));

    ImportShipMatch match = ShipTypeMatcher.resolveImport(index, "Constellation Taurus", null);

    assertThat(match.single()).isNull();
    assertThat(match.ships())
        .extracting(ImportShipMatch.Candidate::canonical)
        .containsExactly(wikelo, rsiTaurus);
  }

  @Test
  void aRenamedShipResolvesThroughTheShortNameItsUploadNameHolds() {
    Manufacturer rsi = manufacturer("Roberts Space Industries", "RSI", null);
    ShipType ursa = shipBy("RSI Ursa", rsi);
    ShipType fortuna = shipBy("RSI Ursa Fortuna", rsi);
    ShipType medivac = shipBy("RSI Ursa Medivac", rsi);
    ShipTypeMatcher.ShipTypeIndex index =
        ShipTypeMatcher.buildIndex(List.of(ursa, fortuna, medivac));

    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Ursa Rover", null))).isSameAs(ursa);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "ursa medivac", null)))
        .isSameAs(medivac);
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
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Avenger Titan", null)))
        .isSameAs(live);
  }

  @Test
  void aRetiredShipTypeAnswersOnlyWhenNoLiveOneMatches() {
    ShipType swept = ship("Hull E", "RSI_Hull_E");
    swept.setP4kDeletedAt(Instant.now());
    ShipType live = ship("Avenger Titan", "AEGS_Avenger_Titan");
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(swept, live));

    assertThat(ShipTypeMatcher.resolveClassName(index, "RSI_Hull_E").single()).isSameAs(swept);
    assertThat(canonical(ShipTypeMatcher.resolveImport(index, "Hull E", null))).isSameAs(swept);
  }

  @Test
  void aLiveAmbiguousNameNeverFallsBackToARetiredShipType() {
    ShipType black = ship("Cutlass Black", null);
    ShipType red = ship("Cutlass Red", null);
    ShipType retired = ship("Cutlass", null);
    retired.setP4kDeletedAt(Instant.now());
    ShipTypeMatcher.ShipTypeIndex index = ShipTypeMatcher.buildIndex(List.of(black, red, retired));

    assertThat(ShipTypeMatcher.resolveImport(index, "Cutlass", null).ships())
        .extracting(ImportShipMatch.Candidate::canonical)
        .containsExactly(black, red);
  }

  /**
   * Returns the canonical row of the one ship a hangar-import match found.
   *
   * @param match the match
   * @return the canonical row, or {@code null} when the match found none or several ships
   */
  private static @Nullable ShipType canonical(@NotNull ImportShipMatch match) {
    ImportShipMatch.Candidate ship = match.single();
    return ship == null ? null : ship.canonical();
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

  /**
   * Builds a ship type with an id and a manufacturer.
   *
   * @param name the name
   * @param manufacturer the manufacturer, or {@code null}
   * @return the ship type
   */
  private static @NotNull ShipType shipBy(
      @NotNull String name, @Nullable Manufacturer manufacturer) {
    ShipType ship = ship(name, null);
    ship.setManufacturer(manufacturer);
    return ship;
  }

  /**
   * Builds a manufacturer.
   *
   * @param name the name
   * @param nickname the nickname, or {@code null}
   * @param abbreviation the abbreviation, or {@code null}
   * @return the manufacturer
   */
  private static @NotNull Manufacturer manufacturer(
      @NotNull String name, @Nullable String nickname, @Nullable String abbreviation) {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setId(UUID.randomUUID());
    manufacturer.setName(name);
    manufacturer.setNickname(nickname);
    manufacturer.setAbbreviation(abbreviation);
    return manufacturer;
  }
}
