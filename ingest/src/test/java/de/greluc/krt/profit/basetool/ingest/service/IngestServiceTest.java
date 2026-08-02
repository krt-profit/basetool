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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractGoodDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractImageDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
  void ingestRefinery_logsThePayloadShapeWithoutAnyScreenRead() {
    // The gateway interprets nothing, so these counts are the only handle on "what did the client
    // actually push?". No material name and no quantity is logged — only structure.
    when(backendImportClient.forwardRefineryExtract(any(), any(), any(), any()))
        .thenReturn("{\"draft\":true}");
    when(handoffStagingService.stage(any(), any(), any())).thenReturn("handoff-1");
    when(ingestProperties.getRefineryPath()).thenReturn("/refinery/import");
    when(ingestProperties.getFrontendBaseUrl()).thenReturn("https://frontend.test");

    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestService.class,
            Level.INFO,
            () -> service.ingestRefinery("sub-1", "bearer", null, null, extract()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getFormattedMessage())
        .isEqualTo(
            "Relaying refinery extract (schemaVersion=1, tool=krt-extractor/1.2.3, orders=1,"
                + " goods=2, images=1)");
    assertThat(events.getFirst().getFormattedMessage()).doesNotContain("Quantanium");
  }

  @Test
  void ingestRefinery_sanitisesClientSuppliedProvenanceBeforeLoggingIt() {
    // `tool` is free text from an internet-facing client; a newline in it would forge a log line.
    when(backendImportClient.forwardRefineryExtract(any(), any(), any(), any()))
        .thenReturn("{\"draft\":true}");
    when(handoffStagingService.stage(any(), any(), any())).thenReturn("handoff-1");
    when(ingestProperties.getRefineryPath()).thenReturn("/refinery/import");
    when(ingestProperties.getFrontendBaseUrl()).thenReturn("https://frontend.test");
    RefineryExtractDto forged =
        new RefineryExtractDto(
            1, "evil\nERROR fabricated", "1.0", null, null, null, List.of(order()));

    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestService.class,
            Level.INFO,
            () -> service.ingestRefinery("sub-1", "bearer", null, null, forged));

    assertThat(events.getFirst().getFormattedMessage()).doesNotContain("\n");
  }

  @Test
  void ingestBlueprint_logsTheExportSizeBecauseTheBodyIsOpaque() {
    when(backendImportClient.forwardBlueprintPreview(any(), any(), any(), any()))
        .thenReturn("{\"preview\":true}");
    when(handoffStagingService.stage(any(), any(), any())).thenReturn("handoff-2");
    when(ingestProperties.getBlueprintPath()).thenReturn("/blueprint/import");
    when(ingestProperties.getFrontendBaseUrl()).thenReturn("https://frontend.test");

    List<ILoggingEvent> events =
        LogCapture.capture(
            IngestService.class,
            Level.INFO,
            () -> service.ingestBlueprint("sub-1", "bearer", null, null, new byte[] {1, 2, 3}));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getFormattedMessage())
        .isEqualTo("Relaying blueprint export (3 bytes)");
  }

  /** An order carrying two goods rows and one source image, for the shape assertions above. */
  private static RefineryExtractOrderDto order() {
    RefineryExtractGoodDto good =
        new RefineryExtractGoodDto(0, "Quantanium", 100, 500, 420, Boolean.TRUE, 0.9d, null);
    return new RefineryExtractOrderDto(
        "SETUP",
        Boolean.TRUE,
        null,
        "ARC-L1",
        "Dinyx Solventation",
        null,
        null,
        null,
        null,
        null,
        List.of(new RefineryExtractImageDto("capture.png", 2560, 1440, "PANEL", null)),
        List.of(good, good));
  }

  private static RefineryExtractDto extract() {
    return new RefineryExtractDto(
        1, "krt-extractor", "1.2.3", "vlm-1", null, "de-DE", List.of(order()));
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
