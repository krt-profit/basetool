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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** Pure reads, concurrent singleton creation and banner dismissal identities against PostgreSQL. */
@SpringBootTest
class AnnouncementSingletonIntegrationTest {

  @Autowired private AnnouncementService service;
  @Autowired private AnnouncementRepository repository;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void emptyReadsDoNotWriteAndRecreationHasANewDismissalIdentity() {
    transactionTemplate.executeWithoutResult(status -> {
      repository.deleteAll();
      repository.flush();
      assertThat(service.getAdminAnnouncement().getId()).isNull();
      assertThat(service.getAdminAnnouncement().getVersion()).isZero();
      assertThat(service.getPublicAnnouncement()).isEmpty();
      assertThat(repository.count()).isZero();
      UUID dismissed = service.updateAnnouncement("First banner", 0L).getId();
      service.deleteAnnouncement();
      repository.flush();
      assertThat(service.getPublicAnnouncement()).isEmpty();
      Announcement next = service.updateAnnouncement("Next banner", 0L);
      assertThat(next.getId()).isNotEqualTo(dismissed);
      assertThat(service.getPublicAnnouncement())
          .map(Announcement::getContent)
          .contains("Next banner");
      status.setRollbackOnly();
    });
  }

  @Test
  void concurrentWriteInitializationKeepsOneCurrentRow() throws Exception {
    UUID original =
        transactionTemplate.execute(
            status -> repository.findBySingletonTrue().map(Announcement::getId).orElse(null));
    ArrayList<Future<UUID>> writes = new ArrayList<>();
    UUID created = null;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 16; i++) {
        writes.add(
            executor.submit(() ->
                transactionTemplate.execute(status -> {
                  repository.ensureSingleton();
                  return repository.lockSingleton().orElseThrow().getId();
                })));
      }
      for (Future<UUID> write : writes) {
        UUID result = write.get();
        if (created == null) {
          created = result;
        }
        assertThat(result).isEqualTo(created);
      }
    } finally {
      if (original == null && created != null) {
        UUID inserted = created;
        transactionTemplate.executeWithoutResult(status -> repository.deleteById(inserted));
      }
    }
  }
}
