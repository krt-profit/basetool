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

import de.greluc.krt.profit.basetool.backend.mapper.NotificationMapper;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped service for the per-user notification inbox.
 *
 * <p>Every read and mutation is keyed by the caller's Keycloak {@code sub}; an id that is unknown
 * <em>or</em> owned by someone else yields {@link EntityNotFoundException} (→ HTTP 404) so a caller
 * can neither read, mark, nor delete a peer's notification (REQ-NOTIF-004). The inbox is not
 * org-unit scoped and so injects neither {@code OwnerScopeService} nor {@code AuthHelperService} —
 * the {@code recipientUserId} the controller passes in <em>is</em> the authorization boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

  /**
   * Sort properties accepted on the list endpoint; restricting them prevents unstable sorting.
   * {@code id} is included so {@link
   * de.greluc.krt.profit.basetool.backend.web.PaginationUtil#createPageRequest} appends it as a
   * deterministic tiebreaker — without it, two notifications sharing a {@code createdAt} instant
   * could reorder between page fetches and the inbox page's load-more (REQ-NOTIF-019) would skip a
   * row at the page boundary.
   */
  public static final Set<String> SORTABLE_FIELDS =
      Set.of("id", "createdAt", "readAt", "read", "type");

  /** Default sort property for the list endpoint (most-recent-first when combined with DESC). */
  public static final String DEFAULT_SORT_FIELD = "createdAt";

  private final NotificationRepository notificationRepository;
  private final NotificationMapper mapper;

  /**
   * Owner-scoped paged list of the caller's notifications.
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @param pageable page request (sort fields whitelisted by {@link #SORTABLE_FIELDS})
   * @return the page of notification DTOs
   */
  public Page<NotificationDto> listOwn(@NotNull UUID recipientUserId, @NotNull Pageable pageable) {
    return notificationRepository
        .findAllByRecipientUserId(recipientUserId, pageable)
        .map(mapper::toDto);
  }

  /**
   * Returns the caller's most recent notifications (newest first), capped at {@code limit}. Backs
   * the bell dropdown.
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @param limit maximum number of entries to return (clamped to a sane range)
   * @return the most-recent-first list of DTOs
   */
  public List<NotificationDto> listRecentOwn(@NotNull UUID recipientUserId, int limit) {
    int capped = Math.max(1, Math.min(limit, 50));
    return notificationRepository
        .findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, PageRequest.of(0, capped))
        .stream()
        .map(mapper::toDto)
        .toList();
  }

  /**
   * Counts the caller's unread notifications; backs the always-on bell badge.
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @return the number of unread notifications
   */
  public long unreadCount(@NotNull UUID recipientUserId) {
    return notificationRepository.countByRecipientUserIdAndReadFalse(recipientUserId);
  }

  /**
   * Marks one of the caller's notifications read (idempotent: a no-op when already read).
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @param id notification id
   * @return the persisted DTO
   * @throws EntityNotFoundException when the id is unknown or owned by someone else
   */
  @Transactional
  public NotificationDto markRead(@NotNull UUID recipientUserId, @NotNull UUID id) {
    Notification entity = loadOwn(recipientUserId, id);
    if (!entity.isRead()) {
      entity.setRead(true);
      entity.setReadAt(Instant.now());
    }
    // saveAndFlush so the bumped @Version reaches the response DTO the frontend writes back in
    // place.
    return mapper.toDto(notificationRepository.saveAndFlush(entity));
  }

  /**
   * Marks every unread notification of the caller read in one atomic statement.
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @return the number of notifications updated
   */
  @Transactional
  public int markAllRead(@NotNull UUID recipientUserId) {
    int updated = notificationRepository.markAllReadForRecipient(recipientUserId, Instant.now());
    log.debug("Marked {} notification(s) read for recipientUserId={}", updated, recipientUserId);
    return updated;
  }

  /**
   * Deletes one of the caller's notifications, regardless of its read state (REQ-NOTIF-005).
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @param id notification id
   * @throws EntityNotFoundException when the id is unknown or owned by someone else
   */
  @Transactional
  public void deleteOwn(@NotNull UUID recipientUserId, @NotNull UUID id) {
    Notification entity = loadOwn(recipientUserId, id);
    notificationRepository.delete(entity);
    log.debug("Deleted notification id={} for recipientUserId={}", id, recipientUserId);
  }

  /**
   * Deletes every read notification of the caller in one atomic statement (the "clear read"
   * action); unread notifications are kept.
   *
   * @param recipientUserId Keycloak {@code sub} of the caller
   * @return the number of notifications deleted
   */
  @Transactional
  public int deleteAllRead(@NotNull UUID recipientUserId) {
    int deleted = notificationRepository.deleteAllReadForRecipient(recipientUserId);
    log.debug("Cleared {} read notification(s) for recipientUserId={}", deleted, recipientUserId);
    return deleted;
  }

  /**
   * Deletes read notifications whose read timestamp is older than the cutoff; backs the scheduled
   * retention sweep. Independent of the user-initiated delete.
   *
   * @param cutoff delete read notifications read before this instant
   * @return the number of notifications deleted
   */
  @Transactional
  public int purgeReadOlderThan(@NotNull Instant cutoff) {
    int deleted = notificationRepository.deleteReadOlderThan(cutoff);
    if (deleted > 0) {
      log.info("Retention: deleted {} read notification(s) read before {}", deleted, cutoff);
    }
    return deleted;
  }

  @NotNull
  private Notification loadOwn(@NotNull UUID recipientUserId, @NotNull UUID id) {
    return notificationRepository
        .findByIdAndRecipientUserId(id, recipientUserId)
        .orElseThrow(
            () -> {
              log.warn(
                  "Notification access denied or not found: recipientUserId={} requested id={}",
                  recipientUserId,
                  id);
              return new EntityNotFoundException("Notification not found: " + id);
            });
  }
}
