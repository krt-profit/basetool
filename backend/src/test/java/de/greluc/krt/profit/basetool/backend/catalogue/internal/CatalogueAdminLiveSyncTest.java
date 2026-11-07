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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The admin starmap, shops and P4K import pages hear of a change through the admin catalogue room,
 * in the section of the page that lists it (REQ-FE-010, REQ-FE-015).
 */
class CatalogueAdminLiveSyncTest {

  private static final LiveSyncTopic ROOM =
      LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE_ADMIN);

  private LiveSyncPublisher publisher;
  private CatalogueAdminLiveSync liveSync;

  @BeforeEach
  void setUp() {
    publisher = mock(LiveSyncPublisher.class);
    liveSync = new CatalogueAdminLiveSync(publisher);
  }

  @Test
  void aStarmapChange_announcesTheStarmapSectionAfterCommit() {
    liveSync.starmapChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("starmap"));
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void aShopLinkChange_announcesTheShopsSectionAfterCommit() {
    liveSync.shopsChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("shops"));
  }

  @Test
  void anApply_announcesEveryAdminCatalogueSectionInOneFrame() {
    liveSync.applied();

    verify(publisher).publishAfterCommit(ROOM, List.of("starmap", "shops", "runs", "review"));
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void aReviewResolution_announcesTheReviewSectionAfterCommit() {
    liveSync.reviewChanged();

    verify(publisher).publishAfterCommit(ROOM, List.of("review"));
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void theRoomAdmitsExactlyThePageSections() {
    assertThat(LiveSyncTopicClass.CATALOGUE_ADMIN.allowedSections())
        .containsExactlyInAnyOrder(
            CatalogueAdminLiveSync.STARMAP,
            CatalogueAdminLiveSync.SHOPS,
            CatalogueAdminLiveSync.RUNS,
            CatalogueAdminLiveSync.REVIEW);
  }
}
