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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.Announcement;
import de.greluc.krt.profit.basetool.backend.repository.AnnouncementRepository;
import de.greluc.krt.profit.basetool.backend.service.AnnouncementService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    when(announcementRepository.findAll()).thenReturn(java.util.List.of(old, latest));

    Announcement result = announcementService.getAdminAnnouncement();

    assertEquals("Latest Info", result.getContent());
  }

  @Test
  void getAdminAnnouncement_ShouldReturnActiveOverEmpty() {
    Announcement emptyZombie = new Announcement();

    Announcement active = new Announcement();
    active.setContent("Active Info");
    active.setUpdatedAt(Instant.now());

    when(announcementRepository.findAll()).thenReturn(java.util.List.of(emptyZombie, active));

    Announcement result = announcementService.getAdminAnnouncement();

    assertEquals("Active Info", result.getContent());
  }

  @Test
  void updateAnnouncement_ShouldUpdateContent() {
    Announcement existing = new Announcement();
    existing.setContent("Old Info");
    existing.setUpdatedAt(Instant.now());
    existing.setVersion(0L);

    when(announcementRepository.findAll()).thenReturn(java.util.List.of(existing));
    when(announcementRepository.save(any(Announcement.class))).thenAnswer(i -> i.getArguments()[0]);

    Announcement result = announcementService.updateAnnouncement("New Info", 0L);

    assertEquals("New Info", result.getContent());
    verify(announcementRepository).save(existing);
  }

  @Test
  void deleteAnnouncement_ShouldCallDeleteAll() {
    announcementService.deleteAnnouncement();
    verify(announcementRepository).deleteAll();
  }
}
