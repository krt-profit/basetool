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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationChangedEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationDecidedEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the open admin dashboards and registration queues that the Discord registrations or the
 * members without accepted terms changed (REQ-UI-034, REQ-SEC-090): a registration was filed,
 * changed or decided, and the terms version in force may have changed with this start.
 */
@Component
@RequiredArgsConstructor
public class RegistrationDashboardLiveSync {

  private final AdminDashboardLiveSync adminDashboardLiveSync;

  /** Announces a newly filed registration. */
  @EventListener(DiscordRegistrationPendingEvent.class)
  public void onRegistrationFiled() {
    adminDashboardLiveSync.registrationsChanged();
  }

  /** Announces a written account on the queue, so the queue echoes its current version. */
  @EventListener(DiscordRegistrationChangedEvent.class)
  public void onRegistrationChanged() {
    adminDashboardLiveSync.registrationsChanged();
  }

  /** Announces a decided registration; an approved member may still owe the terms. */
  @EventListener(DiscordRegistrationDecidedEvent.class)
  public void onRegistrationDecided() {
    adminDashboardLiveSync.registrationsChanged();
    adminDashboardLiveSync.termsChanged();
  }

  /** Announces that a new terms version may now be in force, as every member owes it again. */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    adminDashboardLiveSync.termsChanged();
  }
}
