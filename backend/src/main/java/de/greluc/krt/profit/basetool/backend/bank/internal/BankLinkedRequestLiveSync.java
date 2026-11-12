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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Announces changes to linked Bank requests in the existing Bank rooms after commit. */
@Component
@RequiredArgsConstructor
class BankLinkedRequestLiveSync {

  private final LiveSyncPublisher publisher;

  /** Refreshes the staff queue and the member's scoped request views. */
  void changed() {
    publisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.BANK_STAFF), List.of("requestQueue"));
    publisher.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORGUNIT_BANK), List.of("orgUnitBank"));
  }
}
