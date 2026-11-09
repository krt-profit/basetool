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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The after-commit announcements an exchange write raises (REQ-XCH-013). */
@ExtendWith(MockitoExtension.class)
class ExchangeLiveSyncTest {

  private static final UUID MEMBER = UUID.fromString("0b7a3c6e-2f1d-4e8a-9c3b-5d6e7f809a1b");

  @Mock private LiveSyncPublisher publisher;
  @InjectMocks private ExchangeLiveSync liveSync;

  @Test
  void aShipWriteRefreshesOnlyTheMembersOwnHangar() {
    liveSync.hangarChanged(MEMBER);

    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, MEMBER)), eq(List.of("ships")));
  }

  @Test
  void aStockWriteRefreshesTheLagerAndTheBoardOnlyWhenOffersChanged() {
    liveSync.stockChanged(false);
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL)), eq(List.of("stock")));
    verify(publisher, never())
        .publishAfterCommit(eq(LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD)), anyList());

    liveSync.stockChanged(true);
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD)), eq(List.of("board")));
  }

  @Test
  void aRefineryWriteRefreshesTheListEachWrittenOrderTheStoredOnesStoreAndTellsRemovedOnes() {
    UUID written = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    UUID stored = UUID.fromString("2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e");
    UUID removed = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e1f-2a3b4c5d6e7f");

    liveSync.refineryOrdersChanged(List.of(written, stored), List.of(stored), List.of(removed));

    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.global(LiveSyncTopicClass.REFINERY)), eq(List.of("queue")));
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.REFINERY_ORDER, written)), eq(List.of("order")));
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.REFINERY_ORDER, stored)), eq(List.of("store")));
    verify(publisher, never())
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.REFINERY_ORDER, written)), eq(List.of("store")));
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.REFINERY_ORDER, removed)),
            eq(List.of("deleted")));
  }

  @Test
  void aRefineryWriteThatChangedNothingAnnouncesNothing() {
    liveSync.refineryOrdersChanged(List.of(), List.of(), List.of());

    verifyNoInteractions(publisher);
  }

  @Test
  void aBankRequestWriteRefreshesTheStaffQueueAndTheOrgUnitBankAsTheWebFormsDo() {
    liveSync.bankRequestsChanged();

    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.global(LiveSyncTopicClass.BANK_STAFF)), eq(List.of("requestQueue")));
    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.global(LiveSyncTopicClass.ORGUNIT_BANK)), eq(List.of("orgUnitBank")));
  }

  @Test
  void aBlueprintWriteRefreshesOnlyTheMembersOwnBlueprints() {
    liveSync.blueprintsChanged(MEMBER);

    verify(publisher)
        .publishAfterCommit(
            eq(LiveSyncTopic.of(LiveSyncTopicClass.BLUEPRINTS_OWN, MEMBER)), eq(List.of("list")));
  }
}
