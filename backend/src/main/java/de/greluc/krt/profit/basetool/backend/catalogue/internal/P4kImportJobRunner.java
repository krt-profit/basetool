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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.platform.api.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an enqueued {@link de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob} on the
 * single-thread {@code @Async} import executor, delegating every state change and the
 * parse-and-reconcile work to the transactional {@link P4kImportJobService} and {@link
 * P4kImportService}.
 *
 * <p>An APPLY holds the {@link SyncCoordinator} lock that the Wiki and UEX syncs share; PREVIEW
 * writes nothing and takes no lock.
 *
 * <p>Each run marks the job {@code RUNNING}, reconciles in a separate transaction, then marks it
 * {@code SUCCEEDED} or {@code FAILED}. Afterwards an APPLY reclaims its payload and every run
 * prunes the job history; housekeeping failures are only logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class P4kImportJobRunner {

  /** The holder name an APPLY shows in the {@link SyncCoordinator} wait and skip log lines. */
  static final String APPLY_LOCK_LABEL = "P4K APPLY";

  private final P4kImportJobService jobService;
  private final P4kImportService importService;
  private final P4kPreviewReportCodec reportCodec;
  private final MasterDataCacheEvictionService cacheEvictionService;
  private final ObjectMapper objectMapper;
  private final AuthHelperService authHelperService;
  private final LiveSyncPublisher catalogueUpdates;
  private final SyncCoordinator syncCoordinator;

  /**
   * Executes one import job asynchronously on the {@code importExecutor}. Never throws: every
   * failure is recorded on the job as {@link
   * de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobStatus#FAILED FAILED}.
   *
   * @param jobId the (already-committed) job to run; an APPLY job carries the admin's choices
   * @param kind whether to preview or apply
   * @param admin the authentication of the administrator who enqueued the job; the run acts under
   *     it, so its audit events name that administrator
   */
  @Async(AsyncConfig.IMPORT_EXECUTOR)
  public void run(
      @NotNull UUID jobId, @NotNull P4kImportJobKind kind, @NotNull Authentication admin) {
    authHelperService.runAs(admin, () -> execute(jobId, kind));
  }

  /**
   * Runs one import job on the current thread; never throws.
   *
   * @param jobId the (already-committed) job to run
   * @param kind whether to preview or apply
   */
  private void execute(@NotNull UUID jobId, @NotNull P4kImportJobKind kind) {
    log.info("P4K import job {} ({}) starting.", jobId, kind);
    boolean appliedMasterData = false;
    boolean changedCatalogue = false;
    try {
      jobService.markRunning(jobId);
      byte[] bytes = jobService.loadPayload(jobId);
      P4kImportResultDto result;
      if (kind == P4kImportJobKind.APPLY) {
        P4kImportJob job = jobService.getJob(jobId);
        result =
            applyExclusively(
                bytes,
                new P4kImportService.P4kApplyRequest(
                    jobId,
                    job.getCreatedBy(),
                    job.isCreateItemsAndShips(),
                    job.isOverrideMassGuard()));
        appliedMasterData = true;
        changedCatalogue = hasChanges(result);
      } else {
        P4kImportService.P4kPreview preview = importService.previewImport(bytes);
        jobService.storePreviewReport(
            jobId,
            reportCodec.encode(preview.changes().stream().map(P4kImportChangeDto::of).toList()));
        result = preview.result();
      }
      jobService.markSucceeded(jobId, objectMapper.writeValueAsString(result));
      log.info("P4K import job {} ({}) succeeded.", jobId, kind);
    } catch (BadRequestException e) {
      log.warn("P4K import job {} ({}) failed: {}", jobId, kind, e.getMessage());
      jobService.markFailed(jobId, describe(e));
    } catch (Exception e) {
      log.warn("P4K import job {} ({}) failed", jobId, kind, e);
      jobService.markFailed(jobId, describe(e));
    } finally {
      if (kind == P4kImportJobKind.APPLY) {
        if (appliedMasterData) {
          safely(
              cacheEvictionService::evictP4kSyncedMasterData,
              "evict P4K-synced master-data caches");
        }
        if (changedCatalogue) {
          safely(
              () ->
                  catalogueUpdates.publishAfterCommit(
                      LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE), List.of("catalogue")),
              "publish catalogue change");
        }
        safely(() -> jobService.deletePayload(jobId), "delete payload");
      }
      safely(jobService::pruneOldJobs, "prune old jobs");
    }
  }

  /**
   * Applies the catalogue under the {@link SyncCoordinator} lock, so an APPLY never runs beside a
   * Wiki or UEX sync and its duplicate merges.
   *
   * @param bytes the payload
   * @param request the APPLY parameters
   * @return the result of the APPLY
   * @throws SyncLockUnavailableException if a sync held the lock past the coordinator's wait cap
   */
  @NotNull
  private P4kImportResultDto applyExclusively(
      byte @NotNull [] bytes, @NotNull P4kImportService.P4kApplyRequest request) {
    AtomicReference<P4kImportResultDto> applied = new AtomicReference<>();
    boolean ran =
        syncCoordinator.runExclusively(
            APPLY_LOCK_LABEL, () -> applied.set(importService.applyImport(bytes, request)));
    P4kImportResultDto result = applied.get();
    if (!ran || result == null) {
      throw new SyncLockUnavailableException();
    }
    return result;
  }

  /** The APPLY could not take the catalogue sync lock within the coordinator's wait cap. */
  static final class SyncLockUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception with the message stored on the failed job. */
    SyncLockUnavailableException() {
      super(
          "A Wiki or UEX sync held the catalogue lock past the wait limit; the APPLY did not run."
              + " Start it again later.");
    }
  }

  private static boolean hasChanges(P4kImportResultDto result) {
    return result.ingredientsResolved() > 0
        || hasChanges(result.manufacturers())
        || hasChanges(result.items())
        || hasChanges(result.ships())
        || hasChanges(result.commodities())
        || hasChanges(result.blueprints())
        || hasChanges(result.starmapObjects())
        || hasChanges(result.shops());
  }

  private static boolean hasChanges(P4kImportResultDto.@Nullable Counts counts) {
    return counts != null && (counts.created() > 0 || counts.enriched() > 0);
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
    String message = e.getMessage();
    if ((e instanceof BadRequestException || e instanceof SyncLockUnavailableException)
        && message != null) {
      return message;
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
