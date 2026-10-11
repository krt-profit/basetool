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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant.Rewritten;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The job-order module's part of the handle anonymisation. */
@ExtendWith(MockitoExtension.class)
class JobOrderHandleAnonymisationTest {

  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;

  @InjectMocks private JobOrderHandleAnonymisation participant;

  private final UUID userId = UUID.randomUUID();

  @Test
  void runsLastAndRewritesTheThreeTextMatchedColumns() {
    assertThat(participant.position()).isEqualTo(30);
    assertThat(participant.columns())
        .containsExactly(
            "job_order.handle",
            "job_order_handover.recipient_handle",
            "job_order_item_handover.recipient_handle");
  }

  @Test
  void rewritesEachColumnOncePerSpellingAndSumsTheRows() {
    when(jobOrderRepository.anonymiseHandle("Longer", SENTINEL)).thenReturn(1);
    when(jobOrderRepository.anonymiseHandle("Name", SENTINEL)).thenReturn(2);
    when(jobOrderHandoverRepository.anonymiseRecipientHandle("Longer", SENTINEL)).thenReturn(3);
    when(jobOrderHandoverRepository.anonymiseRecipientHandle("Name", SENTINEL)).thenReturn(0);
    when(jobOrderItemHandoverRepository.anonymiseRecipientHandle("Longer", SENTINEL)).thenReturn(0);
    when(jobOrderItemHandoverRepository.anonymiseRecipientHandle("Name", SENTINEL)).thenReturn(5);

    assertThat(participant.anonymise(userId, List.of("Longer", "Name")))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                Rewritten.JOB_ORDERS, 3,
                Rewritten.MATERIAL_HANDOVERS, 3,
                Rewritten.ITEM_HANDOVERS, 5));
  }

  @Test
  void touchesNothingWhenNoSpellingIsKnown() {
    assertThat(participant.anonymise(userId, List.of()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                Rewritten.JOB_ORDERS, 0,
                Rewritten.MATERIAL_HANDOVERS, 0,
                Rewritten.ITEM_HANDOVERS, 0));

    verifyNoInteractions(
        jobOrderRepository, jobOrderHandoverRepository, jobOrderItemHandoverRepository);
  }
}
