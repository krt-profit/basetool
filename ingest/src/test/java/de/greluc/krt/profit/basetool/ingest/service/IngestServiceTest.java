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

package de.greluc.krt.profit.basetool.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link IngestService}: a successfully relayed and staged handoff increments the
 * {@code basetool_ingest_handoff_total} counter under the bounded {@link HandoffKind}.
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

  @Mock private BackendImportClient backendImportClient;
  @Mock private HandoffStagingService handoffStagingService;
  @Mock private IngestProperties ingestProperties;
  @Mock private SubjectRateLimiter subjectRateLimiter;

  private SimpleMeterRegistry meterRegistry;
  private IngestService service;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    service =
        new IngestService(
            backendImportClient,
            handoffStagingService,
            ingestProperties,
            subjectRateLimiter,
            meterRegistry);
  }

  @Test
  void ingestRefinery_countsAcceptedHandoffUnderRefineryKind() {
    when(backendImportClient.forwardRefineryExtract(any(), any(), any(), any()))
        .thenReturn("{\"draft\":true}");
    when(handoffStagingService.stage(any(), any(), any())).thenReturn("handoff-1");
    when(ingestProperties.getRefineryPath()).thenReturn("/refinery/import");
    when(ingestProperties.getFrontendBaseUrl()).thenReturn("https://frontend.test");

    service.ingestRefinery("sub-1", "bearer", null, null, mock(RefineryExtractDto.class));

    assertThat(handoffCounter(HandoffKind.REFINERY)).isEqualTo(1.0d);
  }

  @Test
  void ingestBlueprint_countsAcceptedHandoffUnderBlueprintKind() {
    when(backendImportClient.forwardBlueprintPreview(any(), any(), any(), any()))
        .thenReturn("{\"preview\":true}");
    when(handoffStagingService.stage(any(), any(), any())).thenReturn("handoff-2");
    when(ingestProperties.getBlueprintPath()).thenReturn("/blueprint/import");
    when(ingestProperties.getFrontendBaseUrl()).thenReturn("https://frontend.test");

    service.ingestBlueprint("sub-1", "bearer", null, null, new byte[] {1, 2, 3});

    assertThat(handoffCounter(HandoffKind.BLUEPRINT)).isEqualTo(1.0d);
  }

  private double handoffCounter(HandoffKind kind) {
    return meterRegistry
        .get(MetricNames.INGEST_HANDOFF)
        .tag(MetricNames.TAG_KIND, kind.name())
        .counter()
        .count();
  }
}
