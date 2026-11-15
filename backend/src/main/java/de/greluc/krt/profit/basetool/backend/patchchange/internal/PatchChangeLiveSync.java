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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells every open patch-change page that the history changed, once the change has committed
 * (REQ-PATCH-008).
 */
@Component
@RequiredArgsConstructor
class PatchChangeLiveSync {

  /** The section of the patch-change room that holds the history and the form's previous values. */
  static final String HISTORY = "history";

  private final LiveSyncPublisher publisher;

  /** Refreshes the history of every open patch-change page after the commit. */
  void historyChanged() {
    publisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.PATCH_CHANGE), List.of(HISTORY));
  }
}
