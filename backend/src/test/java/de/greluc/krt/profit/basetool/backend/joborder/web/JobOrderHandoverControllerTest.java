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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandoverDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverCreateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverReportService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderHandoverService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemProductionCreateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderItemProductionService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Pure-Mockito unit tests for the handover, production and report endpoints of {@link
 * JobOrderHandoverController}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderHandoverControllerTest {

  @Mock private JobOrderHandoverService jobOrderHandoverService;
  @Mock private JobOrderItemProductionService jobOrderItemProductionService;
  @Mock private JobOrderHandoverReportService jobOrderHandoverReportService;

  @InjectMocks private JobOrderHandoverController controller;

  @Test
  void createHandover_delegatesToHandoverServiceWithBody() {
    UUID jobOrderId = UUID.randomUUID();
    JobOrderHandoverCreateDto dto =
        new JobOrderHandoverCreateDto(
            Instant.parse("2026-05-01T12:00:00Z"), "alice", "DAS KARTELL", List.of());
    JobOrderHandoverDto persisted =
        new JobOrderHandoverDto(
            UUID.randomUUID(),
            jobOrderId,
            dto.handoverTime(),
            "alice",
            "DAS KARTELL",
            null,
            null,
            List.of(),
            1L);
    when(jobOrderHandoverService.createHandover(jobOrderId, dto)).thenReturn(persisted);

    JobOrderHandoverDto result = controller.createHandover(jobOrderId, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderHandoverService).createHandover(jobOrderId, dto);
  }

  @Test
  void bookProduction_delegatesToService() {
    UUID jobOrderId = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    JobOrderItemProductionCreateDto dto =
        new JobOrderItemProductionCreateDto(
            3,
            7L,
            List.of(),
            List.of(),
            new JobOrderItemProductionCreateDto.BookInDto(
                UUID.randomUUID(), null, null, false, true));
    JobOrderItemDto persisted =
        new JobOrderItemDto(itemId, null, null, 5, 3, 0, null, List.of(), false, 8L);
    when(jobOrderItemProductionService.bookProduction(jobOrderId, itemId, dto))
        .thenReturn(persisted);

    JobOrderItemDto result = controller.bookProduction(jobOrderId, itemId, dto);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderItemProductionService).bookProduction(jobOrderId, itemId, dto);
  }

  @Test
  void downloadHandoverReport_validTimeZone_passedToService() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReport(
            eq(jobOrderId), eq(handoverId), eq(java.time.ZoneId.of("Europe/Berlin"))))
        .thenReturn(pdf);

    ResponseEntity<byte[]> response =
        controller.downloadHandoverReport(
            jobOrderId, handoverId, java.time.ZoneId.of("Europe/Berlin"));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(pdf);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(headers.getContentDisposition().getFilename())
        .isEqualTo("uebergabeprotokoll-" + jobOrderId + ".pdf");
  }

  @Test
  void downloadHandoverReport_nullTimeZoneHeader_passedAsNull() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReport(jobOrderId, handoverId, null))
        .thenReturn(pdf);

    ResponseEntity<byte[]> response =
        controller.downloadHandoverReport(jobOrderId, handoverId, null);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    verify(jobOrderHandoverReportService).generateHandoverReport(jobOrderId, handoverId, null);
  }

  @Test
  void previewHandoverReport_returnsPdfWithPreviewFilename() {
    UUID jobOrderId = UUID.randomUUID();
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#42", LocalDateTime.of(2026, 5, 1, 12, 0), "alice", List.of());
    byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46};
    when(jobOrderHandoverReportService.generateHandoverReportPreview(dto)).thenReturn(pdf);

    ResponseEntity<byte[]> response = controller.previewHandoverReport(jobOrderId, dto);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(pdf);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(headers.getContentDisposition().getFilename())
        .isEqualTo("uebergabeprotokoll-vorschau.pdf");
  }
}
