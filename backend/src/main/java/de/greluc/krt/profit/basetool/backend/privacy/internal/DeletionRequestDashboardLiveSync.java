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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.privacy.api.events.AccountDeletionRequestDeclinedEvent;
import de.greluc.krt.profit.basetool.backend.privacy.api.events.AccountDeletionRequestResolvedEvent;
import de.greluc.krt.profit.basetool.backend.privacy.api.events.AccountDeletionRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the open admin dashboards that the deletion requests changed: a request was filed, declined
 * or resolved (REQ-UI-034).
 */
@Component
@RequiredArgsConstructor
public class DeletionRequestDashboardLiveSync {

  private final AdminDashboardLiveSync adminDashboardLiveSync;

  /** Announces the change; the frame leaves once the publishing transaction has committed. */
  @EventListener({
    AccountDeletionRequestedEvent.class,
    AccountDeletionRequestDeclinedEvent.class,
    AccountDeletionRequestResolvedEvent.class
  })
  public void onDeletionRequestChanged() {
    adminDashboardLiveSync.deletionRequestsChanged();
  }
}
