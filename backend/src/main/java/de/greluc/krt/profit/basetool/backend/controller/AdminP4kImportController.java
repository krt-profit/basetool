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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.P4kImportJobMapper;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportJobDto;
import de.greluc.krt.profit.basetool.backend.service.P4kImportJobRunner;
import de.greluc.krt.profit.basetool.backend.service.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin-only endpoints that drive the KRT P4K Reader catalog import as <b>asynchronous background
 * jobs</b>. An administrator uploads the single JSON catalog the external KRT P4K Reader extracts
 * from the game's {@code Data/Game2.dcb}; rather than parsing and reconciling ~60k records inside
 * the request (which outran the frontend / proxy timeouts and hung the page), the upload only
 * enqueues a job and returns {@code 202 Accepted} immediately. A single-thread {@code @Async}
 * worker ({@link P4kImportJobRunner}) does the heavy work in the background and writes the per-type
 * result back onto the job, which the page polls.
 *
 * <ul>
 *   <li>{@code POST /jobs} — upload a catalog and enqueue a PREVIEW (dry-run) job.
 *   <li>{@code GET /jobs} — list recent jobs (status + result when finished).
 *   <li>{@code GET /jobs/{id}} — poll one job.
 *   <li>{@code POST /jobs/{id}/apply} — enqueue an APPLY job from a finished preview's stored
 *       upload (optionally seeding new rows), without re-uploading.
 * </ul>
 *
 * <p>The {@code ADMIN} role is enforced at this boundary; the services stay free of the security
 * context (the enqueuing administrator's id is read from the validated JWT here and passed down).
 */
@RestController
@RequestMapping("/api/v1/admin/import/p4k")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – P4K Import",
    description =
        "Administrator endpoints for importing the KRT P4K Reader catalog (Game2.dcb master data)"
            + " as asynchronous background jobs.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class AdminP4kImportController {

  private final P4kImportJobService jobService;
  private final P4kImportJobRunner jobRunner;
  private final P4kImportJobMapper jobMapper;

  /**
   * Uploads a P4K catalog and enqueues a PREVIEW (dry-run) job. Returns immediately with the {@code
   * PENDING} job; the background worker parses and reconciles it, and the page polls {@link
   * #getJob(UUID)} for the result.
   *
   * @param file the uploaded P4K catalog JSON
   * @param jwt the authenticated administrator (for the audit {@code created_by})
   * @return {@code 202 Accepted} with the enqueued job
   */
  @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Upload a P4K catalog and enqueue an async preview (dry-run) job.")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "Preview job enqueued."),
    @ApiResponse(responseCode = "400", description = "File empty or unreadable."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  @NotNull
  public ResponseEntity<P4kImportJobDto> enqueuePreview(
      @RequestParam("file") @NotNull MultipartFile file,
      @AuthenticationPrincipal @NotNull Jwt jwt) {
    byte[] bytes = readBytes(file);
    P4kImportJob job =
        jobService.createPreviewJob(bytes, file.getOriginalFilename(), currentUserId(jwt));
    jobRunner.run(job.getId(), P4kImportJobKind.PREVIEW, false);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobMapper.toDto(job));
  }

  /**
   * Lists the most recent import jobs, newest first, for the admin job table.
   *
   * @return the recent jobs (status, and the per-type result for finished ones)
   */
  @GetMapping("/jobs")
  @Operation(summary = "List recent P4K import jobs.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The recent jobs."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  @NotNull
  public List<P4kImportJobDto> listJobs() {
    return jobMapper.toDtos(jobService.recentJobs());
  }

  /**
   * Fetches a single import job for polling.
   *
   * @param id the job id
   * @return the job (status, plus the result / error when finished)
   */
  @GetMapping("/jobs/{id}")
  @Operation(summary = "Fetch one P4K import job (poll its status / result).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The job."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No job with the given id.")
  })
  @NotNull
  public P4kImportJobDto getJob(@PathVariable("id") @NotNull UUID id) {
    return jobMapper.toDto(jobService.getJob(id));
  }

  /**
   * Enqueues an APPLY job from a finished preview's stored upload (no re-upload needed). Returns
   * immediately with the {@code PENDING} apply job; the background worker enriches / reconciles
   * (and optionally seeds) the master data and the page polls for the result.
   *
   * @param id the SUCCEEDED preview job to apply
   * @param seedNew {@code true} to insert new {@code source = P4K} rows for unmatched player-facing
   *     records; {@code false} (default) to enrich existing rows only
   * @param jwt the authenticated administrator (for the audit {@code created_by})
   * @return {@code 202 Accepted} with the enqueued apply job
   */
  @PostMapping("/jobs/{id}/apply")
  @Operation(summary = "Enqueue an async apply job from a finished preview (optionally seeding).")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "Apply job enqueued."),
    @ApiResponse(
        responseCode = "400",
        description = "Referenced job is not a finished preview, or its upload is gone."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No job with the given id.")
  })
  @NotNull
  public ResponseEntity<P4kImportJobDto> enqueueApply(
      @PathVariable("id") @NotNull UUID id,
      @RequestParam(value = "seedNew", defaultValue = "false") boolean seedNew,
      @AuthenticationPrincipal @NotNull Jwt jwt) {
    P4kImportJob job = jobService.createApplyJob(id, seedNew, currentUserId(jwt));
    jobRunner.run(job.getId(), P4kImportJobKind.APPLY, seedNew);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobMapper.toDto(job));
  }

  /**
   * Reads the uploaded multipart body into memory, mapping an I/O failure to a 400 rather than a
   * 500.
   *
   * @param file the uploaded file
   * @return the file bytes
   * @throws BadRequestException if the file cannot be read
   */
  private byte @NotNull [] readBytes(@NotNull MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("The uploaded file could not be read.");
    }
  }

  /**
   * Extracts the enqueuing administrator's user id from the validated JWT {@code sub} (which equals
   * {@code app_user.id}).
   *
   * @param jwt the authenticated principal
   * @return the user id
   * @throws BadRequestException if the subject is not a UUID
   */
  @NotNull
  private UUID currentUserId(@NotNull Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("The authenticated subject is not a valid user id.");
    }
  }
}
