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

import de.greluc.krt.profit.basetool.backend.mapper.JobTypeMapper;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.JobTypeService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the job-type reference table. Read is public; mutations are OFFICER/ADMIN;
 * activate is ADMIN-only.
 */
@RestController
@RequestMapping("/api/v1/job-types")
@RequiredArgsConstructor
@Transactional
public class JobTypeController {

  private static final Set<String> ALLOWED_SORT = Set.of("name", "archetype", "id");

  private final JobTypeService jobTypeService;
  private final JobTypeMapper jobTypeMapper;

  /**
   * Paged list with optional archetype filter and {@code includeInactive} for the admin view.
   *
   * @return paged job-type DTOs
   */
  @GetMapping
  @Transactional(readOnly = true)
  public PageResponse<JobTypeDto> getAllJobTypes(
      @RequestParam(required = false) JobTypeArchetype archetype,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
    Pageable pageable = PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "name");
    Page<JobType> p = jobTypeService.getJobTypes(archetype, pageable, includeInactive);
    return PageResponse.of(p.map(jobTypeMapper::toDto));
  }

  /**
   * Creates a new job type. Duplicate name → 409 with code {@code DUPLICATE_ENTITY}.
   *
   * @param jobTypeDto create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public JobTypeDto createJobType(@RequestBody @Valid JobTypeDto jobTypeDto) {
    JobType toCreate = jobTypeMapper.toEntity(jobTypeDto);
    // L-7: strip client-supplied id/version so create cannot become a merge()-UPSERT of another
    // row.
    toCreate.setId(null);
    toCreate.setVersion(null);
    return jobTypeMapper.toDto(jobTypeService.createJobType(toCreate));
  }

  /**
   * Updates an existing job type. Carries optimistic-lock version in the DTO body.
   *
   * @param id job type id
   * @param jobTypeDto update payload
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public JobTypeDto updateJobType(
      @PathVariable @NotNull UUID id, @RequestBody @Valid JobTypeDto jobTypeDto) {
    return jobTypeMapper.toDto(jobTypeService.updateJobType(id, jobTypeDto));
  }

  /**
   * Soft-deletes a job type (sets {@code active=false}). Existing missions referencing the type
   * continue to work.
   *
   * @param id job type id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public void deleteJobType(@PathVariable @NotNull UUID id) {
    jobTypeService.deleteJobType(id);
  }

  /**
   * Reverses a soft-delete. ADMIN-only.
   *
   * @param id job type id
   */
  @PostMapping("/{id}/activate")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public void activateJobType(@PathVariable @NotNull UUID id) {
    jobTypeService.activateJobType(id);
  }
}
