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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.platform.api.NameRetentionParticipant;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily name retention (REQ-DATA-047): every {@link NameRetentionParticipant} removes the names and
 * free texts of its records older than {@code app.privacy.name-retention.max-age}, and one {@link
 * AuditEventType#NAME_RETENTION_APPLIED} records the counts when anything changed.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.privacy.name-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class NameRetentionTask {

  private final List<NameRetentionParticipant> participants;
  private final NameRetentionProperties properties;
  private final AuditRecorder auditRecorder;
  private final TransactionTemplate transactionTemplate;
  private final TaskMetrics taskMetrics;

  /**
   * Runs one sweep, publishing the {@code name_retention} job metrics; a failure is recorded and
   * swallowed by {@link TaskMetrics}.
   */
  @Scheduled(fixedDelayString = "${app.privacy.name-retention.interval:PT24H}")
  public void removeExpiredNames() {
    taskMetrics.recordCounting(ScheduledJob.NAME_RETENTION, () -> sweep(Instant.now()));
  }

  /**
   * Lets every participant work, records the audit row for what changed and fails the run when an
   * area failed, after the other areas have finished.
   *
   * @param now the time the run starts; the cutoff lies {@code maxAge} before it
   * @return the number of rows changed
   * @throws IllegalStateException when at least one area failed
   */
  int sweep(@NotNull Instant now) {
    Instant cutoff = now.minus(properties.maxAge());
    Map<String, Integer> changed = new LinkedHashMap<>();
    List<String> failed = new ArrayList<>();
    for (NameRetentionParticipant participant : participants) {
      try {
        changed.put(participant.area(), participant.removeNamesBefore(cutoff));
      } catch (RuntimeException e) {
        log.warn("Name retention failed in area {}", participant.area(), e);
        failed.add(participant.area());
      }
    }
    int total = changed.values().stream().mapToInt(Integer::intValue).sum();
    if (total > 0) {
      transactionTemplate.executeWithoutResult(_ -> record(changed, cutoff));
    }
    log.info("Name retention: {} row(s) changed before {}.", total, cutoff);
    if (!failed.isEmpty()) {
      throw new IllegalStateException("Name retention failed in " + failed);
    }
    return total;
  }

  /**
   * Writes the audit row with the per-area counts and the cutoff, never a removed text.
   *
   * @param changed rows changed per area
   * @param cutoff the oldest event whose names were kept
   */
  private void record(@NotNull Map<String, Integer> changed, @NotNull Instant cutoff) {
    AuditDetails details = AuditDetails.of("cutoff", cutoff);
    for (Map.Entry<String, Integer> area : changed.entrySet()) {
      details = details.with(area.getKey(), area.getValue());
    }
    auditRecorder.record(AuditEventType.NAME_RETENTION_APPLIED, null, null, null, details);
  }

  /**
   * Publishes {@code basetool_scheduled_job_enabled{task="name_retention"} = 1}, so {@code
   * ScheduledJobStale} can tell a disabled sweep from one that never succeeded.
   */
  @PostConstruct
  void publishEnabledGauge() {
    taskMetrics.markEnabled(ScheduledJob.NAME_RETENTION);
  }
}
