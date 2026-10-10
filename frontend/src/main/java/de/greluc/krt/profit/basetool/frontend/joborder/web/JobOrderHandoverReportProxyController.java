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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.HandoverReportPreviewRequestDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

/**
 * Frontend proxy for the job order handover report endpoints.
 *
 * <p>Forwards PDF download and preview requests to the backend through {@link
 * JobOrderBackendClient} (authenticated client, shared error mapping), and streams the PDF bytes
 * back to the browser.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class JobOrderHandoverReportProxyController {

  /** Fetches the report PDFs from the backend. */
  private final JobOrderBackendClient jobOrderClient;

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
    return fetchPdf(
        false, jobOrderId, handoverId, userTimeZone, "uebergabeprotokoll-" + jobOrderId + ".pdf");
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
    return fetchPdf(
        true, jobOrderId, handoverId, userTimeZone, "uebergabeprotokoll-" + jobOrderId + ".pdf");
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
      @PathVariable @NotNull UUID jobOrderId,
      @RequestBody @NotNull HandoverReportPreviewRequestDto body) {
    byte[] pdf = withBackendStatus(() -> jobOrderClient.handoverReportPreview(jobOrderId, body));

    return attachment(pdf, "uebergabeprotokoll-vorschau.pdf");
  }

  /**
   * Fetches one backend report PDF, forwarding the caller's time zone, and wraps it as an
   * attachment.
   *
   * @param itemHandover {@code true} for an item-handover report, {@code false} for a material one
   * @param jobOrderId the job order
   * @param handoverId the handover or item-handover
   * @param userTimeZone the caller's IANA time zone; may be {@code null}
   * @param filename the download filename
   * @return the PDF with attachment headers
   */
  private @NotNull ResponseEntity<byte[]> fetchPdf(
      boolean itemHandover,
      @NotNull UUID jobOrderId,
      @NotNull UUID handoverId,
      String userTimeZone,
      @NotNull String filename) {
    byte[] pdf =
        withBackendStatus(
            () ->
                itemHandover
                    ? jobOrderClient.itemHandoverReport(jobOrderId, handoverId, userTimeZone)
                    : jobOrderClient.handoverReport(jobOrderId, handoverId, userTimeZone));
    return attachment(pdf, filename);
  }

  /**
   * Wraps PDF bytes as a download.
   *
   * @param pdf the document
   * @param filename the download filename
   * @return the PDF with attachment headers
   */
  private static @NotNull ResponseEntity<byte[]> attachment(byte[] pdf, @NotNull String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", filename);
    return ResponseEntity.ok().headers(headers).body(pdf);
  }
}
