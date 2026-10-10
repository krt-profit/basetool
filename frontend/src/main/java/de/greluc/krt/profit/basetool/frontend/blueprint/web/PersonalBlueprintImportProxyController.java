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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.frontend.model.HandoffKind;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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

  /** Sends the import preview and apply requests. */
  private final BlueprintBackendClient blueprintClient;

  private final IngestHandoffService ingestHandoffService;

  /** Resolves the localised refusals this proxy decides itself. */
  private final MessageSource messageSource;

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

  /** The message of an empty upload. */
  static final String EMPTY_KEY = "personalInventory.blueprints.import.error.empty";

  /** The message of an upload above {@link #MAX_EXPORT_BYTES}. */
  static final String TOO_LARGE_KEY = "personalInventory.blueprints.import.error.tooLarge";

  /** The problem code of an empty upload, as the frontend answers any {@code 400}. */
  private static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";

  /** The problem code of an oversized upload, as the frontend answers any {@code 413}. */
  private static final String CODE_UPLOAD_TOO_LARGE = "UPLOAD_TOO_LARGE";

  /**
   * Proxies a blueprint export JSON upload to the backend import-preview endpoint and returns the
   * resolution preview. The backend persists nothing at this step.
   *
   * <p>A refusal answers {@code application/problem+json} with a localised {@code detail} the page
   * shows: an empty upload ({@code 400}) and one above {@link #MAX_EXPORT_BYTES} ({@code 413}) are
   * refused before they are read, and a backend refusal is relayed with its status, code and
   * detail.
   *
   * @param file the uploaded blueprint export JSON (SCMDB log-watcher, Basetool BP Extractor,
   *     scmdb.net export or the {@code basetool.blueprints} envelope)
   * @return the import preview (per-name rows + status counts), or the refusal
   * @throws ResponseStatusException 500 when the upload cannot be read
   */
  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Object> preview(@RequestParam("file") @NotNull MultipartFile file) {
    if (file.isEmpty()) {
      return BackendErrorResponses.problem(
          HttpStatus.BAD_REQUEST, CODE_VALIDATION_FAILED, message(EMPTY_KEY));
    }
    if (file.getSize() > MAX_EXPORT_BYTES) {
      log.warn(
          "Blueprint import preview proxy: upload of {} bytes refused, the cap is {} bytes",
          file.getSize(),
          MAX_EXPORT_BYTES);
      return BackendErrorResponses.problem(
          HttpStatus.CONTENT_TOO_LARGE, CODE_UPLOAD_TOO_LARGE, message(TOO_LARGE_KEY));
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      log.error("Blueprint import preview proxy: the upload could not be read", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import preview.");
    }
    String filename =
        file.getOriginalFilename() != null ? file.getOriginalFilename() : "blueprints.json";
    return BackendErrorResponses.relay(
        log,
        "Blueprint import preview",
        () -> ResponseEntity.ok(blueprintClient.importPreview(filename, bytes)));
  }

  /**
   * Relays the user's reviewed import resolutions to the backend apply endpoint and returns the
   * summary; a backend refusal is relayed with its status, code and localised detail.
   *
   * @param resolutions the per-name resolutions staged in the preview modal
   * @return the apply summary (added / aliases learned / skipped / already owned), the relayed
   *     refusal, or an empty {@code 500} on an unexpected error
   */
  @PostMapping("/apply")
  public ResponseEntity<Object> apply(@RequestBody List<BlueprintImportResolutionDto> resolutions) {
    List<BlueprintImportResolutionDto> list = resolutions == null ? List.of() : resolutions;
    return BackendErrorResponses.relay(
        log,
        "Blueprint import apply for " + list.size() + " resolution(s)",
        () -> {
          BlueprintImportResultDto result =
              blueprintClient.importApply(new BlueprintImportApplyRequest(list));
          return ResponseEntity.ok(
              result == null ? new BlueprintImportResultDto(0, 0, 0, 0, 0) : result);
        });
  }

  /**
   * Resolves a message of this proxy in the caller's locale.
   *
   * @param key the message key
   * @return the localised text, or the key when it is missing
   */
  private @NotNull String message(@NotNull String key) {
    return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
  }
}
