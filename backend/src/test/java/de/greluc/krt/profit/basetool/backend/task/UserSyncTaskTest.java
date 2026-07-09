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

package de.greluc.krt.profit.basetool.backend.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.UserSyncService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UserSyncTask} — the scheduled trigger. The reconciliation logic itself is
 * exercised by {@code UserSyncServiceTest}; here we only pin that the scheduled entry point drives
 * {@link UserSyncService} through the failure-swallowing {@link TaskMetrics#recordCounting}
 * wrapper.
 */
@ExtendWith(MockitoExtension.class)
class UserSyncTaskTest {

  @Mock private UserSyncService userSyncService;

  // A real TaskMetrics (spied) so the wrapper genuinely runs the sync body; a mock would no-op it
  // and defeat the delegation assertion below.
  @Spy private TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

  @InjectMocks private UserSyncTask userSyncTask;

  @Test
  void syncUsers_delegatesToTheServiceThroughTaskMetrics() {
    when(userSyncService.syncFromKeycloak()).thenReturn(3);

    userSyncTask.syncUsers();

    verify(taskMetrics).recordCounting(eq(ScheduledJob.USER_SYNC), any());
    verify(userSyncService).syncFromKeycloak();
  }

  @Test
  void syncUsers_swallowsAServiceFailureSoTheSchedulerThreadSurvives() {
    when(userSyncService.syncFromKeycloak()).thenThrow(new RuntimeException("boom"));

    // recordCounting's catch-record-swallow contract must keep the scheduled trigger from throwing.
    assertDoesNotThrow(userSyncTask::syncUsers);

    verify(userSyncService).syncFromKeycloak();
  }
}
