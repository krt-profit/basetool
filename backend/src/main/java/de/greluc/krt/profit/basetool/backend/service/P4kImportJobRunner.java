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

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an enqueued {@link de.greluc.krt.profit.basetool.backend.model.P4kImportJob} on the
 * single-thread {@code @Async} import executor, off the request thread, so the upload returns
 * immediately and the admin page polls the job for progress. It only orchestrates: every state
 * change and the heavy parse-and-reconcile live behind transactional proxies ({@link
 * P4kImportJobService} and {@link P4kImportService}) and are invoked here cross-bean so those
 * proxies actually apply.
 *
 * <p>Flow per run: mark {@code RUNNING} (own transaction, so polling sees it at once), parse and
 * reconcile in {@link P4kImportService} (its own transaction; for APPLY an all-or-nothing
 * read-write one), then mark {@code SUCCEEDED} with the serialized result, or {@code FAILED} with
 * the reason if anything threw. The reconcile transaction is separate from the status writes so
 * that a rolled-back apply does not also revert the {@code RUNNING} / {@code FAILED} bookkeeping.
 * In the {@code finally} block an APPLY reclaims its (now-consumed) payload and every run prunes
 * the job history; housekeeping failures are logged, never allowed to mask the run's own outcome.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class P4kImportJobRunner {

  private final P4kImportJobService jobService;
  private final P4kImportService importService;
  private final MasterDataCacheEvictionService cacheEvictionService;
  private final ObjectMapper objectMapper;

  /**
   * Executes one import job asynchronously on the {@code importExecutor}. Never throws: every
   * failure is recorded on the job as {@link
   * de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus#FAILED FAILED}.
   *
   * @param jobId the (already-committed) job to run
   * @param kind whether to preview or apply
   * @param seedNew for an APPLY run, whether to seed brand-new unmatched rows
   */
  @Async(AsyncConfig.IMPORT_EXECUTOR)
  public void run(@NotNull UUID jobId, @NotNull P4kImportJobKind kind, boolean seedNew) {
    log.info("P4K import job {} ({}) starting.", jobId, kind);
    // Whether the APPLY reconcile transaction actually committed (its own @Transactional; a normal
    // return means committed). Gates the cache eviction below so it fires iff master data changed —
    // never for a PREVIEW and never for an apply that rolled back.
    boolean appliedMasterData = false;
    try {
      jobService.markRunning(jobId);
      byte[] bytes = jobService.loadPayload(jobId);
      P4kImportResultDto result;
      if (kind == P4kImportJobKind.APPLY) {
        result = importService.applyImport(bytes, seedNew);
        appliedMasterData = true;
      } else {
        result = importService.previewImport(bytes);
      }
      jobService.markSucceeded(jobId, objectMapper.writeValueAsString(result));
      log.info("P4K import job {} ({}) succeeded.", jobId, kind);
    } catch (Exception e) {
      log.warn("P4K import job {} ({}) failed: {}", jobId, kind, e.getMessage());
      jobService.markFailed(jobId, describe(e));
    } finally {
      if (kind == P4kImportJobKind.APPLY) {
        if (appliedMasterData) {
          // The apply runs on the import executor, outside the UEX / SC Wiki scheduler sweeps, so
          // it
          // has no @CacheEvict of its own — evict the master-data caches it rewrote (materials,
          // manufacturers, ship types, blueprint family index) so the changes are visible on the
          // next read instead of after the 12 h TTL (REQ-DATA-011, CACHE-SYNC-EVICT-001).
          safely(
              cacheEvictionService::evictP4kSyncedMasterData,
              "evict P4K-synced master-data caches");
        }
        // The apply ran against its own payload copy and is terminal — reclaim the bytes now.
        safely(() -> jobService.deletePayload(jobId), "delete payload");
      }
      safely(jobService::pruneOldJobs, "prune old jobs");
    }
  }

  /**
   * Renders a concise failure reason for the job row: a {@link BadRequestException} carries a
   * user-meaningful message (empty / invalid catalog), anything else is summarized by its type so
   * no stacktrace or internal detail leaks into the stored message.
   *
   * @param e the caught failure
   * @return the message to store on the job
   */
  @NotNull
  private String describe(@NotNull Exception e) {
    if (e instanceof BadRequestException) {
      return e.getMessage();
    }
    String simple = e.getClass().getSimpleName();
    return e.getMessage() == null ? simple : simple + ": " + e.getMessage();
  }

  /**
   * Runs a housekeeping action, swallowing (but logging) any failure so post-run cleanup can never
   * flip an otherwise-successful import into an error.
   *
   * @param action the cleanup action
   * @param what a short label for the log line
   */
  private void safely(@NotNull Runnable action, @NotNull String what) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn("P4K import housekeeping ({}) failed: {}", what, e.getMessage());
    }
  }
}
