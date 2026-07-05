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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure-Mockito unit tests for {@link P4kImportJobRunner}: {@link P4kImportJobService} and {@link
 * P4kImportService} are mocked and the runner is driven synchronously (the {@code @Async} dispatch
 * is a Spring concern, irrelevant to the orchestration logic). A real Jackson 3 {@link JsonMapper}
 * serializes the result. Verifies the run order (running → work → succeeded), that APPLY reclaims
 * its payload while PREVIEW keeps it, that a failure is recorded rather than thrown, and that
 * housekeeping never masks the outcome.
 */
@ExtendWith(MockitoExtension.class)
class P4kImportJobRunnerTest {

  @Mock private P4kImportJobService jobService;
  @Mock private P4kImportService importService;
  @Mock private MasterDataCacheEvictionService cacheEvictionService;

  private P4kImportJobRunner runner;

  @BeforeEach
  void setUp() {
    runner =
        new P4kImportJobRunner(
            jobService, importService, cacheEvictionService, JsonMapper.builder().build());
  }

  private static P4kImportResultDto someResult() {
    P4kImportResultDto.Counts zero = new P4kImportResultDto.Counts(0, 0, 0, 0, 0, 0);
    return new P4kImportResultDto(true, true, zero, zero, zero, zero, zero, 0, UUID.randomUUID());
  }

  @Test
  void run_preview_marksRunningThenPreviewsThenSucceeds_keepingPayload() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenReturn(someResult());

    runner.run(id, P4kImportJobKind.PREVIEW, false);

    InOrder order = inOrder(jobService, importService);
    order.verify(jobService).markRunning(id);
    order.verify(importService).previewImport(bytes);
    order.verify(jobService).markSucceeded(eq(id), anyString());
    verify(importService, never()).applyImport(any(), anyBoolean());
    verify(jobService, never())
        .deletePayload(any()); // a preview keeps its payload for a later apply
    verify(jobService, never()).markFailed(any(), anyString());
    verify(jobService).pruneOldJobs();
    // A preview changes no master data, so it must never evict the caches.
    verify(cacheEvictionService, never()).evictP4kSyncedMasterData();
  }

  @Test
  void run_apply_appliesThenSucceeds_andReclaimsPayload() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.applyImport(bytes, true)).thenReturn(someResult());

    runner.run(id, P4kImportJobKind.APPLY, true);

    verify(importService).applyImport(bytes, true);
    verify(importService, never()).previewImport(any());
    verify(jobService).markSucceeded(eq(id), anyString());
    verify(jobService).deletePayload(id); // an apply is terminal -> reclaim the bytes
    verify(jobService).pruneOldJobs();
    // A committed apply rewrote master data -> evict the P4K-synced caches.
    verify(cacheEvictionService).evictP4kSyncedMasterData();
  }

  @Test
  void run_applyFailure_recordsFailed_andDoesNotEvictCaches() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.applyImport(bytes, false))
        .thenThrow(new BadRequestException("empty catalog"));

    runner.run(id, P4kImportJobKind.APPLY, false);

    verify(jobService).markFailed(id, "empty catalog");
    verify(jobService).deletePayload(id); // the payload is still reclaimed on a failed apply
    // The apply rolled back, so nothing changed -> the caches must NOT be evicted.
    verify(cacheEvictionService, never()).evictP4kSyncedMasterData();
  }

  @Test
  void run_failure_recordsFailedWithMessage_andStillPrunes() {
    UUID id = UUID.randomUUID();
    byte[] bytes = "{ bad".getBytes(StandardCharsets.UTF_8);
    when(jobService.loadPayload(id)).thenReturn(bytes);
    when(importService.previewImport(bytes)).thenThrow(new BadRequestException("not valid JSON"));

    runner.run(id, P4kImportJobKind.PREVIEW, false);

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

    assertDoesNotThrow(() -> runner.run(id, P4kImportJobKind.PREVIEW, false));
    verify(jobService).markSucceeded(eq(id), anyString());
  }
}
