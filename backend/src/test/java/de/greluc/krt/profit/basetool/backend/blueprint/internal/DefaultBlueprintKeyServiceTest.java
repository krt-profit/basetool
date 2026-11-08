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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unit tests for {@link DefaultBlueprintKeyService}. */
@ExtendWith(MockitoExtension.class)
class DefaultBlueprintKeyServiceTest {

  @Mock private DefaultBlueprintRepository repository;
  @InjectMocks private DefaultBlueprintKeyService service;

  @Test
  void isDefault_lazilyLoadsAndReportsMembership() {
    when(repository.findAllProductKeys()).thenReturn(Set.of("s-38 pistol", "p4-ar rifle"));

    assertTrue(service.isDefault("s-38 pistol"));
    assertFalse(service.isDefault("arclight pistol"));
    assertFalse(service.isDefault(null));
  }

  @Test
  void refresh_reloadsTheCachedSet() {
    when(repository.findAllProductKeys())
        .thenReturn(Set.of("old key"))
        .thenReturn(Set.of("new key"));

    service.refresh();
    assertTrue(service.isDefault("old key"));

    service.refresh();
    assertTrue(service.isDefault("new key"));
    assertFalse(service.isDefault("old key"));
  }

  @Test
  void refreshAfterCommit_reloadsOnlyOnceTheTransactionCommits() {
    when(repository.findAllProductKeys())
        .thenReturn(Set.of("old key"))
        .thenReturn(Set.of("new key"));
    service.refresh();
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.refreshAfterCommit();
      assertTrue(service.isDefault("old key"));

      TransactionSynchronizationManager.getSynchronizations()
          .forEach(TransactionSynchronization::afterCommit);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    assertTrue(service.isDefault("new key"));
    verify(repository, times(2)).findAllProductKeys();
  }

  @Test
  void refreshAfterCommit_reloadsAtOnceOutsideATransaction() {
    when(repository.findAllProductKeys()).thenReturn(Set.of("key"));

    service.refreshAfterCommit();

    verify(repository).findAllProductKeys();
  }
}
