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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingReportDto;
import de.greluc.krt.profit.basetool.backend.purchasing.internal.PurchasingReportService;
import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The purchasing report (REQ-PUR-023): figures, a CSV file and a forum-safe text block, without
 * member names or partner handles. The service restricts the deals to those the caller may read.
 */
@RestController
@RequestMapping("/api/v1/purchasing/report")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_KRT_MEMBER)
public class PurchasingReportController {

  private final PurchasingReportService reportService;

  /**
   * Builds the report of a period.
   *
   * @param from the first month as {@code yyyy-MM}, default eleven months before {@code to}
   * @param to the last month as {@code yyyy-MM}, default the current month
   * @return the report
   */
  @Operation(summary = "Read the purchasing report")
  @GetMapping
  public PurchasingReportDto report(
      @RequestParam(required = false) YearMonth from,
      @RequestParam(required = false) YearMonth to) {
    return reportService.report(from, to);
  }

  /**
   * Exports the report as CSV, one line per deal.
   *
   * @param from the first month, default eleven months before {@code to}
   * @param to the last month, default the current month
   * @return the CSV file
   */
  @Operation(summary = "Export the purchasing report as CSV")
  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestParam(required = false) YearMonth from,
      @RequestParam(required = false) YearMonth to) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("purchasing-report.csv").build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(reportService.csv(from, to).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds the forum-safe text block of the report.
   *
   * @param from the first month, default eleven months before {@code to}
   * @param to the last month, default the current month
   * @return the text
   */
  @Operation(summary = "Read the purchasing report as a forum text block")
  @GetMapping("/forum-text")
  public ResponseEntity<String> forumText(
      @RequestParam(required = false) YearMonth from,
      @RequestParam(required = false) YearMonth to) {
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
        .body(reportService.forumText(from, to));
  }
}
