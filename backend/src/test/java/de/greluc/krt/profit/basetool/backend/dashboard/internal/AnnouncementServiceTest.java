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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

  @Mock private AnnouncementRepository announcementRepository;

  @InjectMocks private AnnouncementService announcementService;

  @Test
  void getAdminAnnouncement_ShouldReturnLatest() {
    Announcement latest = new Announcement();
    latest.setContent("Latest Info");
    latest.setUpdatedAt(Instant.now());

    Announcement old = new Announcement();
    old.setContent("Old Info");
    old.setUpdatedAt(Instant.now().minus(1, ChronoUnit.DAYS));

    when(announcementRepository.findBySingletonTrue()).thenReturn(Optional.of(latest));

    Announcement result = announcementService.getAdminAnnouncement();

    assertEquals("Latest Info", result.getContent());
  }

  @Test
  void getAdminAnnouncement_ShouldReturnActiveOverEmpty() {
    Announcement emptyZombie = new Announcement();

    Announcement active = new Announcement();
    active.setContent("Active Info");
    active.setUpdatedAt(Instant.now());

    when(announcementRepository.findBySingletonTrue()).thenReturn(Optional.of(active));

    Announcement result = announcementService.getAdminAnnouncement();

    assertEquals("Active Info", result.getContent());
  }

  @Test
  void updateAnnouncement_ShouldUpdateContent() {
    Announcement existing = new Announcement();
    existing.setContent("Old Info");
    existing.setUpdatedAt(Instant.now());
    existing.setVersion(0L);

    when(announcementRepository.lockSingleton()).thenReturn(Optional.of(existing));
    when(announcementRepository.saveAndFlush(any(Announcement.class)))
        .thenAnswer(i -> i.getArguments()[0]);

    Announcement result = announcementService.updateAnnouncement("New Info", 0L);

    assertEquals("New Info", result.getContent());
    verify(announcementRepository).saveAndFlush(existing);
  }

  @Test
  void deleteAnnouncement_onAnAbsentSingletonDoesNotCreateOne() {
    announcementService.deleteAnnouncement();
    verify(announcementRepository).lockSingleton();
    verify(announcementRepository, never()).ensureSingleton();
  }

  @Test
  void deleteAnnouncementDeletesOnlyTheCurrentSingleton() {
    Announcement current = new Announcement();
    when(announcementRepository.lockSingleton()).thenReturn(Optional.of(current));
    announcementService.deleteAnnouncement();
    verify(announcementRepository).delete(current);
    verify(announcementRepository, never()).deleteAll();
  }

  @Test
  void updateAnnouncement_withStaleVersion_throwsOptimisticLockConflictAndSavesNothing() {
    Announcement existing = new Announcement();
    existing.setContent("Old Info");
    existing.setUpdatedAt(Instant.now());
    existing.setVersion(3L);
    when(announcementRepository.lockSingleton()).thenReturn(Optional.of(existing));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> announcementService.updateAnnouncement("New Info", 2L));

    assertEquals("Old Info", existing.getContent());
    verify(announcementRepository, never()).saveAndFlush(any(Announcement.class));
  }

  @Test
  void updateAnnouncement_withoutVersion_savesUnconditionally() {
    Announcement existing = new Announcement();
    existing.setContent("Old Info");
    existing.setUpdatedAt(Instant.now());
    existing.setVersion(3L);
    when(announcementRepository.lockSingleton()).thenReturn(Optional.of(existing));
    when(announcementRepository.saveAndFlush(any(Announcement.class)))
        .thenAnswer(i -> i.getArguments()[0]);

    assertEquals("New Info", announcementService.updateAnnouncement("New Info", null).getContent());
  }

  @Test
  void getAdminAnnouncement_onEmptyTableReturnsAnUnsavedEmptyView() {
    when(announcementRepository.findBySingletonTrue()).thenReturn(Optional.empty());
    Announcement result = announcementService.getAdminAnnouncement();

    assertNull(result.getContent());
    assertEquals(0L, result.getVersion());
    verify(announcementRepository, never()).saveAndFlush(any(Announcement.class));
    verify(announcementRepository, never()).ensureSingleton();
  }

  @Test
  void getPublicAnnouncement_ignoresBlankRows() {
    Announcement blank = new Announcement();
    blank.setContent("   ");
    blank.setUpdatedAt(Instant.now());
    when(announcementRepository.findBySingletonTrue()).thenReturn(Optional.of(blank));

    assertTrue(announcementService.getPublicAnnouncement().isEmpty());
  }
}
