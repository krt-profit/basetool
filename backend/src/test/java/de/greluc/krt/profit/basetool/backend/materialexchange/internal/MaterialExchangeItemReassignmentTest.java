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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
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

/** The Materialbörse's part of a catalogue merge: moved entries, their audit and the board sync. */
@ExtendWith(MockitoExtension.class)
class MaterialExchangeItemReassignmentTest {
  @Mock private MaterialExchangeItemReassignmentRepository repository;
  @Mock private AuditRecorder audit;
  @Mock private LiveSyncPublisher publisher;

  @Test
  void movedEntriesAreAuditedEachAndRefreshTheBoardAfterCommit() {
    UUID retired = UUID.randomUUID();
    UUID survivor = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    when(repository.offers(retired, survivor)).thenReturn(List.of(offer));
    when(repository.requests(retired, survivor)).thenReturn(List.of(request));
    var participant = new MaterialExchangeItemReassignment(repository, audit, publisher);

    assertThat(participant.reassignGameItem(retired, survivor)).isEqualTo(2);

    verify(audit)
        .record(
            AuditEventType.MARKET_CATALOGUE_REASSIGNED,
            offer,
            null,
            null,
            "retiredItem="
                + retired
                + ";survivorItem="
                + survivor
                + ";subject=material_exchange_offer");
    verify(audit)
        .record(
            AuditEventType.MARKET_CATALOGUE_REASSIGNED,
            request,
            null,
            null,
            "retiredItem="
                + retired
                + ";survivorItem="
                + survivor
                + ";subject=material_exchange_request");
    verify(publisher)
        .publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
  }

  @Test
  void unchangedEntriesAnnounceNothing() {
    var participant = new MaterialExchangeItemReassignment(repository, audit, publisher);

    assertThat(participant.reassignGameItem(UUID.randomUUID(), UUID.randomUUID())).isZero();

    verifyNoInteractions(publisher, audit);
  }
}
