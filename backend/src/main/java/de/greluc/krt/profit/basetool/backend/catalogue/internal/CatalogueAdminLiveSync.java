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

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Tells the open admin starmap, shops and P4K import pages that what they list changed (REQ-FE-010,
 * REQ-FE-015): after the commit, one frame to the global {@link LiveSyncTopicClass#CATALOGUE_ADMIN}
 * room naming the page's section, never for a rolled-back write.
 */
@Component
@RequiredArgsConstructor
public class CatalogueAdminLiveSync {

  /** Section of the admin starmap page: the objects, their visibility, storage places and links. */
  public static final String STARMAP = "starmap";

  /** Section of the admin shops page: the shops and their terminal links. */
  public static final String SHOPS = "shops";

  /** Section of the P4K import page: the applied runs and the import-due banner. */
  public static final String RUNS = "runs";

  /** Section of the P4K import page: the open review cases. */
  public static final String REVIEW = "review";

  private final LiveSyncPublisher publisher;

  /** Announces that a starmap link, an object's visibility or a storage place on a node changed. */
  public void starmapChanged() {
    announce(List.of(STARMAP));
  }

  /** Announces that a shop's terminal link changed. */
  public void shopsChanged() {
    announce(List.of(SHOPS));
  }

  /** Announces that a review case was resolved. */
  public void reviewChanged() {
    announce(List.of(REVIEW));
  }

  /**
   * Announces that an APPLY ran: it may have changed the starmap tree, its links and the shop
   * links, it adds a run and it opens and closes review cases.
   */
  public void applied() {
    announce(List.of(STARMAP, SHOPS, RUNS, REVIEW));
  }

  /**
   * Announces sections after the commit.
   *
   * @param sections the page sections
   */
  private void announce(@NotNull List<String> sections) {
    publisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE_ADMIN), sections);
  }
}
