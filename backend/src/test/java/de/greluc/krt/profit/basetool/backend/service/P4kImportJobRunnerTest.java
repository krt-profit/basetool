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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.MasterDataCacheEvictionService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobRunner;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportService;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

  @Mock private P4kImportJobService jobService;
  @Mock private P4kImportService importService;
  @Mock private MasterDataCacheEvictionService cacheEvictionService;

  private final AuthHelperService authHelperService = new AuthHelperService(roleHierarchy());

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
            cacheEvictionService,
            JsonMapper.builder().build(),
            authHelperService);
  }

  private static P4kImportResultDto someResult() {
    P4kImportResultDto.Counts zero = new P4kImportResultDto.Counts(null, 0, 0, 0, 0, 0, 0, 0);
    return new P4kImportResultDto(
        true, true, null, zero, zero, zero, zero, zero, 0, UUID.randomUUID());
  }

  private UUID stubAdmin(UUID jobId) {
    UUID admin = UUID.randomUUID();
    P4kImportJob job = new P4kImportJob();
    job.setCreatedBy(admin);
    when(jobService.getJob(jobId)).thenReturn(job);
    return admin;
  }

  @Test
  void run_preview_marksRunningThenPreviewsThenSucceeds_keepingPayload() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(someResult());

    runner.run(id, P4kImportJobKind.PREVIEW, false, ADMIN);

    InOrder order = inOrder(jobService, importService);
    order.verify(jobService).markRunning(id);
    order.verify(importService).previewImport(bytes);
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
    UUID admin = stubAdmin(id);
    P4kImportService.P4kApplyRequest request =
        new P4kImportService.P4kApplyRequest(id, admin, true);
    when(importService.applyImport(bytes, request)).thenReturn(someResult());

    runner.run(id, P4kImportJobKind.APPLY, true, ADMIN);

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
    UUID admin = stubAdmin(id);
    AtomicReference<Authentication> actingAs = new AtomicReference<>();
    when(importService.applyImport(bytes, new P4kImportService.P4kApplyRequest(id, admin, false)))
        .thenAnswer(_ -> {
          actingAs.set(authHelperService.currentAuthentication().orElse(null));
          return someResult();
        });

    runner.run(id, P4kImportJobKind.APPLY, false, ADMIN);

    assertThat(actingAs.get()).isSameAs(ADMIN);
    assertThat(authHelperService.currentAuthentication()).isEmpty();
  }

  @Test
  void run_applyFailure_recordsFailed_andDoesNotEvictCaches() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    UUID admin = stubAdmin(id);
    when(importService.applyImport(bytes, new P4kImportService.P4kApplyRequest(id, admin, false)))
        .thenThrow(new BadRequestException("empty catalog"));

    runner.run(id, P4kImportJobKind.APPLY, false, ADMIN);

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

    runner.run(id, P4kImportJobKind.PREVIEW, false, ADMIN);

    verify(jobService).markFailed(id, "not valid JSON");
    verify(jobService, never()).markSucceeded(any(), anyString());
    verify(jobService).pruneOldJobs();
  }

  @Test
  void run_housekeepingFailure_doesNotPropagate() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(someResult());
    doThrow(new RuntimeException("db down")).when(jobService).pruneOldJobs();

    assertDoesNotThrow(() -> runner.run(id, P4kImportJobKind.PREVIEW, false, ADMIN));
    verify(jobService).markSucceeded(eq(id), anyString());
  }
}
