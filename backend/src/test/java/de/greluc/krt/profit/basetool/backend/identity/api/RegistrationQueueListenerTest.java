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

package de.greluc.krt.profit.basetool.backend.identity.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationChangedEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link RegistrationQueueListener}: a write to an account on the queue publishes
 * the changed event for that account, a write to an active account publishes nothing.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationQueueListenerTest {

  @Mock private ApplicationEventPublisher eventPublisher;

  private RegistrationQueueListener listener;

  @BeforeEach
  void setUp() {
    listener = new RegistrationQueueListener(eventPublisher);
  }

  @ParameterizedTest
  @EnumSource(
      value = ApprovalStatus.class,
      names = {"PENDING", "REJECTED"})
  void aWrittenAccountOnTheQueue_publishesTheChangedEvent(ApprovalStatus status) {
    User user = account(status);

    listener.written(user);

    verify(eventPublisher).publishEvent(new DiscordRegistrationChangedEvent(user.getId()));
  }

  @Test
  void aWrittenActiveAccount_publishesNothing() {
    listener.written(account(ApprovalStatus.ACTIVE));

    verifyNoInteractions(eventPublisher);
  }

  private static User account(ApprovalStatus status) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setApprovalStatus(status);
    return user;
  }
}
