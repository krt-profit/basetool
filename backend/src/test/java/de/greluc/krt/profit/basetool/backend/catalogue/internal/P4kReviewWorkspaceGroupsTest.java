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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Covers grouping without conflating rows that have independent identity evidence. */
class P4kReviewWorkspaceGroupsTest {

  @Test
  void groupsPaintsWithNoLinkedRowButKeepsTheGuidConflictSeparate() {
    String path = "libs/foundry/records/scitemmanufacturer/paintcolorlogos/logo";
    var first = entry(P4kKind.MANUFACTURER, "Origin", path, null, null);
    var second = entry(P4kKind.MANUFACTURER, " origin ", path + "_2", null, null);
    var identity = entry(P4kKind.MANUFACTURER, "Origin", path + "_3", UUID.randomUUID(), null);

    var groups = P4kReviewWorkspaceGroups.from(List.of(first, second, identity), null, null);

    assertThat(groups).hasSize(2);
    assertThat(groups.getFirst().summary().batchable()).isTrue();
    assertThat(groups.getFirst().members()).containsExactly(first.review(), second.review());
    assertThat(groups.getLast().members()).containsExactly(identity.review());
    assertThat(groups.getLast().summary().batchable()).isFalse();
  }

  @Test
  void groupsGenericAssetsOnlyWithinTheSameSourceFamily() {
    var first = entry(P4kKind.ITEM, "Door Control", "game/doors/first", null, null);
    var second = entry(P4kKind.ITEM, "Door Control", "game/doors/second", null, null);
    var unrelated = entry(P4kKind.ITEM, "Door Control", "game/controlpanels/third", null, null);
    var ship = entry(P4kKind.SHIP, "Door Control", "game/doors/fourth", null, null);

    var groups =
        P4kReviewWorkspaceGroups.from(
            List.of(first, second, unrelated, ship), P4kKind.ITEM, P4kReviewReason.GUID_CONFLICT);

    assertThat(groups).hasSize(2);
    assertThat(groups.getFirst().members()).containsExactly(first.review(), second.review());
    assertThat(groups.getLast().members()).containsExactly(unrelated.review());
  }

  @Test
  void reciprocalConflictsHaveOneStableGroupWithNoBatchDisposition() {
    UUID firstRow = UUID.randomUUID();
    UUID otherRow = UUID.randomUUID();
    var first = entry(P4kKind.ITEM, "Coil", null, firstRow, otherRow);
    var second = entry(P4kKind.ITEM, "Coil", null, otherRow, firstRow);

    var group = P4kReviewWorkspaceGroups.from(List.of(first, second), null, null).getFirst();
    var reverse = P4kReviewWorkspaceGroups.from(List.of(second, first), null, null).getFirst();

    assertThat(group.id()).isEqualTo(reverse.id());
    assertThat(group.summary().batchable()).isFalse();
    assertThat(group.members()).containsExactly(first.review(), second.review());
  }

  @Test
  void ordersUexPlaceParentsBeforeTerminalsAndDependentShops() {
    var terminal = place(StarmapPlaceKind.TERMINAL, "Shubin terminal");
    var planet = place(StarmapPlaceKind.PLANET, "MicroTech");
    var system = place(StarmapPlaceKind.STAR_SYSTEM, "Stanton");
    var shop =
        entry(
            P4kKind.SHOP,
            P4kReviewReason.SHOP_LINK_NOT_FOUND,
            "shop",
            "Shubin shop",
            null,
            null,
            null);

    assertThat(P4kReviewWorkspaceGroups.from(List.of(shop, terminal, system, planet), null, null))
        .extracting(group -> group.summary().name())
        .containsExactly("Stanton", "MicroTech", "Shubin terminal", "Shubin shop");
  }

  private static P4kReviewCaseSource place(StarmapPlaceKind kind, String name) {
    return entry(
        P4kKind.STARMAP_OBJECT,
        P4kReviewReason.STARMAP_LINK_NOT_FOUND,
        kind.getTable(),
        name,
        null,
        null,
        null);
  }

  private static P4kReviewCaseSource entry(
      P4kKind kind, String name, @Nullable String path, @Nullable UUID row, @Nullable UUID other) {
    return entry(kind, P4kReviewReason.GUID_CONFLICT, null, name, path, row, other);
  }

  private static P4kReviewCaseSource entry(
      P4kKind kind,
      P4kReviewReason reason,
      @Nullable String className,
      String name,
      @Nullable String path,
      @Nullable UUID row,
      @Nullable UUID other) {
    UUID run = UUID.randomUUID();
    return new P4kReviewCaseSource(
        new P4kReviewCaseDto(
            UUID.randomUUID(),
            0,
            kind,
            UUID.randomUUID(),
            reason,
            P4kReviewStatus.OPEN,
            className,
            name,
            row,
            null,
            other,
            run,
            run,
            Instant.EPOCH,
            null,
            null),
        path);
  }
}
