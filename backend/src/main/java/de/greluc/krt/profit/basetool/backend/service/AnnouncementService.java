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

import de.greluc.krt.profit.basetool.backend.model.Announcement;
import de.greluc.krt.profit.basetool.backend.repository.AnnouncementRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.Comparator;
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
 * <p>The table holds at most one logically active record — public callers see "the latest entry
 * with non-blank content"; admins see the same record so edits never accumulate duplicates. The
 * delete endpoint clears the whole table, which lets the next save start clean.
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
    return announcementRepository.findAll().stream()
        .filter(a -> a.getContent() != null && !a.getContent().isBlank())
        .max(
            Comparator.comparing(
                Announcement::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  /**
   * Returns the announcement the admin edit screen should load. Prefers the active record so an
   * empty content does not produce a "blank screen" admin experience; falls back to the latest
   * record overall (including blanks) so admins reuse the existing row across edits; finally
   * creates an empty row on first use — saves accumulate-duplicate rows from an unfortunate race.
   *
   * @return the admin-view announcement record, never {@code null}
   */
  public Announcement getAdminAnnouncement() {
    // Try the latest active announcement first; fall back to the latest entry
    // overall (even if its content is empty/null) so admins reuse the existing
    // row instead of accumulating duplicates. As a last resort create one.
    return getPublicAnnouncement()
        .orElseGet(
            () ->
                announcementRepository.findAll().stream()
                    .max(
                        Comparator.comparing(
                            Announcement::getUpdatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElseGet(() -> announcementRepository.save(new Announcement())));
  }

  /**
   * Updates the shared announcement with the given content. {@code version} is the optimistic lock
   * value from the form; {@code null} bypasses the check (used for first-time create).
   *
   * @param content new announcement body
   * @param version expected current version, or {@code null} for unconditional save
   * @return the persisted announcement
   * @throws ObjectOptimisticLockingFailureException when the current row's version no longer
   *     matches the supplied {@code version}
   */
  @Transactional
  public Announcement updateAnnouncement(@NotNull String content, @Nullable Long version) {
    Announcement announcement = getAdminAnnouncement();
    OptimisticLock.checkOptionalClient(
        announcement.getVersion(), version, Announcement.class, announcement.getId());
    announcement.setContent(content);
    Announcement saved = announcementRepository.save(announcement);
    log.info("Announcement updated id={} version={}", saved.getId(), saved.getVersion());
    return saved;
  }

  /**
   * Removes every announcement row. The next save creates a fresh row — no soft-delete needed
   * because there is only ever one logical announcement.
   */
  @Transactional
  public void deleteAnnouncement() {
    long removed = announcementRepository.count();
    announcementRepository.deleteAll();
    log.info("Announcement banner cleared ({} row(s) removed)", removed);
  }
}
