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
 * Frontend proxy for the blueprint import flow (#327, Phase 6). Two AJAX endpoints used by the
 * Blueprints-page import modal:
 *
 * <ul>
 *   <li>{@code POST /personal-inventory/blueprints/import/preview} — forwards the uploaded
 *       blueprint export JSON multipart (SCMDB log-watcher, Basetool Blueprint Extractor, or
 *       scmdb.net profile / tracking export) to the backend {@code POST
 *       /api/v1/personal-blueprints/import/preview} via the authenticated {@link WebClient} (which
 *       attaches the OAuth2 token) and returns the typed preview unchanged. A backend parse failure
 *       (400) is surfaced as-is so the user sees a meaningful error.
 *   <li>{@code POST /personal-inventory/blueprints/import/apply} — wraps the resolution list in the
 *       backend apply request and relays it via {@link BackendApiClient}.
 * </ul>
 *
 * <p>Returned as a {@code @RestController} (raw JSON), separate from the page controller, so the
 * import modal can drive preview / apply without a full navigation. Both endpoints are
 * authenticated; the backend re-derives the owner from the relayed JWT.
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
   * Returns the one-click ingest handoff staged for the current user (epic #639, REQ-INGEST-004):
   * the desktop extractor sent a blueprint export and opened {@code
   * /personal-inventory/blueprints?handoff=<id>}; the import JS POSTs the id here and renders the
   * staged preview without an upload. Single-use and scoped to the session subject — an unknown,
   * expired, consumed or foreign id is a 404 (no IDOR leak), which the JS treats as "nothing
   * staged".
   *
   * <p>Deliberately a <strong>POST</strong>, not a GET: consuming the handoff is a single-use,
   * state-changing operation (an atomic Redis {@code GETDEL}), so it must not ride a cacheable /
   * prefetchable safe method. The navigational page GET ({@code /personal-inventory/blueprints})
   * already never consumes; keeping the consume itself off any GET means a speculative browser
   * prefetch or a duplicate load cannot burn the token before the real pickup (REQ-INGEST-004, the
   * 2026-07-19 double-GET incident that hit the refinery surface).
   *
   * @param handoff the handoff id from the {@code ?handoff=} parameter
   * @param principal the authenticated user (its subject scopes the staged lookup)
   * @return the staged blueprint import preview
   * @throws ResponseStatusException 404 when there is no staged handoff for this user/id
   */
  @PostMapping("/staged")
  public BlueprintImportPreviewDto staged(
      @RequestParam("handoff") String handoff, @AuthenticationPrincipal OidcUser principal) {
    String sub = principal != null ? principal.getSubject() : null;
    return ingestHandoffService
        .consume(sub, handoff, HandoffKind.BLUEPRINT, BlueprintImportPreviewDto.class)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /**
   * Proxies a blueprint export JSON upload to the backend import-preview endpoint and returns the
   * resolution preview. The backend persists nothing at this step.
   *
   * @param file the uploaded blueprint export JSON (SCMDB log-watcher, Basetool BP Extractor, or
   *     scmdb.net export)
   * @return the import preview (per-name rows + status counts)
   * @throws ResponseStatusException with the backend's status if the upload is rejected (e.g. 400
   *     for an empty / malformed file), or 500 on an unexpected error
   */
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public BlueprintImportPreviewDto preview(@RequestParam("file") @NotNull MultipartFile file) {
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
