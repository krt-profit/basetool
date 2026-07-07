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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the {@code job_type} reference table.
 *
 * <p>Job types form a tree (each row may have a parent); the {@code archetype} enum classifies the
 * top-level family. Soft-delete via {@code active=false} rather than row removal so missions that
 * reference a retired job type keep working. Case-insensitive uniqueness on name is enforced
 * explicitly (with a localized 409 message) instead of relying on the DB unique index.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobTypeService {

  private final JobTypeRepository jobTypeRepository;

  /**
   * Used to clear the derived {@code is_mission_lead_participant} flag on participants whose
   * planned job type loses the Einsatzleiter designation, so a stale flag cannot falsely trip the
   * {@code uq_mission_participant_single_lead} partial unique index (#1113).
   */
  private final MissionParticipantRepository missionParticipantRepository;

  /**
   * Returns the unpaged active job-type list for a dropdown, optionally filtered by archetype.
   *
   * @param archetype optional filter; null means "all active types"
   * @return cached list
   */
  @Cacheable(cacheNames = CacheConfig.JOB_TYPES_CACHE)
  public List<JobType> getJobTypes(@Nullable JobTypeArchetype archetype) {
    if (archetype == null) {
      return jobTypeRepository.findByActiveTrue();
    }
    return jobTypeRepository.findByArchetypeAndActiveTrue(archetype);
  }

  /**
   * Paged variant for the admin list with an {@code includeInactive} flag for soft-deleted entries.
   *
   * @param archetype optional archetype filter
   * @param pageable page request
   * @param includeInactive true to include soft-deleted rows
   * @return cached page
   */
  @Cacheable(cacheNames = CacheConfig.JOB_TYPES_CACHE)
  public Page<JobType> getJobTypes(
      @Nullable JobTypeArchetype archetype, @NotNull Pageable pageable, boolean includeInactive) {
    if (archetype == null) {
      return includeInactive
          ? jobTypeRepository.findAll(pageable)
          : jobTypeRepository.findByActiveTrue(pageable);
    }
    return includeInactive
        ? jobTypeRepository.findByArchetype(archetype, pageable)
        : jobTypeRepository.findByArchetypeAndActiveTrue(archetype, pageable);
  }

  /**
   * Persists a new job type. Resolves the parent reference via id so the caller can pass a shallow
   * parent (id-only stub from a DTO). Duplicate name throws {@link DuplicateEntityException} → 409.
   *
   * @param jobType transient entity
   * @return the persisted job type
   * @throws DuplicateEntityException when the name collides with an existing row
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the supplied
   *     parent id does not resolve
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.JOB_TYPES_CACHE, allEntries = true)
  public JobType createJobType(@NotNull JobType jobType) {
    if (jobTypeRepository.existsByNameIgnoreCase(jobType.getName())) {
      throw new DuplicateEntityException(
          "A Job Type with the name '" + jobType.getName() + "' already exists.");
    }
    if (jobType.getParent() != null && jobType.getParent().getId() != null) {
      JobType parent =
          jobTypeRepository
              .findById(jobType.getParent().getId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "Parent JobType not found"));
      jobType.setParent(parent);
    } else {
      jobType.setParent(null);
    }
    applyMissionLeadDesignation(jobType, jobType.isMissionLead());
    return jobTypeRepository.save(jobType);
  }

  /**
   * Updates an existing job type. Optimistic-lock check is explicit (the DTO carries the expected
   * version), duplicate-name check excludes the row being edited so a self-rename is a no-op.
   *
   * @param id job type primary key
   * @param jobTypeDto update payload
   * @return the persisted job type
   * @throws DuplicateEntityException when the new name collides with a different row
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.JOB_TYPES_CACHE, allEntries = true)
  public JobType updateJobType(@NotNull UUID id, @NotNull JobTypeDto jobTypeDto) {
    if (jobTypeRepository.existsByNameIgnoreCaseAndIdNot(jobTypeDto.name(), id)) {
      throw new DuplicateEntityException(
          "A Job Type with the name '" + jobTypeDto.name() + "' already exists.");
    }
    JobType jobType =
        jobTypeRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "JobType not found"));

    OptimisticLock.check(jobType.getVersion(), jobTypeDto.version(), JobType.class, id);

    jobType.setName(jobTypeDto.name());
    jobType.setDescription(jobTypeDto.description());
    jobType.setArchetype(jobTypeDto.archetype());
    jobType.setLeadershipRole(jobTypeDto.isLeadershipRole());
    applyMissionLeadDesignation(jobType, Boolean.TRUE.equals(jobTypeDto.isMissionLead()));

    if (jobTypeDto.parentId() != null) {
      JobType parent =
          jobTypeRepository
              .findById(jobTypeDto.parentId())
              .orElseThrow(
                  () ->
                      new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                          "Parent JobType not found"));
      jobType.setParent(parent);
    } else {
      jobType.setParent(null);
    }

    return jobTypeRepository.save(jobType);
  }

  /**
   * Soft-deletes a job type by flipping {@code active=false}. Hard delete would orphan every
   * mission participant that still references the job type — the soft-delete keeps history usable.
   *
   * @param id job type primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.JOB_TYPES_CACHE, allEntries = true)
  public void deleteJobType(@NotNull UUID id) {
    JobType jobTypeToDeactivate =
        jobTypeRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "JobType not found"));

    jobTypeToDeactivate.setActive(false);
    jobTypeRepository.save(jobTypeToDeactivate);
  }

  /**
   * Reverses a soft-delete by flipping {@code active=true}. ADMIN-only at the controller layer.
   *
   * @param id job type primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.JOB_TYPES_CACHE, allEntries = true)
  public void activateJobType(@NotNull UUID id) {
    JobType jobTypeToActivate =
        jobTypeRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new de.greluc.krt.profit.basetool.backend.exception.NotFoundException(
                        "JobType not found"));

    jobTypeToActivate.setActive(true);
    jobTypeRepository.save(jobTypeToActivate);
  }

  /**
   * Applies the single "Einsatzleiter" (mission lead) designation to a job type. When {@code wants}
   * is {@code false} the flag is cleared. When {@code true} the job type must be a {@link
   * JobTypeArchetype#MISSION} leadership role (else {@link IllegalArgumentException} → 400), and
   * any other job type currently carrying the designation is cleared first so at most one type is
   * the Einsatzleiter (the DB partial unique index is the backstop). Operates on managed entities
   * and relies on the caller's enclosing transaction.
   *
   * @param jobType the job type being created/updated (its archetype + leadership flag are already
   *     set)
   * @param wants whether the caller wants this job type to be the Einsatzleiter designation
   * @throws IllegalArgumentException when designating a non-MISSION or non-leadership job type
   */
  private void applyMissionLeadDesignation(@NotNull JobType jobType, boolean wants) {
    if (!wants) {
      jobType.setMissionLead(false);
      // #1113: a job type losing the designation must drop the derived participant flag too, else a
      // stale is_mission_lead_participant=true row keeps tripping the partial unique index.
      if (jobType.getId() != null) {
        missionParticipantRepository.clearMissionLeadFlagForJobType(jobType.getId());
      }
      return;
    }
    if (jobType.getArchetype() != JobTypeArchetype.MISSION || !jobType.isLeadershipRole()) {
      throw new IllegalArgumentException(
          "Only a MISSION leadership role can be designated as the Einsatzleiter (mission lead).");
    }
    for (JobType current : jobTypeRepository.findAllMissionLead()) {
      if (jobType.getId() == null || !jobType.getId().equals(current.getId())) {
        current.setMissionLead(false);
        jobTypeRepository.save(current);
        // The demoted type's participants must lose their derived mission-lead flag too (#1113).
        missionParticipantRepository.clearMissionLeadFlagForJobType(current.getId());
      }
    }
    jobType.setMissionLead(true);
  }
}
