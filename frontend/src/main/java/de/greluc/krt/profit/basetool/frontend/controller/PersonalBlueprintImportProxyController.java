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

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * AJAX proxy for the blueprint import modal: {@code POST .../preview} forwards an uploaded
 * blueprint export to the backend preview endpoint, and {@code POST .../apply} relays the chosen
 * resolutions. The backend derives the owner from the relayed JWT.
 */
@RestController
@RequestMapping("/personal-inventory/blueprints/import")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class PersonalBlueprintImportProxyController {

  private final WebClient webClient;
  private final BackendApiClient backendApiClient;
  private final IngestHandoffService ingestHandoffService;

  /**
   * Returns and consumes the blueprint import handoff the desktop extractor staged for the current
   * user (REQ-INGEST-004). A POST because the pickup is single-use; an unknown, expired, consumed
   * or foreign id is a 404.
   *
   * @param handoff the handoff id from the {@code ?handoff=} parameter
   * @param principal the authenticated user (its subject scopes the staged lookup)
   * @return the staged blueprint import preview
   * @throws ResponseStatusException 404 when there is no staged handoff for this user/id
   */
  @PostMapping("/staged")
  public BlueprintImportPreviewDto staged(
      @RequestParam("handoff") String handoff, @AuthenticationPrincipal OidcUser principal) {
    String sub = CurrentUser.userIdText(principal);
    return ingestHandoffService
        .consume(sub, handoff, HandoffKind.BLUEPRINT, BlueprintImportPreviewDto.class)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /**
   * Inclusive upper bound on a relayed blueprint-export upload, matching the backend parser's own
   * cap ({@code BlueprintExportParser#MAX_IMPORT_BYTES}). A real export is well under 1 MB.
   */
  static final long MAX_EXPORT_BYTES = 8L * 1024 * 1024;

  /**
   * Proxies a blueprint export JSON upload to the backend import-preview endpoint and returns the
   * resolution preview. The backend persists nothing at this step.
   *
   * @param file the uploaded blueprint export JSON (SCMDB log-watcher, Basetool BP Extractor, or
   *     scmdb.net export)
   * @return the import preview (per-name rows + status counts)
   * @throws ResponseStatusException 400 for an empty upload and 413 for one above {@link
   *     #MAX_EXPORT_BYTES} (both refused before the upload is read), the backend's status if the
   *     backend rejects it (e.g. 400 for a malformed file), or 500 on an unexpected error
   */
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BlueprintImportPreviewDto preview(@RequestParam("file") @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The uploaded blueprint export is empty.");
    }
    if (file.getSize() > MAX_EXPORT_BYTES) {
      log.warn(
          "Blueprint import preview proxy: upload of {} bytes refused, the cap is {} bytes",
          file.getSize(),
          MAX_EXPORT_BYTES);
      throw new ResponseStatusException(
          HttpStatus.CONTENT_TOO_LARGE, "The uploaded blueprint export is too large.");
    }
    try {
      byte[] bytes = file.getBytes();
      String filename =
          file.getOriginalFilename() != null ? file.getOriginalFilename() : "blueprints.json";
      MultipartBodyBuilder builder = new MultipartBodyBuilder();
      builder
          .part(
              "file",
              new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                  return filename;
                }
              })
          .contentType(MediaType.APPLICATION_OCTET_STREAM);

      return webClient
          .post()
          .uri("/api/v1/personal-blueprints/import/preview")
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(BodyInserters.fromMultipartData(builder.build()))
          .retrieve()
          .bodyToMono(BlueprintImportPreviewDto.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn(
          "Blueprint import preview proxy: backend {} — {}", e.getStatusCode(), e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Blueprint import preview proxy: unexpected error", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import preview.");
    }
  }

  /**
   * Relays the user's reviewed import resolutions to the backend apply endpoint and returns the
   * summary.
   *
   * @param resolutions the per-name resolutions staged in the preview modal
   * @return the apply summary (added / aliases learned / skipped / already owned)
   */
  @PostMapping("/apply")
  public BlueprintImportResultDto apply(
      @RequestBody List<BlueprintImportResolutionDto> resolutions) {
    List<BlueprintImportResolutionDto> list = resolutions == null ? List.of() : resolutions;
    try {
      BlueprintImportResultDto result =
          backendApiClient.post(
              "/api/v1/personal-blueprints/import/apply",
              new BlueprintImportApplyRequest(list),
              BlueprintImportResultDto.class);
      return result == null ? new BlueprintImportResultDto(0, 0, 0, 0, 0) : result;
    } catch (Exception e) {
      log.error("Blueprint import apply proxy failed for {} resolution(s)", list.size(), e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import apply.");
    }
  }
}
