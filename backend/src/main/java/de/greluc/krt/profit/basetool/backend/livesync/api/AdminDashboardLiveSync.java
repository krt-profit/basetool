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

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Tells the open admin dashboards that the state behind one of their widgets changed (REQ-UI-034):
 * after the commit, one frame to the global {@link LiveSyncTopicClass#ADMIN_DASHBOARD} room naming
 * the widget's section, never for a rolled-back write.
 */
@Component
@RequiredArgsConstructor
public class AdminDashboardLiveSync {

  /** Section of the open deletion requests widget. */
  public static final String DELETION_REQUESTS = "deletion-requests";

  /** Section of the pending Discord registrations widget. */
  public static final String DISCORD_REGISTRATIONS = "discord-registrations";

  /** Section of the open terms of use widget. */
  public static final String TERMS = "terms";

  /** Section of the sync and import status widget. */
  public static final String SYNC_STATUS = "sync-status";

  private final LiveSyncPublisher publisher;

  /** Announces that an account deletion request was filed, decided or resolved. */
  public void deletionRequestsChanged() {
    announce(DELETION_REQUESTS);
  }

  /** Announces that a Discord registration was filed or decided. */
  public void registrationsChanged() {
    announce(DISCORD_REGISTRATIONS);
  }

  /** Announces that the set of members without accepted terms changed. */
  public void termsChanged() {
    announce(TERMS);
  }

  /** Announces that a synchronisation or import run finished. */
  public void syncStatusChanged() {
    announce(SYNC_STATUS);
  }

  /**
   * Announces one section after the commit.
   *
   * @param section the widget's section key
   */
  private void announce(@NotNull String section) {
    publisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ADMIN_DASHBOARD), List.of(section));
  }
}
