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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The in-memory match index answers what the repository queries answer on the current rows. */
class P4kRowIndexTest {

  private static final UUID GUID = UUID.fromString("3f1d7f43-2b1c-4b3e-9a59-3f8d1d0f6a01");

  private static GameItem item(String name, String className) {
    GameItem row = new GameItem();
    row.setName(name);
    row.setClassName(className);
    return row;
  }

  private static P4kRowIndex<GameItem> index(List<GameItem> rows) {
    return new P4kRowIndex<>(
        rows, GameItem::getExternalUuid, GameItem::getClassName, GameItem::getName);
  }

  @Test
  void classNamesAndNames_matchIgnoringCase_andKeepEveryRowSharingAKey() {
    GameItem first = item("Arrow", "ARROW_A");
    GameItem second = item("arrow", "arrow_b");

    P4kRowIndex<GameItem> index = index(List.of(first, second));

    assertThat(index.byName("ARROW")).containsExactlyInAnyOrder(first, second);
    assertThat(index.byClassName("Arrow_B")).containsExactly(second);
    assertThat(index.byName("Bow")).isEmpty();
  }

  @Test
  void aRowRenamedInTheRun_leavesItsOldName_andIsFoundByItsNewOneOnceAdded() {
    GameItem row = item("Old Name", "CLASS_A");
    P4kRowIndex<GameItem> index = index(List.of(row));

    row.setName("New Name");
    assertThat(index.byName("Old Name")).isEmpty();
    index.add(row);

    assertThat(index.byName("new name")).containsExactly(row);
  }

  @Test
  void aGuidStampedOrACreatedRow_isFoundOnceAdded_andAddingTwiceKeepsOneEntry() {
    GameItem known = item("Known", null);
    P4kRowIndex<GameItem> index = index(List.of(known));
    GameItem created = item("Created", null);
    created.setExternalUuid(GUID);

    known.setP4kUuid(GUID);
    index.add(known);
    index.add(known);
    index.add(created);

    assertThat(index.byP4kUuid(GUID)).containsSame(known);
    assertThat(index.byWikiUuid(GUID)).containsSame(created);
    assertThat(index.byName("Created")).containsExactly(created);
  }
}
