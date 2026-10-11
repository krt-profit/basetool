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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MissionReferenceReader}. */
@ExtendWith(MockitoExtension.class)
class MissionReferenceReaderTest {

  @Mock private MissionRepository missionRepository;

  @InjectMocks private MissionReferenceReader reader;

  @Test
  void theExistingMissions_comeBackById_andAnUnknownOneIsAbsent() {
    UUID known = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    MissionReferenceDto reference =
        new MissionReferenceDto(
            known, "Op Sunfire", "PLANNED", Instant.parse("2026-10-01T18:00:00Z"));
    when(missionRepository.findReferencesByIdIn(Set.of(known, unknown)))
        .thenReturn(List.of(reference));

    Map<UUID, MissionReferenceDto> found = reader.references(Set.of(known, unknown));

    assertEquals(Map.of(known, reference), found);
  }

  @Test
  void noIds_askNoRepository() {
    assertTrue(reader.references(Set.of()).isEmpty());

    verifyNoInteractions(missionRepository);
  }
}
