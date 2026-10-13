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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.audit.internal.AuditHandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant.Rewritten;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The audit module's part of the handle anonymisation. */
@ExtendWith(MockitoExtension.class)
class AuditHandleAnonymisationTest {

  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Mock private AuditEventRepository auditEventRepository;

  @InjectMocks private AuditHandleAnonymisation participant;

  private final UUID userId = UUID.randomUUID();

  @Test
  void runsFirstAndRewritesTheTwoActivityTrailColumns() {
    assertThat(participant.position()).isEqualTo(10);
    assertThat(participant.columns())
        .containsExactly("audit_event.actor_handle", "audit_event.subject_label");
  }

  @Test
  void rewritesTheActorByIdThenTheLabelPerSpellingAndSumsTheRows() {
    when(auditEventRepository.anonymiseActorHandle(userId, SENTINEL)).thenReturn(2);
    when(auditEventRepository.anonymiseSubjectLabel("Longer", SENTINEL)).thenReturn(1);
    when(auditEventRepository.anonymiseSubjectLabel("Name", SENTINEL)).thenReturn(4);

    assertThat(participant.anonymise(userId, List.of("Longer", "Name")))
        .containsExactly(Map.entry(Rewritten.ACTIVITY_AUDIT, 7));

    InOrder order = inOrder(auditEventRepository);
    order.verify(auditEventRepository).anonymiseActorHandle(userId, SENTINEL);
    order.verify(auditEventRepository).anonymiseSubjectLabel("Longer", SENTINEL);
    order.verify(auditEventRepository).anonymiseSubjectLabel("Name", SENTINEL);
    verifyNoMoreInteractions(auditEventRepository);
  }

  @Test
  void rewritesOnlyTheActorWhenNoSpellingIsKnown() {
    when(auditEventRepository.anonymiseActorHandle(userId, SENTINEL)).thenReturn(3);

    assertThat(participant.anonymise(userId, List.of()))
        .containsExactly(Map.entry(Rewritten.ACTIVITY_AUDIT, 3));

    verifyNoMoreInteractions(auditEventRepository);
  }
}
