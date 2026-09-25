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

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Frontend proxy for the job order handover report endpoints.
 *
 * <p>Forwards PDF download and preview requests to the backend via the authenticated {@link
 * WebClient} (which automatically attaches the OAuth2 token), and streams the PDF bytes back to the
 * browser.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class JobOrderHandoverReportProxyController {

  private final WebClient webClient;

  /**
   * Proxies the download of a persisted handover report PDF to the backend.
   *
   * @param jobOrderId the job order UUID
   * @param handoverId the handover UUID
   * @return the PDF as a byte array with appropriate headers
   */
  @GetMapping("/{jobOrderId}/handovers/{handoverId}/report")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadHandoverReport(
      @PathVariable @NotNull UUID jobOrderId,
      @PathVariable @NotNull UUID handoverId,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    try {
      byte[] pdf =
          webClient
              .get()
              .uri("/api/v1/orders/" + jobOrderId + "/handovers/" + handoverId + "/report")
              .headers(
                  h -> {
                    if (userTimeZone != null && !userTimeZone.isBlank()) {
                      h.set("X-User-Time-Zone", userTimeZone);
                    }
                  })
              .retrieve()
              .bodyToMono(byte[].class)
              .block();

      String filename = "uebergabeprotokoll-" + jobOrderId + ".pdf";
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDispositionFormData("attachment", filename);
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn(
          "Handover report proxy: backend returned {} — {}", e.getStatusCode(), e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Handover report proxy: unexpected error", e);
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the handover report.");
    }
  }

  /**
   * Proxies the download of a persisted item-handover report PDF to the backend.
   *
   * @param jobOrderId the job order UUID
   * @param handoverId the item-handover UUID
   * @param userTimeZone the caller's IANA time zone, so the PDF renders local times
   * @return the PDF as a byte array with appropriate headers
   */
  @GetMapping("/{jobOrderId}/item-handovers/{handoverId}/report")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> downloadItemHandoverReport(
      @PathVariable @NotNull UUID jobOrderId,
      @PathVariable @NotNull UUID handoverId,
      @RequestHeader(value = "X-User-Time-Zone", required = false) String userTimeZone) {
    try {
      byte[] pdf =
          webClient
              .get()
              .uri("/api/v1/orders/" + jobOrderId + "/item-handovers/" + handoverId + "/report")
              .headers(
                  h -> {
                    if (userTimeZone != null && !userTimeZone.isBlank()) {
                      h.set("X-User-Time-Zone", userTimeZone);
                    }
                  })
              .retrieve()
              .bodyToMono(byte[].class)
              .block();

      String filename = "uebergabeprotokoll-" + jobOrderId + ".pdf";
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDispositionFormData("attachment", filename);
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn(
          "Item-handover report proxy: backend returned {} — {}",
          e.getStatusCode(),
          e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Item-handover report proxy: unexpected error", e);
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the item-handover report.");
    }
  }

  /**
   * Proxies the preview of a handover report PDF (unsaved data) to the backend.
   *
   * @param jobOrderId the job order UUID
   * @param body the preview request payload
   * @return the PDF as a byte array with appropriate headers
   */
  @PostMapping("/{jobOrderId}/handovers/report/preview")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> previewHandoverReport(
      @PathVariable @NotNull UUID jobOrderId, @RequestBody @NotNull Map<String, Object> body) {
    try {
      byte[] pdf =
          webClient
              .post()
              .uri("/api/v1/orders/" + jobOrderId + "/handovers/report/preview")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(body)
              .retrieve()
              .bodyToMono(byte[].class)
              .block();

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDispositionFormData("attachment", "uebergabeprotokoll-vorschau.pdf");
      return ResponseEntity.ok().headers(headers).body(pdf);
    } catch (WebClientResponseException e) {
      log.warn(
          "Handover report preview proxy: backend returned {} — {}",
          e.getStatusCode(),
          e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Handover report preview proxy: unexpected error", e);
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the handover report preview.");
    }
  }
}
