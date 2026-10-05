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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.config.AppHttpProperties;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.netty.http.client.HttpClientRequest;

/**
 * Streams a member's Art. 15 / Art. 20 data export to the browser as a download (REQ-SEC-058).
 *
 * <p>The self-service endpoints take no user id; the backend derives the subject from the token.
 * The admin endpoints that take an id are ADMIN-gated here and at the backend.
 */
@RestController
@RequiredArgsConstructor
public class DataExportProxyController {

  private final BackendApiClient backendApiClient;

  private final AppHttpProperties httpProperties;

  /**
   * The caller's export as a JSON download.
   *
   * @return the JSON attachment
   */
  @GetMapping("/api/proxy/me/export/json")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> json() {
    return fetch(
        "/api/v1/users/me/export", new Object[0], "datenauskunft.json", MediaType.APPLICATION_JSON);
  }

  /**
   * The caller's export as a PDF download.
   *
   * @return the PDF attachment
   */
  @GetMapping("/api/proxy/me/export/pdf")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<byte[]> pdf() {
    return fetch(
        "/api/v1/users/me/export/pdf",
        new Object[0],
        "datenauskunft.pdf",
        MediaType.APPLICATION_PDF);
  }

  /**
   * Another member's export as a PDF download, for an admin; the backend applies the same
   * projections and anonymisation as for self-service.
   *
   * @param userId the member the export is about
   * @return the PDF attachment
   */
  @GetMapping("/admin/members/{userId}/export/pdf")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public ResponseEntity<byte[]> adminPdf(@PathVariable UUID userId) {
    return fetch(
        "/api/v1/admin/users/{userId}/export/pdf",
        new Object[] {userId},
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
        "/api/v1/admin/users/{userId}/export",
        new Object[] {userId},
        "datenauskunft-" + userId + ".json",
        MediaType.APPLICATION_JSON);
  }

  /**
   * Fetches one export document with an extended response timeout and no retries, and re-wraps it
   * with attachment headers. The filename never carries a handle.
   *
   * @param uri the backend URI template
   * @param uriVariables the values expanded into the template, in order
   * @param filename the download filename
   * @param mediaType the response content type
   * @return the proxied attachment response
   */
  private ResponseEntity<byte[]> fetch(
      @NotNull String uri,
      @NotNull Object[] uriVariables,
      @NotNull String filename,
      @NotNull MediaType mediaType) {
    byte[] body =
        withBackendStatus(
            () ->
                backendApiClient.execute(
                    HttpMethod.GET,
                    uri,
                    webClient ->
                        webClient
                            .get()
                            .uri(uri, uriVariables)
                            .httpRequest(
                                request -> {
                                  HttpClientRequest nativeRequest = request.getNativeRequest();
                                  nativeRequest.responseTimeout(
                                      httpProperties.exportResponseTimeout());
                                }),
                    spec -> spec.bodyToMono(byte[].class)));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(mediaType);
    headers.setContentDispositionFormData("attachment", filename);
    return ResponseEntity.ok().headers(headers).body(body);
  }
}
