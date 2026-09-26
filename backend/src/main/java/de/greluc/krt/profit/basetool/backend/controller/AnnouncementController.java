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

import de.greluc.krt.profit.basetool.backend.mapper.AnnouncementMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.AnnouncementDto;
import de.greluc.krt.profit.basetool.backend.service.AnnouncementService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over the single shared announcement: public {@code GET}, {@code GET /admin} that
 * returns the record even when blank, and ADMIN/OFFICER-only PUT/DELETE. The class-level {@code
 * isAuthenticated()} gate is the floor for every endpoint (REQ-SEC-052).
 */
@RestController
@RequestMapping("/api/v1/announcement")
@RequiredArgsConstructor
@Transactional
@PreAuthorize("isAuthenticated()")
public class AnnouncementController {

  private final AnnouncementService announcementService;
  private final AnnouncementMapper announcementMapper;

  /**
   * Returns the currently active (non-blank content) announcement, or 204 when none is active. 204
   * is intentional — frontends rely on it to hide the announcement banner entirely.
   *
   * @return announcement DTO with 200, or 204 when none active
   */
  @GetMapping
  public ResponseEntity<AnnouncementDto> getPublicAnnouncement() {
    return announcementService
        .getPublicAnnouncement()
        .map(announcementMapper::toDto)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.noContent().build());
  }

  /**
   * Admin-view announcement — returns the existing row even when blank so the edit form pre-fills
   * with the last saved content instead of forcing the admin to start over.
   *
   * @return the announcement DTO
   */
  @GetMapping("/admin")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public AnnouncementDto getAdminAnnouncement() {
    return announcementMapper.toDto(announcementService.getAdminAnnouncement());
  }

  /**
   * Updates the shared announcement with optimistic-lock check.
   *
   * @param request new content + expected version
   * @return the persisted DTO
   */
  @PutMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public AnnouncementDto updateAnnouncement(
      @NotNull @RequestBody @Valid AnnouncementRequest request) {
    return announcementMapper.toDto(
        announcementService.updateAnnouncement(request.getContent(), request.getVersion()));
  }

  /** Removes the announcement entirely. Next PUT creates a fresh row. */
  @DeleteMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteAnnouncement() {
    announcementService.deleteAnnouncement();
  }

  /** Request body for {@link #updateAnnouncement}. */
  @Data
  public static class AnnouncementRequest {
    @NotBlank private String content;
    @jakarta.validation.constraints.NotNull private Long version;
  }
}
