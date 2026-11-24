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

package de.greluc.krt.profit.basetool.backend.purchasing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingReportService;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The CSV download of the purchasing report is an RFC 4180 attachment (REQ-PUR-023). */
class PurchasingReportControllerTest {

  private final PurchasingReportService reportService = mock(PurchasingReportService.class);

  private final PurchasingReportController controller =
      new PurchasingReportController(reportService);

  @Test
  void theCsvIsAUtf8AttachmentThatDeclaresItsHeaderRow() {
    YearMonth month = YearMonth.of(2026, 9);
    when(reportService.csv(month, month)).thenReturn("Deal,Eintrag\r\n1,Größe\r\n");

    ResponseEntity<byte[]> response = controller.export(month, month);

    assertThat(response.getBody())
        .isEqualTo("Deal,Eintrag\r\n1,Größe\r\n".getBytes(StandardCharsets.UTF_8));
    MediaType type = response.getHeaders().getContentType();
    assertThat(type).isNotNull();
    assertThat(type.isCompatibleWith(MediaType.parseMediaType("text/csv"))).isTrue();
    assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(type.getParameter("header")).isEqualTo("present");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"purchasing-report.csv\"");
  }
}
