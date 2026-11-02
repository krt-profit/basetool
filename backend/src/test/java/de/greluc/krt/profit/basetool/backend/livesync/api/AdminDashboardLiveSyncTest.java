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

package de.greluc.krt.profit.basetool.backend.livesync.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link AdminDashboardLiveSync}: each change names its widget's section. */
@ExtendWith(MockitoExtension.class)
class AdminDashboardLiveSyncTest {

  private static final LiveSyncTopic ROOM =
      LiveSyncTopic.global(LiveSyncTopicClass.ADMIN_DASHBOARD);

  @Mock private LiveSyncPublisher publisher;

  @InjectMocks private AdminDashboardLiveSync liveSync;

  @Test
  void deletionRequestsChanged_announcesItsSectionAfterCommit() {
    liveSync.deletionRequestsChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("deletion-requests"));
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void registrationsChanged_announcesItsSectionAfterCommit() {
    liveSync.registrationsChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("discord-registrations"));
  }

  @Test
  void termsChanged_announcesItsSectionAfterCommit() {
    liveSync.termsChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("terms"));
  }

  @Test
  void syncStatusChanged_announcesItsSectionAfterCommit() {
    liveSync.syncStatusChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("sync-status"));
  }

  @Test
  void everySectionSurvivesTheRoomsWhitelist() {
    List<String> sections =
        List.of(
            AdminDashboardLiveSync.DELETION_REQUESTS,
            AdminDashboardLiveSync.DISCORD_REGISTRATIONS,
            AdminDashboardLiveSync.TERMS,
            AdminDashboardLiveSync.SYNC_STATUS);

    org.assertj.core.api.Assertions.assertThat(
            LiveSyncTopicClass.ADMIN_DASHBOARD.clipSections(sections))
        .isEqualTo(sections);
  }
}
