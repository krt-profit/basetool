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

package de.greluc.krt.profit.basetool.backend.dashboard.internal;

import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the single shared announcement row.
 *
 * <p>The database permits one current row; reads exclude retained non-current records.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementService {

  private final AnnouncementRepository announcementRepository;

  /**
   * Returns the latest announcement with non-blank content, suitable for the home-page banner.
   * Empty content means "no announcement is currently active" — distinguishes a freshly cleared
   * announcement from an in-progress draft an admin saved with blank content.
   *
   * @return the active announcement, or empty
   */
  public Optional<Announcement> getPublicAnnouncement() {
    return announcementRepository
        .findBySingletonTrue()
        .filter(a -> a.getContent() != null && !a.getContent().isBlank());
  }

  /**
   * Returns the singleton for editing, or an unsaved empty view with initial version zero.
   *
   * @return the admin-view announcement record, never {@code null}
   */
  public Announcement getAdminAnnouncement() {
    return announcementRepository
        .findBySingletonTrue()
        .orElseGet(() -> {
          Announcement empty = new Announcement();
          empty.setVersion(0L);
          return empty;
        });
  }

  /**
   * Updates the shared announcement's content.
   *
   * @param content new announcement body
   * @param version expected current version, or {@code null} for an unconditional save
   * @return the persisted announcement
   * @throws ObjectOptimisticLockingFailureException when the version no longer matches
   */
  @Transactional
  public Announcement updateAnnouncement(@NotNull String content, @Nullable Long version) {
    announcementRepository.ensureSingleton();
    Announcement announcement = announcementRepository.lockSingleton().orElseThrow();
    OptimisticLock.checkOptionalClient(
        announcement.getVersion(), version, Announcement.class, announcement.getId());
    announcement.setContent(content);
    Announcement saved = announcementRepository.saveAndFlush(announcement);
    log.info("Announcement updated id={} version={}", saved.getId(), saved.getVersion());
    return saved;
  }

  /** Deletes the current row so a later banner receives a fresh dismissal identity. */
  @Transactional
  public void deleteAnnouncement() {
    announcementRepository.lockSingleton().ifPresent(announcementRepository::delete);
    log.info("Announcement banner cleared");
  }
}
