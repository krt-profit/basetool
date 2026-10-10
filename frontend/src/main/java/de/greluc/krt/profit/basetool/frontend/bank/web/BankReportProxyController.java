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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frontend proxy for the bank PDF exports: account statement (REQ-BANK-014) and management
 * three-month report (REQ-BANK-015). Forwards the caller's IANA time zone; authorization is decided
 * by the backend, this seam only requires authentication.
 */
@RestController
@RequestMapping("/api/proxy/bank")
@RequiredArgsConstructor
public class BankReportProxyController {

  /** The bank domain's backend calls. */
  private final BankBackendClient bankClient;

  /**
   * Proxies the account statement download for a caller-chosen period.
   *
   * @param id the account id
   * @param from period start; bound as an instant so the relayed value cannot carry URI syntax
   * @param to period end; bound as an instant so the relayed value cannot carry URI syntax
   * @param userTimeZone the caller's IANA time zone; optional
   * @return the PDF with attachment headers
   */
  @GetMapping("/accounts/{id}/statement")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadStatement(
      @PathVariable @NotNull UUID id,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    byte[] pdf = withBackendStatus(() -> bankClient.accountStatement(id, from, to, userTimeZone));
    return pdfResponse(pdf, "kontoauszug-" + id + ".pdf");
  }

  /**
   * Proxies the management three-month report download.
   *
   * @param userTimeZone the caller's IANA time zone; optional
   * @return the PDF with attachment headers
   */
  @GetMapping("/export/three-month-report")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadThreeMonthReport(
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    byte[] pdf = withBackendStatus(() -> bankClient.threeMonthReport(userTimeZone));
    return pdfResponse(pdf, "bank-3-monats-report.pdf");
  }

  /**
   * Wraps a fetched backend PDF with attachment headers; backend errors have already propagated
   * with their original status so bank.js can surface 403/400 distinctly.
   *
   * @param pdf the PDF bytes, or {@code null} when the backend sent no body
   * @param filename the download filename
   * @return the proxied PDF response
   */
  private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, @NotNull String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", filename);
    return ResponseEntity.ok().headers(headers).body(pdf);
  }
}
