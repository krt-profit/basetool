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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A merge moves a retired row's P4K identity onto the survivor as one unit (REQ-P4K-007). */
class P4kItemParticipationTest {

  private static final UUID GUID = UUID.fromString("6a4d3c0e-1f2b-4d5e-8a9b-0c1d2e3f4a5b");
  private static final UUID RUN = UUID.fromString("7b5e4d1f-2a3c-4e6f-9b0c-1d2e3f4a5b6c");
  private static final Instant SYNCED = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant HIDDEN = Instant.parse("2026-11-02T10:00:00Z");

  @Test
  void detachingAndAttaching_movesEveryP4kColumn_andClearsTheRetiredRow() {
    GameItem retired = new GameItem();
    retired.setP4kUuid(GUID);
    retired.setNameKey("item_name_shield");
    retired.setNameDe("Schild");
    retired.setP4kSyncedAt(SYNCED);
    retired.setP4kHiddenAt(HIDDEN);
    retired.setP4kSeenRunId(RUN);
    retired.setP4kName("Shield P4K");
    retired.setP4kGradeRaw(3);
    GameItem survivor = new GameItem();
    survivor.setName("Shield UEX");

    P4kItemParticipation.detach(retired, GUID).attachTo(survivor);

    assertThat(retired.getP4kUuid()).isNull();
    assertThat(retired.getNameKey()).isNull();
    assertThat(retired.getNameDe()).isNull();
    assertThat(retired.getP4kSyncedAt()).isNull();
    assertThat(retired.getP4kHiddenAt()).isNull();
    assertThat(retired.getP4kSeenRunId()).isNull();
    assertThat(retired.getP4kName()).isNull();
    assertThat(retired.getP4kGradeRaw()).isNull();
    assertThat(survivor.getP4kUuid()).isEqualTo(GUID);
    assertThat(survivor.getNameKey()).isEqualTo("item_name_shield");
    assertThat(survivor.getNameDe()).isEqualTo("Schild");
    assertThat(survivor.getP4kSyncedAt()).isEqualTo(SYNCED);
    assertThat(survivor.getP4kHiddenAt()).isEqualTo(HIDDEN);
    assertThat(survivor.getP4kSeenRunId()).isEqualTo(RUN);
    assertThat(survivor.getP4kName()).isEqualTo("Shield P4K");
    assertThat(survivor.getName()).isEqualTo("Shield P4K");
    assertThat(survivor.getP4kGradeRaw()).isEqualTo(3);
  }
}
