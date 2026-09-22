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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.AuditRetentionService;
import de.greluc.krt.profit.basetool.backend.support.AuditRetentionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the scheduled audit-trail retention sweep (REQ-AUDIT-006). */
@ExtendWith(MockitoExtension.class)
class AuditRetentionTaskTest {

  private static final Duration MAX_AGE = Duration.ofDays(730);

  /** The sweep interval; irrelevant to these direct calls, but the properties record needs one. */
  private static final Duration INTERVAL = Duration.ofHours(24);

  @Mock private AuditRetentionService retentionService;

  private final TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

  private AuditRetentionTask task() {
    return new AuditRetentionTask(
        retentionService, taskMetrics, new AuditRetentionProperties(true, MAX_AGE, INTERVAL));
  }

  // covers REQ-AUDIT-006 — the cutoff is "now - max-age"
  @Test
  void purgesAuditRowsOlderThanTheCutoff() {
    when(retentionService.purgeOlderThan(any())).thenReturn(17);

    task().purgeExpiredAuditEvents();

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(retentionService).purgeOlderThan(cutoff.capture());
    assertThat(cutoff.getValue()).isBeforeOrEqualTo(Instant.now().minus(729, ChronoUnit.DAYS));
    assertThat(cutoff.getValue()).isAfter(Instant.now().minus(731, ChronoUnit.DAYS));
  }

  @Test
  void swallowsFailuresSoSchedulerSurvives() {
    when(retentionService.purgeOlderThan(any())).thenThrow(new RuntimeException("db down"));

    // Must not propagate.
    task().purgeExpiredAuditEvents();
  }
}
