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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import tools.jackson.databind.json.JsonMapper;

/** Verifies version-scoped stat follow-up counts only fetched, applied payloads. */
class ScWikiItemStatFollowUpTest {

  @Test
  void absentDetailsAreNeitherAppliedNorReportedAsSuccessful() {
    ScWikiClient client = mock(ScWikiClient.class);
    GameItemStatRepository repository = mock(GameItemStatRepository.class);
    UUID uuid = UUID.randomUUID();
    when(repository.findBlueprintOutputUuidsWithoutStats()).thenReturn(List.of(uuid));
    SyncReportService report = mock(SyncReportService.class);
    ScWikiItemStatFollowUp followUp =
        new ScWikiItemStatFollowUp(
            client, BoundProperties.bind(ScWikiProperties.class, Map.of()), repository, report);
    AtomicInteger writes = new AtomicInteger();

    assertEquals(0, followUp.fetchMissing((_, _) -> writes.incrementAndGet()));

    assertEquals(0, writes.get());
    verify(report, never()).recordApplied(anyLong());
    verify(client).openSyncRun();
  }

  @Test
  void successfulDetailsReachTheWriterUnderOneVersionScope() {
    ScWikiClient client = mock(ScWikiClient.class);
    GameItemStatRepository repository = mock(GameItemStatRepository.class);
    UUID uuid = UUID.randomUUID();
    when(repository.findBlueprintOutputUuidsWithoutStats()).thenReturn(List.of(uuid));
    ScWikiItemDto dto =
        JsonMapper.builder()
            .build()
            .readValue("{\"durability\":{\"health\":1100}}", ScWikiItemDto.class);
    when(client.fetchOne("/api/items", uuid, ScWikiItemDto.class, "item")).thenReturn(dto);
    SyncReportService report = mock(SyncReportService.class);
    ScWikiItemStatFollowUp followUp =
        new ScWikiItemStatFollowUp(
            client, BoundProperties.bind(ScWikiProperties.class, Map.of()), repository, report);
    AtomicInteger writes = new AtomicInteger();

    assertEquals(
        1,
        followUp.fetchMissing((itemId, payload) -> {
          assertEquals(uuid, itemId);
          assertEquals(dto, payload);
          writes.incrementAndGet();
        }));

    assertEquals(1, writes.get());
    verify(report).recordApplied(1);
    verify(client).openSyncRun();
  }

  @Test
  void failedWriteCallbacksNeverContributeAnAppliedRow() {
    ScWikiClient client = mock(ScWikiClient.class);
    GameItemStatRepository repository = mock(GameItemStatRepository.class);
    SyncReportService report = mock(SyncReportService.class);
    UUID uuid = UUID.randomUUID();
    when(repository.findBlueprintOutputUuidsWithoutStats()).thenReturn(List.of(uuid));
    ScWikiItemDto dto = JsonMapper.builder().build().readValue("{}", ScWikiItemDto.class);
    when(client.fetchOne("/api/items", uuid, ScWikiItemDto.class, "item")).thenReturn(dto);
    ScWikiItemStatFollowUp followUp =
        new ScWikiItemStatFollowUp(
            client, BoundProperties.bind(ScWikiProperties.class, Map.of()), repository, report);

    assertEquals(
        0,
        followUp.fetchMissing((_, _) -> {
          throw new OptimisticLockingFailureException("concurrent write");
        }));

    verify(report).recordFailed(1);
    verify(report, never()).recordApplied(anyLong());
  }
}
