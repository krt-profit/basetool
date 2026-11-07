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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kAcceptedUpload;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobPayload;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobPayloadRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link P4kImportJobService} with mocked repositories and a real {@link
 * SimpleMeterRegistry}: job creation and its guards, lifecycle transitions with the outcome
 * counter, payload reclaim, pruning and the startup orphan reconciliation.
 */
@ExtendWith(MockitoExtension.class)
class P4kImportJobServiceTest {

  @Mock private P4kImportJobRepository jobRepository;
  @Mock private P4kImportJobPayloadRepository payloadRepository;
  @Mock private AdminDashboardLiveSync adminDashboardLiveSync;
  @Mock private AuditRecorder auditRecorder;
  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private P4kImportJobService service;

  private static final P4kBuild BUILD =
      new P4kBuild("sc-alpha-4.3.2", "4.3.2-LIVE.9876543", "9876543", "b-1", null);

  /**
   * Reads the current value of the terminal-outcome counter for a bounded {@code outcome} / {@code
   * kind} pair, or {@code 0} when that series has not been registered yet.
   *
   * @param outcome the terminal outcome tag ({@code succeeded} / {@code failed})
   * @param kind the import job kind tag ({@code PREVIEW} / {@code APPLY})
   * @return the counter value, or {@code 0.0} when no matching series exists
   */
  private double terminalCount(String outcome, String kind) {
    var counter =
        meterRegistry
            .find(CatalogueMetricNames.P4K_IMPORT_JOBS)
            .tags(MetricNames.TAG_OUTCOME, outcome, MetricNames.TAG_KIND, kind)
            .counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Stubs {@code save} to assign a random id (mimicking the JPA UUID generator) and echo the job.
   */
  private void stubSaveAssignsId() {
    when(jobRepository.save(any(P4kImportJob.class)))
        .thenAnswer(invocation -> {
          P4kImportJob job = invocation.getArgument(0);
          if (job.getId() == null) {
            job.setId(UUID.randomUUID());
          }
          return job;
        });
  }

  private P4kImportJob jobWithId(UUID id, P4kImportJobStatus status) {
    P4kImportJob job = new P4kImportJob();
    job.setId(id);
    job.setKind(P4kImportJobKind.PREVIEW);
    job.setStatus(status);
    return job;
  }

  @Test
  void createPreviewJob_persistsPendingJobAndPayload() {
    stubSaveAssignsId();
    byte[] bytes = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
    UUID user = UUID.randomUUID();

    P4kImportJob job =
        service.createPreviewJob(new P4kAcceptedUpload(bytes, 9000L, BUILD), "p4k.json", user);

    assertEquals(P4kImportJobKind.PREVIEW, job.getKind());
    assertEquals(P4kImportJobStatus.PENDING, job.getStatus());
    assertEquals(user, job.getCreatedBy());
    assertEquals("p4k.json", job.getSourceFilename());
    assertEquals(9000L, job.getFileSizeBytes());
    assertNotNull(job.getId());

    ArgumentCaptor<P4kImportJobPayload> payload =
        ArgumentCaptor.forClass(P4kImportJobPayload.class);
    verify(payloadRepository).save(payload.capture());
    assertEquals(job.getId(), payload.getValue().getJobId());
    assertArrayEquals(bytes, payload.getValue().getContent());
  }

  @Test
  void createPreviewJob_recordsOneCatalogueEventWithTheJobAndItsBuild() {
    stubSaveAssignsId();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);

    P4kImportJob job =
        service.createPreviewJob(
            new P4kAcceptedUpload(bytes, 2L, BUILD), "p4k.json", UUID.randomUUID());

    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_P4K_PREVIEW_ENQUEUED),
            eq(job.getId()),
            eq("4.3.2-LIVE.9876543"),
            isNull(),
            argThat(details ->
                details != null
                    && "branch=sc-alpha-4.3.2 change=9876543".equals(details.toString())));
  }

  @Test
  void createApplyJob_fromSucceededPreview_copiesPayloadAndLinksPreview() {
    stubSaveAssignsId();
    UUID previewId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    P4kImportJob preview = jobWithId(previewId, P4kImportJobStatus.SUCCEEDED);
    preview.setSourceFilename("p4k.json");
    preview.setFileSizeBytes(4096L);
    when(jobRepository.findById(previewId)).thenReturn(Optional.of(preview));
    when(payloadRepository.copyPayload(any(UUID.class), eq(previewId))).thenReturn(1);

    P4kImportJob apply = service.createApplyJob(previewId, true, true, user);

    assertEquals(P4kImportJobKind.APPLY, apply.getKind());
    assertEquals(P4kImportJobStatus.PENDING, apply.getStatus());
    assertTrue(apply.isCreateItemsAndShips());
    assertTrue(apply.isOverrideMassGuard());
    assertEquals(previewId, apply.getPreviewJobId());
    assertEquals("p4k.json", apply.getSourceFilename());
    assertEquals(4096L, apply.getFileSizeBytes());
    verify(payloadRepository).copyPayload(apply.getId(), previewId);
  }

  @Test
  void createApplyJob_unknownJob_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(jobRepository.findById(id)).thenReturn(Optional.empty());
    assertThrows(
        NotFoundException.class, () -> service.createApplyJob(id, false, false, UUID.randomUUID()));
    verify(payloadRepository, never()).copyPayload(any(), any());
  }

  @Test
  void createApplyJob_referencingAnApplyJob_throwsBadRequest() {
    UUID id = UUID.randomUUID();
    P4kImportJob applyJob = jobWithId(id, P4kImportJobStatus.SUCCEEDED);
    applyJob.setKind(P4kImportJobKind.APPLY);
    when(jobRepository.findById(id)).thenReturn(Optional.of(applyJob));
    assertThrows(
        BadRequestException.class,
        () -> service.createApplyJob(id, false, false, UUID.randomUUID()));
  }

  @Test
  void createApplyJob_previewNotYetSucceeded_throwsBadRequest() {
    UUID id = UUID.randomUUID();
    when(jobRepository.findById(id))
        .thenReturn(Optional.of(jobWithId(id, P4kImportJobStatus.RUNNING)));
    assertThrows(
        BadRequestException.class,
        () -> service.createApplyJob(id, false, false, UUID.randomUUID()));
  }

  @Test
  void createApplyJob_payloadAlreadyGone_throwsBadRequest() {
    stubSaveAssignsId();
    UUID previewId = UUID.randomUUID();
    when(jobRepository.findById(previewId))
        .thenReturn(Optional.of(jobWithId(previewId, P4kImportJobStatus.SUCCEEDED)));
    when(payloadRepository.copyPayload(any(UUID.class), eq(previewId))).thenReturn(0);
    assertThrows(
        BadRequestException.class,
        () -> service.createApplyJob(previewId, false, false, UUID.randomUUID()));
  }

  @Test
  void markRunning_setsStatusAndStartedAt() {
    UUID id = UUID.randomUUID();
    P4kImportJob job = jobWithId(id, P4kImportJobStatus.PENDING);
    when(jobRepository.findById(id)).thenReturn(Optional.of(job));

    service.markRunning(id);

    assertEquals(P4kImportJobStatus.RUNNING, job.getStatus());
    assertNotNull(job.getStartedAt());
  }

  @Test
  void markSucceeded_storesResultAndFinishedAt() {
    UUID id = UUID.randomUUID();
    P4kImportJob job = jobWithId(id, P4kImportJobStatus.RUNNING);
    when(jobRepository.findById(id)).thenReturn(Optional.of(job));

    service.markSucceeded(id, "{\"dryRun\":true}");

    assertEquals(P4kImportJobStatus.SUCCEEDED, job.getStatus());
    assertEquals("{\"dryRun\":true}", job.getResultJson());
    assertNotNull(job.getFinishedAt());
    assertNull(job.getErrorMessage());
    assertEquals(
        1.0, terminalCount(MetricNames.OUTCOME_SUCCEEDED, P4kImportJobKind.PREVIEW.name()));
    assertEquals(0.0, terminalCount(MetricNames.OUTCOME_FAILED, P4kImportJobKind.PREVIEW.name()));
    verify(adminDashboardLiveSync).syncStatusChanged();
  }

  @Test
  void markFailed_storesErrorAndFinishedAt() {
    UUID id = UUID.randomUUID();
    P4kImportJob job = jobWithId(id, P4kImportJobStatus.RUNNING);
    when(jobRepository.findById(id)).thenReturn(Optional.of(job));

    service.markFailed(id, "boom");

    assertEquals(P4kImportJobStatus.FAILED, job.getStatus());
    assertEquals("boom", job.getErrorMessage());
    assertNotNull(job.getFinishedAt());
    assertNull(job.getResultJson());
    assertEquals(1.0, terminalCount(MetricNames.OUTCOME_FAILED, P4kImportJobKind.PREVIEW.name()));
    assertEquals(
        0.0, terminalCount(MetricNames.OUTCOME_SUCCEEDED, P4kImportJobKind.PREVIEW.name()));
    verify(adminDashboardLiveSync).syncStatusChanged();
  }

  @Test
  void deletePayload_deletesTheRowWithoutLoadingTheUpload() {
    UUID id = UUID.randomUUID();
    service.deletePayload(id);
    verify(payloadRepository).deleteByJobId(id);
    verify(payloadRepository, never()).findById(any());
  }

  @Test
  void thePreviewReport_isStoredAndReadWithoutLoadingTheUpload() {
    UUID id = UUID.randomUUID();
    byte[] report = {1, 2, 3};
    when(payloadRepository.storePreviewReport(id, report)).thenReturn(1);
    when(payloadRepository.findPreviewReport(id)).thenReturn(Optional.of(report));

    service.storePreviewReport(id, report);

    assertArrayEquals(report, service.loadPreviewReport(id).orElseThrow());
    verify(payloadRepository, never()).findById(any());
  }

  @Test
  void storingAPreviewReport_withoutAPayload_isAnError() {
    UUID id = UUID.randomUUID();
    when(payloadRepository.storePreviewReport(eq(id), any())).thenReturn(0);

    assertThrows(IllegalStateException.class, () -> service.storePreviewReport(id, new byte[] {1}));
  }

  @Test
  void pruneOldJobs_delegatesToRepository() {
    when(jobRepository.deleteByCreatedAtBefore(any())).thenReturn(3);
    assertEquals(3, service.pruneOldJobs());
  }

  @Test
  void failOrphanedJobs_flipsPendingAndRunningToFailed() {
    P4kImportJob pending = jobWithId(UUID.randomUUID(), P4kImportJobStatus.PENDING);
    P4kImportJob running = jobWithId(UUID.randomUUID(), P4kImportJobStatus.RUNNING);
    running.setKind(P4kImportJobKind.APPLY);
    when(jobRepository.findByStatusIn(any())).thenReturn(List.of(pending, running));

    service.failOrphanedJobs();

    assertEquals(P4kImportJobStatus.FAILED, pending.getStatus());
    assertEquals(P4kImportJobStatus.FAILED, running.getStatus());
    assertNotNull(pending.getFinishedAt());
    assertNotNull(running.getErrorMessage());
    assertEquals(1.0, terminalCount(MetricNames.OUTCOME_FAILED, P4kImportJobKind.PREVIEW.name()));
    assertEquals(1.0, terminalCount(MetricNames.OUTCOME_FAILED, P4kImportJobKind.APPLY.name()));
  }

  @Test
  void failOrphanedJobs_withNoOrphans_doesNothing() {
    when(jobRepository.findByStatusIn(any())).thenReturn(List.of());
    service.failOrphanedJobs();
    verify(jobRepository).findByStatusIn(any());
  }
}
