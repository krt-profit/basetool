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

package de.greluc.krt.profit.basetool.frontend.controller;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Frontend proxy for the bank PDF exports (epic #556 Phase 3): the account statement (REQ-BANK-014)
 * and the management three-month report (REQ-BANK-015). Streams the backend's PDF bytes back to the
 * browser via the authenticated {@link WebClient} (OAuth2 token attached automatically) and
 * forwards the caller's IANA time zone so the documents render local timestamps. Authorization is
 * decided by the backend gates; this seam only requires authentication.
 */
@RestController
@RequestMapping("/api/proxy/bank")
@RequiredArgsConstructor
@Slf4j
public class BankReportProxyController {

  private final WebClient webClient;

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
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/bank/accounts/" + id + "/statement")
            .queryParam("from", from)
            .queryParam("to", to)
            .toUriString();
    return fetchPdf(uri, userTimeZone, "kontoauszug-" + id + ".pdf");
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
    return fetchPdf(
        "/api/v1/bank/export/three-month-report", userTimeZone, "bank-3-monats-report.pdf");
  }

  /**
   * Fetches one backend PDF and re-wraps it with attachment headers; backend errors propagate with
   * their original status so bank.js can surface 403/400 distinctly.
   *
   * @param uri the backend URI incl. query
   * @param userTimeZone the zone header to forward; may be {@code null}
   * @param filename the download filename
   * @return the proxied PDF response
   */
  private ResponseEntity<byte[]> fetchPdf(
      @NotNull String uri, String userTimeZone, @NotNull String filename) {
    try {
      byte[] pdf =
          webClient
              .get()
              .uri(uri)
              .headers(
                  h -> {
                    if (userTimeZone != null && !userTimeZone.isBlank()) {
                      h.set("X-User-Time-Zone", userTimeZone);
                    }
                  })
              .retrieve()
              .bodyToMono(byte[].class)
              .block();

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDispositionFormData("attachment", filename);
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn("Bank report proxy: backend returned {} for {}", e.getStatusCode(), uri);
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Bank report proxy: unexpected error for {}", uri, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the bank report.");
    }
  }
}
