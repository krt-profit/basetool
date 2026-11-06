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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies that reference changes refresh the owning surface only after transaction commit. */
@ExtendWith(MockitoExtension.class)
class JobOrderItemReassignmentTest {
  @Mock private JobOrderItemReassignmentRepository repository;
  @Mock private AuditRecorder audit;
  @Mock private LiveSyncPublisher publisher;

  @Test
  void changedReferencesAnnounceOnlyTheirOwningRoomsAfterCommit() {
    UUID retired = UUID.randomUUID();
    UUID canonical = UUID.randomUUID();
    UUID resource = UUID.randomUUID();
    when(repository.orders(retired)).thenReturn(List.of(resource));
    when(repository.orderItems(retired, canonical)).thenReturn(List.of(UUID.randomUUID()));
    var participant = new JobOrderItemReassignment(repository, audit, publisher);

    assertThat(participant.reassignGameItem(retired, canonical)).isEqualTo(1);

    verify(publisher)
        .publishAfterCommit(
            LiveSyncTopic.of(LiveSyncTopicClass.ORDER, resource),
            List.of(
                "items",
                "item-stock",
                "item-handovers",
                "item-handover-lines",
                "blueprint-owners"));
  }

  @Test
  void unchangedReferencesAnnounceNothing() {
    var participant = new JobOrderItemReassignment(repository, audit, publisher);

    assertThat(participant.reassignGameItem(UUID.randomUUID(), UUID.randomUUID())).isZero();

    verifyNoInteractions(publisher, audit);
  }
}
