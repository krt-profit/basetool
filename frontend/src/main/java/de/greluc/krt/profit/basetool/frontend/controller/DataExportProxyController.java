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

import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Streams the member's own Art. 15 / Art. 20 export to the browser as a download (REQ-SEC-058).
 *
 * <p><b>The member's own endpoints accept no user id and relay none.</b> The backend derives the
 * subject from the token, so they cannot be talked into fetching somebody else's export — which is
 * what makes them safe to expose to every member rather than needing a scope check of their own.
 * The endpoints that <em>do</em> take an id are {@code hasRole(ADMIN)} here and again at the
 * backend.
 *
 * <p>These are plain {@code GET} links rather than {@code krtFetch} writes: a download is a
 * navigation, not a mutation, and routing it through the AJAX layer would mean buffering the whole
 * document in JavaScript to hand it back to the browser.
 *
 * <p>The admin variant lives here too, under its own ADMIN-gated paths, so both downloads share one
 * attachment-wrapping seam rather than growing a second copy of it.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class DataExportProxyController {

  private final WebClient webClient;

  /**
   * The caller's export as a JSON download.
   *
   * @return the JSON attachment
   */
  @GetMapping("/api/proxy/me/export/json")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> json() {
    return fetch("/api/v1/users/me/export", "datenauskunft.json", MediaType.APPLICATION_JSON);
  }

  /**
   * The caller's export as a PDF download.
   *
   * @return the PDF attachment
   */
  @GetMapping("/api/proxy/me/export/pdf")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> pdf() {
    return fetch("/api/v1/users/me/export/pdf", "datenauskunft.pdf", MediaType.APPLICATION_PDF);
  }

  /**
   * Another member's export as a PDF download, for an admin serving a request from somebody who
   * cannot sign in.
   *
   * <p>ADMIN-gated here and again at the backend. The backend applies the <b>same</b> projections
   * and the same third-party anonymisation as the self-service path: an admin export is not a
   * fuller one.
   *
   * @param userId the member the export is about
   * @return the PDF attachment
   */
  @GetMapping("/admin/members/{userId}/export/pdf")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<byte[]> adminPdf(@PathVariable UUID userId) {
    return fetch(
        "/api/v1/admin/users/" + userId + "/export/pdf",
        "datenauskunft-" + userId + ".pdf",
        MediaType.APPLICATION_PDF);
  }

  /**
   * Another member's export as a JSON download.
   *
   * @param userId the member the export is about
   * @return the JSON attachment
   */
  @GetMapping("/admin/members/{userId}/export/json")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<byte[]> adminJson(@PathVariable UUID userId) {
    return fetch(
        "/api/v1/admin/users/" + userId + "/export",
        "datenauskunft-" + userId + ".json",
        MediaType.APPLICATION_JSON);
  }

  /**
   * Fetches one export document and re-wraps it with attachment headers.
   *
   * <p>The filename carries no <b>handle</b>. A download filename reaches the browser's download
   * list, shells and mail clients, and a name there is a leak nobody chose. The member's own export
   * needs no identifier at all; an admin export carries the subject's <em>id</em>, because an admin
   * handling several requests has to be able to tell two files apart.
   *
   * @param uri the backend URI
   * @param filename the download filename
   * @param mediaType the response content type
   * @return the proxied attachment response
   */
  private ResponseEntity<byte[]> fetch(
      @NotNull String uri, @NotNull String filename, @NotNull MediaType mediaType) {
    try {
      byte[] body = webClient.get().uri(uri).retrieve().bodyToMono(byte[].class).block();
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(mediaType);
      headers.setContentDispositionFormData("attachment", filename);
      return ResponseEntity.ok().headers(headers).body(body);
    } catch (WebClientResponseException e) {
      log.warn("Data-export proxy: backend returned {} for {}", e.getStatusCode(), uri);
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      // The subject is the caller and is identifiable from the session; nothing about them is
      // written into the log line.
      log.error("Data-export proxy: unexpected error for {}", uri, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "An unexpected error occurred while generating the data export.");
    }
  }
}
