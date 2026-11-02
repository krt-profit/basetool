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

import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.privacy.api.events.AccountDeletionRequestedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Unit test for {@link DeletionRequestDashboardLiveSync}: the deletion request events reach it. */
@ExtendWith(MockitoExtension.class)
class DeletionRequestDashboardLiveSyncTest {

  @Mock private AdminDashboardLiveSync adminDashboardLiveSync;

  @Test
  void aFiledRequest_announcesTheDeletionRequestsSection() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AdminDashboardLiveSync.class, () -> adminDashboardLiveSync);
      context.register(DeletionRequestDashboardLiveSync.class);
      context.refresh();

      context.publishEvent(new AccountDeletionRequestedEvent(UUID.randomUUID(), "handle"));
    }

    verify(adminDashboardLiveSync).deletionRequestsChanged();
  }
}
