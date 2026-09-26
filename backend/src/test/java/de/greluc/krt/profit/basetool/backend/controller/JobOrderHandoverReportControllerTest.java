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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverReportService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderHandoverService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

@ExtendWith(MockitoExtension.class)
class JobOrderHandoverReportControllerTest {

  @Mock private JobOrderService jobOrderService;

  @Mock private JobOrderHandoverService jobOrderHandoverService;

  @Mock private JobOrderHandoverReportService jobOrderHandoverReportService;

  @Mock private UserService userService;

  @Mock private RoleHierarchy roleHierarchy;

  @InjectMocks private JobOrderController controller;

  @Test
  void downloadHandoverReport_shouldReturnPdfResponse_whenHandoverExists() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();
    byte[] fakePdf = new byte[] {0x25, 0x50, 0x44, 0x46};

    when(jobOrderHandoverReportService.generateHandoverReport(jobOrderId, handoverId, null))
        .thenReturn(fakePdf);

    ResponseEntity<byte[]> response =
        controller.downloadHandoverReport(jobOrderId, handoverId, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(fakePdf.length, response.getBody().length);
    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(disposition);
    assertTrue(disposition.contains("attachment"), "Content-Disposition must be attachment");
    assertTrue(disposition.contains(".pdf"), "Filename must end with .pdf");
    verify(jobOrderHandoverReportService).generateHandoverReport(jobOrderId, handoverId, null);
  }

  @Test
  void downloadHandoverReport_shouldPropagateNotFound_whenHandoverDoesNotExist() {
    UUID jobOrderId = UUID.randomUUID();
    UUID handoverId = UUID.randomUUID();

    when(jobOrderHandoverReportService.generateHandoverReport(jobOrderId, handoverId, null))
        .thenThrow(new NotFoundException("Handover not found"));

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> controller.downloadHandoverReport(jobOrderId, handoverId, null));
  }

  @Test
  void previewHandoverReport_shouldReturnPdfResponse_whenDtoIsValid() {
    UUID jobOrderId = UUID.randomUUID();
    byte[] fakePdf = new byte[] {0x25, 0x50, 0x44, 0x46};

    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#42",
            LocalDateTime.now(),
            "PreviewPilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Laranite", "Port Olisar", 3.5, 90, "SCU")));

    when(jobOrderHandoverReportService.generateHandoverReportPreview(dto)).thenReturn(fakePdf);

    ResponseEntity<byte[]> response = controller.previewHandoverReport(jobOrderId, dto);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(fakePdf.length, response.getBody().length);
    assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
    String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(disposition);
    assertTrue(disposition.contains("attachment"), "Content-Disposition must be attachment");
    assertTrue(disposition.contains(".pdf"), "Filename must end with .pdf");
    verify(jobOrderHandoverReportService).generateHandoverReportPreview(dto);
  }

  @Test
  void previewHandoverReport_shouldPropagateServiceException() {
    UUID jobOrderId = UUID.randomUUID();
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto("#1", LocalDateTime.now(), "Pilot", List.of());

    when(jobOrderHandoverReportService.generateHandoverReportPreview(any()))
        .thenThrow(new RuntimeException("PDF generation failed"));

    RuntimeException ex =
        assertThrows(
            RuntimeException.class, () -> controller.previewHandoverReport(jobOrderId, dto));
    assertEquals("PDF generation failed", ex.getMessage());
  }
}
