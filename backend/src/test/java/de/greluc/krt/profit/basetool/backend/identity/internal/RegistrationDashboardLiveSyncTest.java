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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationDecidedEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Unit tests for {@link RegistrationDashboardLiveSync}: each registration event, and the start of
 * the application, reach the sections they change.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationDashboardLiveSyncTest {

  @Mock private AdminDashboardLiveSync adminDashboardLiveSync;

  @Test
  void aFiledRegistration_announcesOnlyTheRegistrationsSection() {
    publish(new DiscordRegistrationPendingEvent(UUID.randomUUID(), "user"));

    verify(adminDashboardLiveSync).registrationsChanged();
    verifyNoMoreInteractions(adminDashboardLiveSync);
  }

  @Test
  void aDecidedRegistration_announcesTheRegistrationsAndTheTermsSection() {
    publish(new DiscordRegistrationDecidedEvent(UUID.randomUUID(), UUID.randomUUID()));

    verify(adminDashboardLiveSync).registrationsChanged();
    verify(adminDashboardLiveSync).termsChanged();
  }

  @Test
  void theStartOfTheApplication_announcesTheTermsSection() {
    try (AnnotationConfigApplicationContext context = context()) {
      context.publishEvent(
          new ApplicationReadyEvent(
              new org.springframework.boot.SpringApplication(),
              new String[0],
              context,
              java.time.Duration.ZERO));
    }

    verify(adminDashboardLiveSync).termsChanged();
  }

  private void publish(Object event) {
    try (AnnotationConfigApplicationContext context = context()) {
      context.publishEvent(event);
    }
  }

  private AnnotationConfigApplicationContext context() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(AdminDashboardLiveSync.class, () -> adminDashboardLiveSync);
    context.register(RegistrationDashboardLiveSync.class);
    context.refresh();
    return context;
  }
}
