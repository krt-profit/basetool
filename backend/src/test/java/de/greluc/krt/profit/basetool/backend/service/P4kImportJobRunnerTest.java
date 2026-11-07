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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobRunner;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kPreviewReportCodec;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncCoordinator;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link P4kImportJobRunner} with mocked {@link P4kImportJobService} and {@link
 * P4kImportService} and a real {@link JsonMapper}: run order, payload reclaim for APPLY but not
 * PREVIEW, failures recorded rather than thrown, and housekeeping never masking the outcome.
 */
@ExtendWith(MockitoExtension.class)
class P4kImportJobRunnerTest {

  private static final long SYNC_WAIT_MILLIS = 100;

  @Mock private P4kImportJobService jobService;
  @Mock private P4kImportService importService;
  @Mock private MasterDataCacheEvictionService cacheEvictionService;
  @Mock private LiveSyncPublisher catalogueUpdates;

  private final AuthHelperService authHelperService = new AuthHelperService(roleHierarchy());

  /** A real coordinator whose short wait cap lets a blocked APPLY give up quickly. */
  private final SyncCoordinator syncCoordinator = new SyncCoordinator(SYNC_WAIT_MILLIS);

  private P4kImportJobRunner runner;

  /** The administrator who enqueued every job of these tests. */
  private static final Authentication ADMIN =
      new TestingAuthenticationToken("p4k-admin", null, "ROLE_ADMIN");

  /**
   * A role hierarchy without implications; these tests check no role.
   *
   * @return the hierarchy
   */
  private static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy("");
  }

  @BeforeEach
  void setUp() {
    runner =
        new P4kImportJobRunner(
            jobService,
            importService,
            new P4kPreviewReportCodec(JsonMapper.builder().build()),
            cacheEvictionService,
            JsonMapper.builder().build(),
            authHelperService,
            catalogueUpdates,
            syncCoordinator);
  }

  @Test
  void run_apply_holdsTheSyncLockWhileApplying() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    stubAdmin(id, false);
    AtomicBoolean syncCouldRun = new AtomicBoolean(true);
    when(importService.applyImport(eq(bytes), any(P4kImportService.P4kApplyRequest.class)))
        .thenAnswer(_ -> {
          syncCouldRun.set(onOtherThread(() -> syncCoordinator.runExclusively("UEX", () -> {})));
          return someResult();
        });

    runner.run(id, P4kImportJobKind.APPLY, ADMIN);

    assertThat(syncCouldRun).isFalse();
    verify(jobService).markSucceeded(eq(id), anyString());
  }

  @Test
  void run_apply_failsWithoutApplyingWhileASyncHoldsTheLock() throws Exception {
    UUID id = UUID.randomUUID();
    when(jobService.loadPayload(id)).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    stubAdmin(id, false);

    whileASyncHoldsTheLock(() -> runner.run(id, P4kImportJobKind.APPLY, ADMIN));

    verify(importService, never()).applyImport(any(), any());
    verify(jobService).markFailed(eq(id), contains("catalogue lock"));
    verify(cacheEvictionService, never()).evictP4kSyncedMasterData();
  }

  @Test
  void run_preview_takesNoSyncLock() throws Exception {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(somePreview());

    whileASyncHoldsTheLock(() -> runner.run(id, P4kImportJobKind.PREVIEW, ADMIN));

    verify(jobService).markSucceeded(eq(id), anyString());
  }

  /**
   * Runs {@code action} while another thread holds the sync lock as a Wiki sync would.
   *
   * @param action the action to run on this thread
   * @throws Exception if the holder thread fails or is interrupted
   */
  private void whileASyncHoldsTheLock(Runnable action) throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (ExecutorService holder = Executors.newSingleThreadExecutor()) {
      Future<Boolean> sync =
          holder.submit(() ->
              syncCoordinator.runExclusively(
                  "SC Wiki",
                  () -> {
                    held.countDown();
                    awaitQuietly(release);
                  }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        action.run();
      } finally {
        release.countDown();
      }
      assertThat(sync.get(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static boolean onOtherThread(Callable<Boolean> task) {
    try (ExecutorService other = Executors.newSingleThreadExecutor()) {
      return other.submit(task).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static P4kImportResultDto someResult() {
    P4kImportResultDto.Counts zero = new P4kImportResultDto.Counts(null, 0, 0, 0, 0, 0, 0, 0);
    return new P4kImportResultDto(
        true,
        true,
        null,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        0,
        UUID.randomUUID(),
        List.of());
  }

  private static P4kImportService.P4kPreview somePreview() {
    return new P4kImportService.P4kPreview(someResult(), List.of());
  }

  private UUID stubAdmin(UUID jobId, boolean createItemsAndShips) {
    UUID admin = UUID.randomUUID();
    P4kImportJob job = new P4kImportJob();
    job.setCreatedBy(admin);
    job.setCreateItemsAndShips(createItemsAndShips);
    when(jobService.getJob(jobId)).thenReturn(job);
    return admin;
  }

  @Test
  void run_preview_marksRunningThenPreviewsThenSucceeds_keepingPayload() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(somePreview());

    runner.run(id, P4kImportJobKind.PREVIEW, ADMIN);

    InOrder order = inOrder(jobService, importService);
    order.verify(jobService).markRunning(id);
    order.verify(importService).previewImport(bytes);
    order.verify(jobService).storePreviewReport(eq(id), any());
    order.verify(jobService).markSucceeded(eq(id), anyString());
    verify(importService, never()).applyImport(any(), any());
    verify(jobService, never()).deletePayload(any());
    verify(jobService, never()).markFailed(any(), anyString());
    verify(jobService).pruneOldJobs();
    verify(cacheEvictionService, never()).evictP4kSyncedMasterData();
  }

  @Test
  void run_apply_appliesThenSucceeds_andReclaimsPayload() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    UUID admin = stubAdmin(id, true);
    P4kImportService.P4kApplyRequest request =
        new P4kImportService.P4kApplyRequest(id, admin, true, false);
    when(importService.applyImport(bytes, request)).thenReturn(someResult());

    runner.run(id, P4kImportJobKind.APPLY, ADMIN);

    verify(importService).applyImport(bytes, request);
    verify(importService, never()).previewImport(any());
    verify(jobService).markSucceeded(eq(id), anyString());
    verify(jobService).deletePayload(id);
    verify(jobService).pruneOldJobs();
    verify(cacheEvictionService).evictP4kSyncedMasterData();
  }

  @Test
  void run_apply_actsUnderTheEnqueuingAdmin_andRestoresTheThreadAfterwards() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    UUID admin = stubAdmin(id, false);
    AtomicReference<Authentication> actingAs = new AtomicReference<>();
    when(importService.applyImport(
            bytes, new P4kImportService.P4kApplyRequest(id, admin, false, false)))
        .thenAnswer(_ -> {
          actingAs.set(authHelperService.currentAuthentication().orElse(null));
          return someResult();
        });

    runner.run(id, P4kImportJobKind.APPLY, ADMIN);

    assertThat(actingAs.get()).isSameAs(ADMIN);
    assertThat(authHelperService.currentAuthentication()).isEmpty();
  }

  @Test
  void run_applyFailure_recordsFailed_andDoesNotEvictCaches() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    UUID admin = stubAdmin(id, false);
    when(importService.applyImport(
            bytes, new P4kImportService.P4kApplyRequest(id, admin, false, false)))
        .thenThrow(new BadRequestException("empty catalog"));

    runner.run(id, P4kImportJobKind.APPLY, ADMIN);

    verify(jobService).markFailed(id, "empty catalog");
    verify(jobService).deletePayload(id);
    verify(cacheEvictionService, never()).evictP4kSyncedMasterData();
  }

  @Test
  void run_failure_recordsFailedWithMessage_andStillPrunes() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{ bad".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenThrow(new BadRequestException("not valid JSON"));

    runner.run(id, P4kImportJobKind.PREVIEW, ADMIN);

    verify(jobService).markFailed(id, "not valid JSON");
    verify(jobService, never()).markSucceeded(any(), anyString());
    verify(jobService).pruneOldJobs();
  }

  @Test
  void run_housekeepingFailure_doesNotPropagate() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(somePreview());
    doThrow(new RuntimeException("db down")).when(jobService).pruneOldJobs();

    assertDoesNotThrow(() -> runner.run(id, P4kImportJobKind.PREVIEW, ADMIN));
    verify(jobService).markSucceeded(eq(id), anyString());
  }

  @Test
  void applyPublishesCatalogueChangeOnlyAfterCommittedDataChanges() {
    UUID job = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    var zero = new P4kImportResultDto.Counts(null, 0, 0, 0, 0, 0, 0, 0);
    var enriched = new P4kImportResultDto.Counts(null, 1, 1, 0, 0, 0, 0, 0);
    var result =
        new P4kImportResultDto(
            false, false, null, zero, enriched, zero, zero, zero, zero, zero, zero, 0, job, List.of());
    when(jobService.loadPayload(job)).thenReturn(bytes);
    stubAdmin(job, false);
    when(importService.applyImport(eq(bytes), any(P4kImportService.P4kApplyRequest.class)))
        .thenReturn(result);

    runner.run(job, P4kImportJobKind.APPLY, ADMIN);

    InOrder order = inOrder(importService, catalogueUpdates);
    order.verify(importService).applyImport(eq(bytes), any(P4kImportService.P4kApplyRequest.class));
    order.verify(catalogueUpdates).publishAfterCommit(any(), eq(List.of("catalogue")));
  }

  @Test
  void unchangedApplyDoesNotPublishCatalogueChange() {
    UUID job = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(job)).thenReturn(bytes);
    stubAdmin(job, false);
    when(importService.applyImport(eq(bytes), any(P4kImportService.P4kApplyRequest.class)))
        .thenReturn(someResult());

    runner.run(job, P4kImportJobKind.APPLY, ADMIN);

    verifyNoInteractions(catalogueUpdates);
  }
}
