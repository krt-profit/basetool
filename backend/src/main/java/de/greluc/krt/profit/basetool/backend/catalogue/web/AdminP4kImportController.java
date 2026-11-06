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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobMapper;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobRunner;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kImportJobService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kUploadGate;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin-only endpoints that run the P4K catalogue import (catalogue version 2) as asynchronous
 * background jobs. An upload passes the synchronous {@link P4kUploadGate} and then only enqueues a
 * job and returns {@code 202 Accepted}; {@link P4kImportJobRunner} does the work and writes the
 * result onto the job, which the page polls.
 *
 * <ul>
 *   <li>{@code POST /jobs} — upload a catalog and enqueue a PREVIEW (dry-run) job.
 *   <li>{@code GET /jobs} — list recent jobs.
 *   <li>{@code GET /jobs/{id}} — poll one job.
 *   <li>{@code POST /jobs/{id}/apply} — enqueue an APPLY job from a finished preview's stored
 *       upload.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/catalog/admin/import/p4k")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Tag(
    name = "Admin – P4K Import",
    description =
        "Administrator endpoints for importing the p4kx catalogue (schemaVersion 2, LIVE) as"
            + " asynchronous background jobs.")
@SecurityRequirement(name = "bearer-jwt")
@Slf4j
public class AdminP4kImportController {

  private final P4kImportJobService jobService;
  private final P4kImportJobRunner jobRunner;
  private final P4kImportJobMapper jobMapper;
  private final P4kUploadGate uploadGate;

  /**
   * Checks an uploaded catalogue at the gate and enqueues a PREVIEW (dry-run) job. Returns
   * immediately with the {@code PENDING} job; the background worker reconciles it, and the page
   * polls {@link #getJob(UUID)} for the result. A refused upload creates no job (REQ-P4K-001).
   *
   * @param file the uploaded catalogue: {@code .json}, {@code .json.gz} or {@code .json.zst}
   * @param adminUserId the authenticated administrator (for the audit {@code created_by})
   * @param authentication the administrator's authentication, which the background run acts under
   * @return {@code 202 Accepted} with the enqueued job
   */
  @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Upload a P4K catalogue and enqueue an async preview (dry-run) job.")
  @ApiResponses({
    @ApiResponse(responseCode = "202", description = "Preview job enqueued."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Refused by the gate (P4K_UPLOAD_REJECTED): not schemaVersion 2, not LIVE, no Build,"
                + " or too large."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  @NotNull
  public ResponseEntity<P4kImportJobDto> enqueuePreview(
      @RequestParam("file") @NotNull MultipartFile file,
      @CurrentUserId @NotNull UUID adminUserId,
      @NotNull Authentication authentication) {
    P4kImportJob job =
        jobService.createPreviewJob(
            uploadGate.check(readBytes(file)), file.getOriginalFilename(), adminUserId);
    jobRunner.run(job.getId(), P4kImportJobKind.PREVIEW, false, authentication);
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
   * immediately with the {@code PENDING} apply job; the background worker reconciles the master
   * data, records the run and the page polls for the result.
   *
   * @param id the SUCCEEDED preview job to apply
   * @param createItemsAndShips {@code true} to create unmatched playable items and ships
   *     (REQ-P4K-009); {@code false} (default) creates nothing
   * @param adminUserId the authenticated administrator (for the audit {@code created_by})
   * @param authentication the administrator's authentication, which the background run acts under
   * @return {@code 202 Accepted} with the enqueued apply job
   */
  @PostMapping("/jobs/{id}/apply")
  @Operation(summary = "Enqueue an async apply job from a finished preview.")
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
      @RequestParam(value = "createItemsAndShips", defaultValue = "false")
          boolean createItemsAndShips,
      @CurrentUserId @NotNull UUID adminUserId,
      @NotNull Authentication authentication) {
    P4kImportJob job = jobService.createApplyJob(id, createItemsAndShips, adminUserId);
    jobRunner.run(job.getId(), P4kImportJobKind.APPLY, createItemsAndShips, authentication);
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
    } catch (IOException _) {
      throw new BadRequestException("The uploaded file could not be read.");
    }
  }
}
