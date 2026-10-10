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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only controller for the {@code /admin/p4k-import} page: uploads a JSON catalog extracted
 * from the Star Citizen game files as an asynchronous backend import job, polls its preview and
 * applies it.
 *
 * <p>Every action proxies to {@code /api/v1/admin/import/p4k/jobs} through {@link
 * CatalogueBackendClient}, so a backend refusal is mapped like any other backend call.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/p4k-import")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminP4kImportPageController {

  /** Relays the import jobs. */
  private final CatalogueBackendClient catalogueClient;

  /** Evicts the catalogues an applied import changes. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Renders the P4K import page and exposes the proxy base URL to its script.
   *
   * @param model Thymeleaf model
   * @return the {@code admin/p4k-import} view name
   */
  @NotNull
  @GetMapping
  public String view(@NotNull Model model) {
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
    P4kImportJobDto job =
        withBackendStatus(() -> catalogueClient.enqueueP4kImport(bytes, filename));
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
    return withBackendStatus(catalogueClient::p4kImportJobs);
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
    return withBackendStatus(() -> catalogueClient.p4kImportJob(id));
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
    P4kImportJobDto job = withBackendStatus(() -> catalogueClient.applyP4kImportJob(id, seedNew));
    cacheEviction.evict(
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
}
