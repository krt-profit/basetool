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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The UEX twin rule merges a second listing's row only into a single game row (REQ-DATA-044). */
class UexItemTwinsTest {

  private static final String NAME = "Arclight Pistol";

  @Test
  void aListingsGameUuidLeadsToTheRowHoldingItWhenTheNameAgrees() {
    UUID uuid = UUID.randomUUID();
    GameItem target = gameRow(91, NAME);
    target.setExternalUuid(uuid);
    GameItem twin = twin(92);

    UexItemTwins.Twin found =
        rule(List.of(target), listing(91, uuid), listing(92, uuid)).find(twin, listing(92, uuid));

    assertThat(found)
        .isEqualTo(new UexItemTwins.Twin(twin.getId(), target.getId(), UexItemTwinRule.GAME_UUID));
  }

  @Test
  void aGameUuidOnARowOfAnotherNameNeverMergesNotEvenByName() {
    UUID uuid = UUID.randomUUID();
    GameItem rifle = gameRow(91, "Arclight Rifle");
    rifle.setExternalUuid(uuid);
    GameItem namesake = gameRow(93, NAME);
    GameItem twin = twin(92);

    UexItemTwins rule =
        rule(List.of(rifle, namesake), listing(91, uuid), listing(92, uuid), listing(93, null));

    assertThat(rule.find(twin, listing(92, uuid))).isNull();
  }

  @Test
  void aListingWithoutAGameUuidMergesIntoTheOnlyVisibleGameRowOfItsName() {
    GameItem target = gameRow(91, NAME);
    GameItem hidden = gameRow(93, NAME);
    hidden.setUnobtainableSince(Instant.parse("2026-11-01T00:00:00Z"));
    GameItem twin = twin(92);

    UexItemTwins.Twin found =
        rule(List.of(target, hidden), listing(91, null), listing(92, null), listing(93, null))
            .find(twin, listing(92, null));

    assertThat(found)
        .isEqualTo(
            new UexItemTwins.Twin(twin.getId(), target.getId(), UexItemTwinRule.UNIQUE_NAME));
  }

  @Test
  void aGameRowsUexNameCountsAsItsName() {
    GameItem target = gameRow(91, "Arclight \"Gold\" Pistol");
    target.setUexName(NAME);
    GameItem twin = twin(92);

    UexItemTwins.Twin found =
        rule(List.of(target), listing(91, null), listing(92, null)).find(twin, listing(92, null));

    assertThat(found).extracting(UexItemTwins.Twin::targetId).isEqualTo(target.getId());
  }

  @Test
  void twoGameRowsOfTheNameMergeNothingUnlessOneIsTheGsRecordOfTheOther() {
    GameItem first = gameRow(91, NAME);
    first.setClassName("KLWE_Pistol_Energy_01");
    GameItem second = gameRow(93, NAME);
    second.setClassName("KLWE_Pistol_Energy_02");
    GameItem twin = twin(92);

    UexItemTwins rule =
        rule(List.of(first, second), listing(91, null), listing(92, null), listing(93, null));

    assertThat(rule.find(twin, listing(92, null))).isNull();
  }

  @Test
  void aGsPairTakesTheTwinIntoTheGsRow() {
    GameItem old = gameRow(91, NAME);
    old.setClassName("KLWE_Pistol_Energy_01");
    GameItem gs = gameRow(93, NAME);
    gs.setClassName("klwe_pistol_energy_01_GS");
    GameItem twin = twin(92);

    UexItemTwins.Twin found =
        rule(List.of(old, gs), listing(91, null), listing(92, null), listing(93, null))
            .find(twin, listing(92, null));

    assertThat(found)
        .isEqualTo(new UexItemTwins.Twin(twin.getId(), gs.getId(), UexItemTwinRule.GS_RECORD));
  }

  @Test
  void aGameRowWhoseOwnListingLeftTheFetchTakesNoTwin() {
    GameItem target = gameRow(91, NAME);
    GameItem twin = twin(92);

    assertThat(rule(List.of(target), listing(92, null)).find(twin, listing(92, null))).isNull();
  }

  @Test
  void aRowHoldingAGameUuidIsNoTwin() {
    GameItem target = gameRow(91, NAME);
    GameItem stamped = twin(92);
    stamped.setP4kUuid(UUID.randomUUID());

    assertThat(UexItemTwins.isTwinCandidate(stamped)).isFalse();
    assertThat(
            rule(List.of(target), listing(91, null), listing(92, null))
                .find(stamped, listing(92, null)))
        .isNull();
  }

  private static UexItemTwins rule(List<GameItem> candidates, UexItemDto... fetched) {
    return new UexItemTwins(
        candidates,
        UexIdentityCensus.of(
            List.of(fetched), UexItemDto::id, UexItemDto::uuid, UexItemDto::name, true));
  }

  private static GameItem gameRow(int uexItemId, String name) {
    GameItem row = new GameItem();
    row.setId(UUID.randomUUID());
    row.setName(name);
    row.setP4kUuid(UUID.randomUUID());
    row.setUexItemId(uexItemId);
    row.setSourceSystems(GameItemSourceSystem.BOTH);
    return row;
  }

  private static GameItem twin(int uexItemId) {
    GameItem row = new GameItem();
    row.setId(UUID.randomUUID());
    row.setName(NAME);
    row.setUexName(NAME);
    row.setUexItemId(uexItemId);
    row.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    return row;
  }

  private static UexItemDto listing(int id, @Nullable UUID uuid) {
    return new UexItemDto(
        id,
        0,
        3,
        1,
        0,
        NAME,
        "arclight-pistol-" + id,
        uuid == null ? "" : uuid.toString(),
        "1",
        null,
        null,
        0,
        null,
        "Personal Weapons",
        "Pistols",
        "Klaus & Werner",
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        "4.8.0-LIVE",
        0L,
        0L,
        null);
  }
}
