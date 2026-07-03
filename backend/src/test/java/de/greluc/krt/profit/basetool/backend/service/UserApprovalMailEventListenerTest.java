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

package de.greluc.krt.profit.basetool.backend.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.event.UserApprovalDecidedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link UserApprovalMailEventListener}: it delegates to the mail service
 * and swallows any failure so the already-committed decision is never affected.
 */
@ExtendWith(MockitoExtension.class)
class UserApprovalMailEventListenerTest {

  @Mock private UserApprovalMailService userApprovalMailService;

  @InjectMocks private UserApprovalMailEventListener listener;

  @Test
  void delegatesToMailService() {
    UserApprovalDecidedEvent event =
        new UserApprovalDecidedEvent(UUID.randomUUID(), true, "a@example.test", "Name", null);

    listener.onUserApprovalDecided(event);

    verify(userApprovalMailService).sendDecisionMail(event);
  }

  @Test
  void swallowsMailServiceFailure() {
    UserApprovalDecidedEvent event =
        new UserApprovalDecidedEvent(UUID.randomUUID(), false, "a@example.test", "Name", "reason");
    doThrow(new RuntimeException("boom")).when(userApprovalMailService).sendDecisionMail(event);

    // Best-effort: a mail failure must not propagate out of the after-commit listener.
    listener.onUserApprovalDecided(event);

    verify(userApprovalMailService).sendDecisionMail(event);
  }
}
