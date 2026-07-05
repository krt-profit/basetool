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

import de.greluc.krt.profit.basetool.frontend.model.dto.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Spring MVC controller backing the {@code /admin/p4k-import} page (#326): an admin uploads a JSON
 * catalog extracted from the Star Citizen game files, which is processed as an <b>asynchronous
 * background job</b>. The upload returns immediately; the page polls the job list until each run
 * finishes, shows the per-type preview, and can then apply a finished preview, all without the page
 * hanging or hitting a request timeout.
 *
 * <p>Every action proxies to the backend admin import job endpoints ({@code
 * /api/v1/admin/import/p4k/jobs}) via the authenticated {@link WebClient}, which relays the OAuth2
 * bearer token. Enqueue / poll / apply are all quick calls, so the default short-timeout client is
 * appropriate; the heavy work happens server-side in the background worker. A backend {@link
 * WebClientResponseException} is relayed as the same HTTP status so the page JS toasts the error;
 * any other failure collapses to a 500.
 *
 * <p>Admin-only — class-level {@code @PreAuthorize("hasRole('ADMIN')")} matches the backend gate.
 */
@Controller
@RequestMapping("/admin/p4k-import")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
@Slf4j
public class AdminP4kImportPageController {

  /** Backend base path for the async import jobs. */
  private static final String JOBS_URI = "/api/v1/admin/import/p4k/jobs";

  private final WebClient webClient;

  private final BackendApiClient backendApiClient;

  /**
   * Renders the P4K import page. The page is static chrome plus the upload control and the job
   * table; the proxy endpoint base URL is exposed to the inlined JS so it can POST the picked file
   * and poll the jobs.
   *
   * @param model Thymeleaf model
   * @return the {@code admin/p4k-import} view name
   */
  @GetMapping
  public String view(Model model) {
    model.addAttribute("jobsUrl", "/admin/p4k-import/jobs");
    return "admin/p4k-import";
  }

  /**
   * Uploads a P4K catalog and enqueues a preview job. The multipart {@code file} is forwarded to
   * the backend, which stores it and returns immediately with the {@code PENDING} job.
   *
   * @param file the uploaded P4K catalog JSON
   * @return {@code 202 Accepted} with the enqueued job
   */
  @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseBody
  @NotNull
  public ResponseEntity<P4kImportJobDto> enqueuePreview(
      @RequestParam("file") @NotNull MultipartFile file) {
    byte[] bytes = readBytes(file);
    String filename =
        file.getOriginalFilename() != null ? file.getOriginalFilename() : "p4k-catalog.json";
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

    P4kImportJobDto job =
        execute(
            () ->
                webClient
                    .post()
                    .uri(JOBS_URI)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(P4kImportJobDto.class),
            "enqueue preview");
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
  }

  /**
   * Lists the recent import jobs for the page table (and its polling refresh).
   *
   * @return the recent jobs, newest first
   */
  @GetMapping("/jobs")
  @ResponseBody
  @NotNull
  public List<P4kImportJobDto> listJobs() {
    return execute(
        () ->
            webClient
                .get()
                .uri(JOBS_URI)
                .retrieve()
                .bodyToFlux(P4kImportJobDto.class)
                .collectList(),
        "list jobs");
  }

  /**
   * Fetches a single import job (the page polls this and reads it for the detail view).
   *
   * @param id the job id
   * @return the job
   */
  @GetMapping("/jobs/{id}")
  @ResponseBody
  @NotNull
  public P4kImportJobDto getJob(@PathVariable("id") @NotNull UUID id) {
    return execute(
        () ->
            webClient
                .get()
                .uri(uriBuilder -> uriBuilder.path(JOBS_URI + "/{id}").build(id))
                .retrieve()
                .bodyToMono(P4kImportJobDto.class),
        "get job");
  }

  /**
   * Enqueues an apply job from a finished preview (no re-upload), optionally seeding new rows.
   *
   * @param id the preview job to apply
   * @param seedNew whether to insert brand-new game rows that have no existing match
   * @return {@code 202 Accepted} with the enqueued apply job
   */
  @PostMapping("/jobs/{id}/apply")
  @ResponseBody
  @NotNull
  public ResponseEntity<P4kImportJobDto> applyJob(
      @PathVariable("id") @NotNull UUID id,
      @RequestParam(value = "seedNew", defaultValue = "false") boolean seedNew) {
    P4kImportJobDto job =
        execute(
            () ->
                webClient
                    .post()
                    .uri(
                        uriBuilder ->
                            uriBuilder
                                .path(JOBS_URI + "/{id}/apply")
                                .queryParam("seedNew", seedNew)
                                .build(id))
                    .retrieve()
                    .bodyToMono(P4kImportJobDto.class),
            "apply job");
    // A P4K apply rewrites master data (materials, manufacturers, ship types) and the orderable
    // item catalogue in an async backend job (REQ-DATA-007). Evict the affected frontend catalogue
    // caches at apply-enqueue time so a subsequent page read re-fetches the post-import state. The
    // eviction is deliberately eager (the backend job finishes moments later); the brief window in
    // which a concurrent read could re-cache pre-apply data is bounded by the domain TTL, and this
    // is a rare admin-only action. Preview jobs never reach this endpoint, so they never evict.
    backendApiClient.evict(
        CacheDomain.MATERIAL,
        CacheDomain.MANUFACTURER,
        CacheDomain.SHIP_TYPE,
        CacheDomain.ITEM_CATALOG);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
  }

  /**
   * Reads the uploaded multipart body into memory, mapping an I/O failure to a 400.
   *
   * @param file the uploaded file
   * @return the file bytes
   */
  private byte @NotNull [] readBytes(@NotNull MultipartFile file) {
    try {
      return file.getBytes();
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The uploaded file could not be read.");
    }
  }

  /**
   * Blocks on a backend WebClient call and maps failures uniformly: a backend {@link
   * WebClientResponseException} becomes a {@link ResponseStatusException} of the same status (so
   * the page JS toasts its own i18n message without leaking the backend body); any other failure
   * becomes a 500.
   *
   * @param call the WebClient call producing the result mono
   * @param op a short label for diagnostic logging
   * @param <T> the relayed body type
   * @return the decoded body
   */
  private <T> T execute(@NotNull Supplier<Mono<T>> call, @NotNull String op) {
    try {
      return call.get().block();
    } catch (WebClientResponseException e) {
      log.warn("P4K import proxy ({}): backend {} — {}", op, e.getStatusCode(), e.getMessage());
      throw new ResponseStatusException(e.getStatusCode(), "The P4K import request was rejected.");
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      log.error("P4K import proxy ({}): unexpected error", op, e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred during the P4K import.");
    }
  }
}
