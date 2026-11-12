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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionOperationSummary;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationLinkability;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the operation module's answers to the mission module: the embedded summaries are keyed
 * by id, and a link follows the operation's edit rule (REQ-MISSION-034).
 */
@ExtendWith(MockitoExtension.class)
class OperationSummaryReaderTest {

  private static final UUID UNIT = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @Mock private OperationRepository operationRepository;
  @Mock private OperationAccessPolicy operationAccessPolicy;

  private OperationSummaryReader reader;

  @BeforeEach
  void setUp() {
    reader = new OperationSummaryReader(operationRepository, operationAccessPolicy);
  }

  @Test
  void operations_keyTheSummariesOfTheExistingOperationsById() {
    UUID existing = UUID.randomUUID();
    UUID unknown = UUID.randomUUID();
    List<UUID> ids = List.of(existing, unknown);
    MissionOperationSummary summary =
        new MissionOperationSummary(
            existing, "Op", null, "ACTIVE", UNIT, "Unit", "U", 0L, null, null);
    when(operationRepository.findSummaries(ids)).thenReturn(List.of(summary));

    assertThat(reader.operations(ids)).isEqualTo(Map.of(existing, summary));
    verifyNoInteractions(operationAccessPolicy);
  }

  @Test
  void operations_ofNoIdsRunNoQuery() {
    assertThat(reader.operations(List.of())).isEmpty();

    verifyNoInteractions(operationRepository);
  }

  @Test
  void linkability_ofAnUnknownOperationIsNoSuchOperation() {
    UUID unknown = UUID.randomUUID();
    when(operationRepository.findById(unknown)).thenReturn(Optional.empty());

    assertThat(reader.linkability(unknown)).isEqualTo(OperationLinkability.NO_SUCH_OPERATION);
    verifyNoInteractions(operationAccessPolicy);
  }

  @Test
  void linkability_followsTheOperationsEditRule() {
    Operation editable = operation();
    Operation foreign = operation();
    when(operationRepository.findById(editable.getId())).thenReturn(Optional.of(editable));
    when(operationRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    when(operationAccessPolicy.canEdit(editable)).thenReturn(true);
    when(operationAccessPolicy.canEdit(foreign)).thenReturn(false);

    assertThat(reader.linkability(editable.getId())).isEqualTo(OperationLinkability.LINKABLE);
    assertThat(reader.linkability(foreign.getId())).isEqualTo(OperationLinkability.NOT_LINKABLE);
  }

  private static Operation operation() {
    Operation operation = new Operation();
    operation.setId(UUID.randomUUID());
    return operation;
  }
}
