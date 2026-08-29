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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.NotificationMapper;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.dto.NotificationDto;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

  @Mock private NotificationRepository repository;
  @Mock private NotificationMapper mapper;
  @InjectMocks private NotificationService service;

  private static Notification unread(UUID id) {
    Notification n =
        Notification.builder()
            .recipientUserId(RECIPIENT)
            .type(NotificationType.JOB_ORDER_CREATED)
            .entityType("JOB_ORDER")
            .entityId(UUID.randomUUID())
            .read(false)
            .build();
    n.setId(id);
    return n;
  }

  private static NotificationDto dummyDto(UUID id) {
    return new NotificationDto(
        id, "JOB_ORDER_CREATED", Map.of(), "JOB_ORDER", null, false, null, 0L, null, null);
  }

  @Test
  void markReadMarksUnreadAndSavesAndFlush() {
    // Given
    UUID id = UUID.randomUUID();
    Notification n = unread(id);
    NotificationDto dto = dummyDto(id);
    when(repository.findByIdAndRecipientUserId(id, RECIPIENT)).thenReturn(Optional.of(n));
    when(repository.saveAndFlush(n)).thenReturn(n);
    when(mapper.toDto(n)).thenReturn(dto);

    // When
    NotificationDto result = service.markRead(RECIPIENT, id);

    // Then
    assertTrue(n.isRead());
    assertNotNull(n.getReadAt());
    assertSame(dto, result);
    verify(repository).saveAndFlush(n);
  }

  @Test
  void markReadAlreadyReadKeepsReadAt() {
    // Given
    UUID id = UUID.randomUUID();
    Notification n = unread(id);
    Instant readAt = Instant.parse("2026-01-01T00:00:00Z");
    n.setRead(true);
    n.setReadAt(readAt);
    when(repository.findByIdAndRecipientUserId(id, RECIPIENT)).thenReturn(Optional.of(n));
    when(repository.saveAndFlush(n)).thenReturn(n);
    when(mapper.toDto(n)).thenReturn(dummyDto(id));

    // When
    service.markRead(RECIPIENT, id);

    // Then
    assertEquals(readAt, n.getReadAt());
  }

  @Test
  void markReadForeignOrUnknownThrowsNotFound() {
    // Given
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndRecipientUserId(id, RECIPIENT)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(EntityNotFoundException.class, () -> service.markRead(RECIPIENT, id));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void deleteOwnDeletesWhenFound() {
    // Given
    UUID id = UUID.randomUUID();
    Notification n = unread(id);
    when(repository.findByIdAndRecipientUserId(id, RECIPIENT)).thenReturn(Optional.of(n));

    // When
    service.deleteOwn(RECIPIENT, id);

    // Then
    verify(repository).delete(n);
  }

  @Test
  void deleteOwnForeignOrUnknownThrowsNotFound() {
    // Given
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndRecipientUserId(id, OTHER)).thenReturn(Optional.empty());

    // When / Then
    assertThrows(EntityNotFoundException.class, () -> service.deleteOwn(OTHER, id));
    verify(repository, never()).delete(any());
  }

  @Test
  void markAllReadDelegatesAndReturnsCount() {
    when(repository.markAllReadForRecipient(eq(RECIPIENT), any(Instant.class))).thenReturn(3);
    assertEquals(3, service.markAllRead(RECIPIENT));
  }

  @Test
  void deleteAllReadDelegatesAndReturnsCount() {
    when(repository.deleteAllReadForRecipient(RECIPIENT)).thenReturn(2);
    assertEquals(2, service.deleteAllRead(RECIPIENT));
  }

  @Test
  void unreadCountDelegates() {
    when(repository.countByRecipientUserIdAndReadFalse(RECIPIENT)).thenReturn(5L);
    assertEquals(5L, service.unreadCount(RECIPIENT));
  }

  @Test
  void listRecentOwnClampsLimitToFifty() {
    when(repository.findByRecipientUserIdOrderByCreatedAtDesc(eq(RECIPIENT), any(Pageable.class)))
        .thenReturn(java.util.List.of());

    service.listRecentOwn(RECIPIENT, 1000);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(repository).findByRecipientUserIdOrderByCreatedAtDesc(eq(RECIPIENT), captor.capture());
    assertEquals(50, captor.getValue().getPageSize());
  }

  @Test
  void listRecentOwnClampsNonPositiveLimitToOne() {
    when(repository.findByRecipientUserIdOrderByCreatedAtDesc(eq(RECIPIENT), any(Pageable.class)))
        .thenReturn(java.util.List.of());

    service.listRecentOwn(RECIPIENT, 0);

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(repository).findByRecipientUserIdOrderByCreatedAtDesc(eq(RECIPIENT), captor.capture());
    assertEquals(1, captor.getValue().getPageSize());
  }

  // covers REQ-NOTIF-019 — the inbox-list sort whitelist includes id, so PaginationUtil appends it
  // as a stable tiebreaker. Without a total order, two notifications sharing a createdAt instant
  // could reorder between page fetches and the load-more would silently skip a row at the boundary.
  @Test
  void listSortWhitelistYieldsStableCreatedAtDescWithIdTiebreaker() {
    org.springframework.data.domain.Sort sort =
        de.greluc.krt.profit.basetool.backend.web.PaginationUtil.createPageRequest(
                0,
                50,
                "createdAt,desc",
                NotificationService.SORTABLE_FIELDS,
                NotificationService.DEFAULT_SORT_FIELD)
            .getSort();

    org.springframework.data.domain.Sort.Order createdAt = sort.getOrderFor("createdAt");
    org.springframework.data.domain.Sort.Order id = sort.getOrderFor("id");
    assertNotNull(createdAt, "createdAt must drive the primary ordering");
    assertEquals(
        org.springframework.data.domain.Sort.Direction.DESC,
        createdAt.getDirection(),
        "newest first");
    assertNotNull(id, "id must be appended as the deterministic tiebreaker (REQ-NOTIF-019)");
  }
}
