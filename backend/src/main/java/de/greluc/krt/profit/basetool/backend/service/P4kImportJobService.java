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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobPayload;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.repository.P4kImportJobPayloadRepository;
import de.greluc.krt.profit.basetool.backend.repository.P4kImportJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle and persistence for asynchronous P4K catalog-import runs ({@link P4kImportJob} + {@link
 * P4kImportJobPayload}). It owns the transactional state machine — create, the {@code PENDING →
 * RUNNING → SUCCEEDED | FAILED} transitions, payload load/copy/delete, and the prune /
 * startup-reconcile housekeeping — while the heavy parse-and-reconcile work and the orchestration
 * across these transactions live in {@code P4kImportJobRunner} (which runs them on a single-thread
 * {@code @Async} executor, off the request path).
 *
 * <p>Each transition is its own short transaction so that polling sees {@code RUNNING} immediately
 * and a rolled-back apply (the reconciliation is all-or-nothing) never reverts the status write.
 * The uploaded catalog lives in the 1:1 payload side table; an APPLY run gets its own database-side
 * copy of the PREVIEW's payload so it is self-contained, and reclaims it once it finishes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class P4kImportJobService {

  /** How long finished / abandoned import jobs (and payloads) are kept before the prune sweep. */
  private static final Duration RETENTION = Duration.ofDays(7);

  /** Maximum stored length of the display filename (matches the {@code source_filename} column). */
  private static final int MAX_FILENAME_LENGTH = 255;

  private final P4kImportJobRepository jobRepository;
  private final P4kImportJobPayloadRepository payloadRepository;
  private final MeterRegistry meterRegistry;

  /**
   * Enqueues a new {@link P4kImportJobKind#PREVIEW PREVIEW} job in state {@link
   * P4kImportJobStatus#PENDING PENDING}, persisting the uploaded catalog into the payload side
   * table. The returned job is committed when this method returns, so the async worker (triggered
   * afterwards) is guaranteed to see the row.
   *
   * @param bytes the uploaded P4K catalog JSON
   * @param filename the original upload filename for display, or {@code null}
   * @param createdBy JWT {@code sub} of the enqueuing administrator
   * @return the persisted preview job
   * @throws BadRequestException if {@code bytes} is empty
   */
  @Transactional
  @NotNull
  public P4kImportJob createPreviewJob(
      byte[] bytes, @Nullable String filename, @NotNull UUID createdBy) {
    if (bytes == null || bytes.length == 0) {
      throw new BadRequestException("The uploaded file is empty.");
    }
    P4kImportJob job = new P4kImportJob();
    job.setKind(P4kImportJobKind.PREVIEW);
    job.setStatus(P4kImportJobStatus.PENDING);
    job.setSeedNew(false);
    job.setSourceFilename(trimFilename(filename));
    job.setFileSizeBytes((long) bytes.length);
    job.setCreatedBy(createdBy);
    jobRepository.save(job);

    P4kImportJobPayload payload = new P4kImportJobPayload();
    payload.setJobId(job.getId());
    payload.setContent(bytes);
    payloadRepository.save(payload);
    return job;
  }

  /**
   * Enqueues an {@link P4kImportJobKind#APPLY APPLY} job for a finished preview, copying that
   * preview's stored upload onto the new job (database-side, see {@link
   * P4kImportJobPayloadRepository#copyPayload}). The returned job is committed on return.
   *
   * @param previewJobId the SUCCEEDED preview job to apply
   * @param seedNew whether to seed brand-new unmatched player-facing rows
   * @param createdBy JWT {@code sub} of the enqueuing administrator
   * @return the persisted apply job
   * @throws NotFoundException if no job has the given id
   * @throws BadRequestException if the referenced job is not a finished preview, or its uploaded
   *     catalog is no longer available
   */
  @Transactional
  @NotNull
  public P4kImportJob createApplyJob(
      @NotNull UUID previewJobId, boolean seedNew, @NotNull UUID createdBy) {
    P4kImportJob preview =
        jobRepository
            .findById(previewJobId)
            .orElseThrow(
                () -> new NotFoundException("P4K import job " + previewJobId + " was not found."));
    if (preview.getKind() != P4kImportJobKind.PREVIEW) {
      throw new BadRequestException("Only a preview job can be applied.");
    }
    if (preview.getStatus() != P4kImportJobStatus.SUCCEEDED) {
      throw new BadRequestException("The preview has not finished successfully yet.");
    }

    P4kImportJob job = new P4kImportJob();
    job.setKind(P4kImportJobKind.APPLY);
    job.setStatus(P4kImportJobStatus.PENDING);
    job.setSeedNew(seedNew);
    job.setSourceFilename(preview.getSourceFilename());
    job.setFileSizeBytes(preview.getFileSizeBytes());
    job.setPreviewJobId(previewJobId);
    job.setCreatedBy(createdBy);
    jobRepository.save(job);

    if (payloadRepository.copyPayload(job.getId(), previewJobId) == 0) {
      throw new BadRequestException(
          "The uploaded catalog for this preview is no longer available; upload it again.");
    }
    return job;
  }

  /**
   * Returns the most recent import jobs (newest first) for the admin job list.
   *
   * @return up to 50 jobs, newest first
   */
  @Transactional(readOnly = true)
  @NotNull
  public List<P4kImportJob> recentJobs() {
    return jobRepository.findTop50ByOrderByCreatedAtDesc();
  }

  /**
   * Fetches a single import job by id.
   *
   * @param jobId the job id
   * @return the job
   * @throws NotFoundException if no job has the given id
   */
  @Transactional(readOnly = true)
  @NotNull
  public P4kImportJob getJob(@NotNull UUID jobId) {
    return jobRepository
        .findById(jobId)
        .orElseThrow(() -> new NotFoundException("P4K import job " + jobId + " was not found."));
  }

  /**
   * Loads the stored upload bytes for a job, for the worker to parse.
   *
   * @param jobId the job id
   * @return the uploaded catalog bytes
   * @throws IllegalStateException if the payload row is missing (pruned mid-run)
   */
  @Transactional(readOnly = true)
  @NotNull
  public byte[] loadPayload(@NotNull UUID jobId) {
    return payloadRepository
        .findById(jobId)
        .map(P4kImportJobPayload::getContent)
        .orElseThrow(
            () -> new IllegalStateException("Payload missing for P4K import job " + jobId));
  }

  /**
   * Advances a job to {@link P4kImportJobStatus#RUNNING RUNNING} and stamps {@code started_at}.
   *
   * @param jobId the job id
   */
  @Transactional
  public void markRunning(@NotNull UUID jobId) {
    P4kImportJob job = requireJob(jobId);
    job.setStatus(P4kImportJobStatus.RUNNING);
    job.setStartedAt(Instant.now());
  }

  /**
   * Advances a job to {@link P4kImportJobStatus#SUCCEEDED SUCCEEDED}, storing the serialized result
   * and stamping {@code finished_at}.
   *
   * @param jobId the job id
   * @param resultJson the serialized {@code P4kImportResultDto}
   */
  @Transactional
  public void markSucceeded(@NotNull UUID jobId, @NotNull String resultJson) {
    P4kImportJob job = requireJob(jobId);
    job.setStatus(P4kImportJobStatus.SUCCEEDED);
    job.setResultJson(resultJson);
    job.setFinishedAt(Instant.now());
    recordTerminalOutcome(job, MetricNames.OUTCOME_SUCCEEDED);
  }

  /**
   * Advances a job to {@link P4kImportJobStatus#FAILED FAILED}, storing the reason and stamping
   * {@code finished_at}.
   *
   * @param jobId the job id
   * @param message the human-readable failure reason
   */
  @Transactional
  public void markFailed(@NotNull UUID jobId, @NotNull String message) {
    P4kImportJob job = requireJob(jobId);
    job.setStatus(P4kImportJobStatus.FAILED);
    job.setErrorMessage(message);
    job.setFinishedAt(Instant.now());
    recordTerminalOutcome(job, MetricNames.OUTCOME_FAILED);
  }

  /**
   * Deletes a job's stored upload, reclaiming the bytes once they are no longer needed (an APPLY
   * run is terminal). A no-op when the payload is already gone.
   *
   * @param jobId the job id
   */
  @Transactional
  public void deletePayload(@NotNull UUID jobId) {
    if (payloadRepository.existsById(jobId)) {
      payloadRepository.deleteById(jobId);
    }
  }

  /**
   * Prunes jobs (and, by {@code ON DELETE CASCADE}, their payloads) older than the retention
   * window. Called after every run so the table (especially the multi-MB payloads) stays bounded.
   *
   * @return the number of job rows deleted
   */
  @Transactional
  public int pruneOldJobs() {
    return jobRepository.deleteByCreatedAtBefore(Instant.now().minus(RETENTION));
  }

  /**
   * Reconciles jobs left {@link P4kImportJobStatus#PENDING PENDING} / {@link
   * P4kImportJobStatus#RUNNING RUNNING} by a backend restart to {@link P4kImportJobStatus#FAILED
   * FAILED}. The single-thread import executor does not survive a restart, so such a job can never
   * make progress; flipping it lets the admin see it failed (and re-run) instead of a spinner that
   * never resolves. Runs once on {@link ApplicationReadyEvent}.
   */
  @Transactional
  @EventListener(ApplicationReadyEvent.class)
  public void failOrphanedJobs() {
    List<P4kImportJob> orphans =
        jobRepository.findByStatusIn(
            List.of(P4kImportJobStatus.PENDING, P4kImportJobStatus.RUNNING));
    if (orphans.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    for (P4kImportJob job : orphans) {
      job.setStatus(P4kImportJobStatus.FAILED);
      job.setErrorMessage("Interrupted by a backend restart; re-run the import.");
      job.setFinishedAt(now);
      recordTerminalOutcome(job, MetricNames.OUTCOME_FAILED);
    }
    log.warn("Reconciled {} orphaned P4K import job(s) to FAILED on startup.", orphans.size());
  }

  /**
   * Loads a managed job that is expected to exist (it was created and committed before the worker
   * started); a miss means it was pruned mid-run, which is a programming/infra error rather than a
   * client 404.
   *
   * @param jobId the job id
   * @return the managed job
   */
  @NotNull
  private P4kImportJob requireJob(@NotNull UUID jobId) {
    return jobRepository
        .findById(jobId)
        .orElseThrow(
            () -> new IllegalStateException("P4K import job " + jobId + " vanished mid-run."));
  }

  /**
   * Increments {@code basetool_p4k_import_jobs_total} for a job that just reached a terminal state,
   * tagged by the bounded terminal {@code outcome} and the job {@code kind}. This makes a
   * consistently-failing import observable — the pending-queue gauge alone drains back to zero and
   * looks healthy even while every run fails.
   *
   * @param job the job that reached its terminal state (source of the bounded {@code kind} tag)
   * @param outcome {@link MetricNames#OUTCOME_SUCCEEDED} or {@link MetricNames#OUTCOME_FAILED}
   */
  private void recordTerminalOutcome(@NotNull P4kImportJob job, @NotNull String outcome) {
    meterRegistry
        .counter(
            MetricNames.P4K_IMPORT_JOBS,
            MetricNames.TAG_OUTCOME,
            outcome,
            MetricNames.TAG_KIND,
            job.getKind().name())
        .increment();
  }

  /**
   * Trims a display filename to non-blank and at most {@link #MAX_FILENAME_LENGTH} characters.
   *
   * @param filename the raw filename, or {@code null}
   * @return the trimmed filename, or {@code null} when blank
   */
  @Nullable
  private static String trimFilename(@Nullable String filename) {
    if (filename == null) {
      return null;
    }
    String trimmed = filename.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > MAX_FILENAME_LENGTH
        ? trimmed.substring(0, MAX_FILENAME_LENGTH)
        : trimmed;
  }
}
