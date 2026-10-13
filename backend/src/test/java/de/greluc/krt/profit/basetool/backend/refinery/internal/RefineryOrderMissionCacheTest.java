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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferences;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RefineryOrderMissionCache} outside a transaction. */
class RefineryOrderMissionCacheTest {

  private final MissionReferences missions = mock(MissionReferences.class);

  private final RefineryOrderMissionCache cache =
      new RefineryOrderMissionCache(missions, mock(EntityManager.class));

  @Test
  void anUnlinkedOrder_hasNoMissionAndAsksNobody() {
    assertNull(cache.mission(null));

    verifyNoInteractions(missions);
  }

  @Test
  void aLinkedOrder_getsTheReferenceTheMissionModuleAnswers() {
    UUID missionId = UUID.randomUUID();
    MissionReferenceDto reference =
        new MissionReferenceDto(
            missionId, "Op Sunfire", "ACTIVE", Instant.parse("2026-10-01T18:00:00Z"));
    when(missions.references(Set.of(missionId))).thenReturn(Map.of(missionId, reference));

    assertEquals(reference, cache.mission(missionId));
  }

  @Test
  void anUnknownMission_hasNoReference() {
    UUID missionId = UUID.randomUUID();
    when(missions.references(Set.of(missionId))).thenReturn(Map.of());

    assertNull(cache.mission(missionId));
  }
}
