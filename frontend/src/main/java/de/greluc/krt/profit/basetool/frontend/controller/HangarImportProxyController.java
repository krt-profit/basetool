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

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proxies third-party ship-export uploads (CCU Game Fleetview, HangarXPLOR, Fleetyards, StarJump
 * FleetViewer) to {@code POST /api/v1/hangar/import/ships}, which detects the format.
 *
 * <p>Uploads above {@link #MAX_IMPORT_BYTES} are refused with {@code 413} unread; accepted ones are
 * streamed. {@code /hangar/import/fleetview} is a deprecated alias.
 */
@RestController
@RequestMapping("/hangar/import")
@RequiredArgsConstructor
@Slf4j
public class HangarImportProxyController {

  /**
   * Inclusive upper bound on a relayed ship-export upload: 8 MiB, the backend parser's cap. Also
   * published to the hangar page as {@code data-max-bytes}.
   */
  public static final long MAX_IMPORT_BYTES = 8L * 1024 * 1024;

  /** Filename sent to the backend when the browser supplied none. */
  private static final String FALLBACK_FILENAME = "shiplist.json";

  private final WebClient webClient;

  /** Resolves the localized {@code 413} message for an oversized upload. */
  private final MessageSource messageSource;

  /**
   * Proxies a ship-export JSON file upload from the browser to the backend import endpoint.
   *
   * @param file the uploaded JSON file (Fleetview, HangarXPLOR Shiplist, Fleetyards or StarJump
   *     FleetViewer)
   * @return the backend response (a {@code FleetviewImportResponseDto}) as a raw JSON map, or a
   *     {@code 413} JSON body when the upload exceeds {@link #MAX_IMPORT_BYTES}
   */
  @PostMapping(value = "/ships", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Map<?, ?>> importShips(@RequestParam("file") @NotNull MultipartFile file) {
    return forwardImport(file, "/api/v1/hangar/import/ships");
  }

  /**
   * Deprecated alias of {@link #importShips(MultipartFile)} for the Fleetview-only path.
   *
   * @param file the uploaded JSON file
   * @return the backend response (a {@code FleetviewImportResponseDto}) as a raw JSON map, or a
   *     {@code 413} JSON body when the upload exceeds {@link #MAX_IMPORT_BYTES}
   * @deprecated use {@code POST /hangar/import/ships}.
   */
  @PostMapping(value = "/fleetview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Deprecated(since = "2026-05-14", forRemoval = true)
  public ResponseEntity<Map<?, ?>> importFleetview(
      @RequestParam("file") @NotNull MultipartFile file) {
    return forwardImport(file, "/api/v1/hangar/import/fleetview");
  }

  /**
   * Forwards a multipart upload to the backend: refuses one above {@link #MAX_IMPORT_BYTES} unread,
   * streams an accepted one, maps a {@link WebClientResponseException} to a {@link
   * ResponseStatusException} with the backend's status and any other failure to {@code 500}.
   *
   * @param file uploaded multipart file
   * @param backendPath relative path on the backend (without host) to forward to
   * @return the backend response unchanged, or the {@code 413} refusal
   */
  private @NotNull ResponseEntity<Map<?, ?>> forwardImport(
      @NotNull MultipartFile file, @NotNull String backendPath) {
    if (file.getSize() > MAX_IMPORT_BYTES) {
      log.warn(
          "Hangar import proxy: upload of {} bytes refused, the cap is {} bytes",
          file.getSize(),
          MAX_IMPORT_BYTES);
      return tooLarge();
    }
    try {
      String originalFilename =
          file.getOriginalFilename() != null ? file.getOriginalFilename() : FALLBACK_FILENAME;

      MultipartBodyBuilder builder = new MultipartBodyBuilder();
      builder
          .part("file", new StreamedUpload(file, originalFilename))
          .contentType(MediaType.APPLICATION_OCTET_STREAM);

      Map<?, ?> result =
          webClient
              .post()
              .uri(backendPath)
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(BodyInserters.fromMultipartData(builder.build()))
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      return ResponseEntity.ok(result);
    } catch (WebClientResponseException e) {
      log.warn("Hangar import proxy: backend returned {} — {}", e.getStatusCode(), e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), e.getMessage());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("Hangar import proxy: unexpected error", e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during import.");
    }
  }

  /**
   * Builds the {@code 413} refusal in the JSON shape the frontend's other upload refusals use
   * ({@code GlobalExceptionHandler#handleMaxUploadSizeExceeded}), with the localized hangar message
   * in both {@code message} and {@code detail} — {@code hangar.js} shows {@code detail}.
   *
   * @return a {@code 413 Content Too Large} response with a JSON body
   */
  private @NotNull ResponseEntity<Map<?, ?>> tooLarge() {
    String message =
        messageSource.getMessage(
            "hangar.import.error.tooLarge",
            null,
            "The file is too large.",
            LocaleContextHolder.getLocale());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", "UPLOAD_TOO_LARGE");
    body.put("status", HttpStatus.CONTENT_TOO_LARGE.value());
    body.put("message", message);
    body.put("detail", message);
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  /**
   * The uploaded file as a streamed {@link org.springframework.core.io.Resource} with a filename.
   * Each {@link #getInputStream()} call reopens the part.
   */
  private static final class StreamedUpload extends AbstractResource {

    /** The container-held upload this resource streams from. */
    private final MultipartFile file;

    /** The filename announced in the part's {@code Content-Disposition}; never {@code null}. */
    private final String filename;

    /**
     * Wraps an upload for streaming.
     *
     * @param file the container-held upload
     * @param filename the filename to announce, already defaulted when the browser sent none
     */
    StreamedUpload(@NotNull MultipartFile file, @NotNull String filename) {
      this.file = file;
      this.filename = filename;
    }

    /**
     * Opens the upload's content afresh from the container's part storage.
     *
     * @return a new stream over the whole upload
     * @throws IOException when the container can no longer read the part (a torn upload)
     */
    @Override
    public @NotNull InputStream getInputStream() throws IOException {
      return file.getInputStream();
    }

    /**
     * Returns the size the container recorded for the part, so the writer can announce a length.
     *
     * @return the upload size in bytes
     */
    @Override
    public long contentLength() {
      return file.getSize();
    }

    /**
     * Returns the filename announced to the backend.
     *
     * @return the browser's filename, or {@code shiplist.json} when it sent none
     */
    @Override
    public @NotNull String getFilename() {
      return filename;
    }

    /**
     * Describes the resource for diagnostics; carries the filename only, never content.
     *
     * @return a short description naming the upload
     */
    @Override
    public @NotNull String getDescription() {
      return "hangar import upload [" + filename + "]";
    }

    /**
     * Treats two wrappers as equal when they stream the same upload under the same name.
     *
     * @param other the object to compare with
     * @return {@code true} for a wrapper of the same upload and filename
     */
    @Override
    public boolean equals(Object other) {
      return other instanceof StreamedUpload that
          && that.file == file
          && that.filename.equals(filename);
    }

    /**
     * Hashes consistently with {@link #equals(Object)}.
     *
     * @return a hash over the upload identity and the filename
     */
    @Override
    public int hashCode() {
      return System.identityHashCode(file) * 31 + filename.hashCode();
    }
  }
}
