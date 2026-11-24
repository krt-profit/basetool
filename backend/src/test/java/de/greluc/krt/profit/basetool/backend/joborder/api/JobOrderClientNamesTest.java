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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.ClientDirectory;
import de.greluc.krt.profit.basetool.backend.platform.api.ClientNames;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link JobOrderClientNames} names a client by its registry display name, or by its id once the
 * registry no longer holds it, and reads the names a transaction needs at once (REQ-ORDERS-078).
 */
class JobOrderClientNamesTest {

  private ClientDirectory clientDirectory;
  private JobOrderClientNames names;

  @BeforeEach
  void setUp() {
    clientDirectory = mock(ClientDirectory.class);
    when(clientDirectory.displayNames(anyCollection())).thenReturn(Map.of("versekit", "VerseKit"));
    names = new JobOrderClientNames(new ClientNames(clientDirectory), mock(EntityManager.class));
  }

  @AfterEach
  void endTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void aRegisteredClientIsNamedByItsDisplayName() {
    assertThat(names.nameOf("versekit")).isEqualTo("VerseKit");
  }

  @Test
  void aClientTheRegistryNoLongerHoldsIsNamedByItsId() {
    assertThat(names.nameOf("gone-app")).isEqualTo("gone-app");
  }

  @Test
  void theWebIsNamedByNoClientAndReadsNothing() {
    assertThat(names.nameOf(null)).isNull();
    assertThat(names.appOf(new JobOrderActor.Web(UUID.randomUUID()))).isNull();

    verifyNoInteractions(clientDirectory);
  }

  @Test
  void aClientsActorNamesItsApplication() {
    assertThat(names.appOf(new JobOrderActor.Client(UUID.randomUUID(), "versekit")))
        .isEqualTo("VerseKit");
  }

  @Test
  void oneTransactionReadsThePrimedNamesOnce() {
    TransactionSynchronizationManager.initSynchronization();

    names.prime(List.of("versekit", "gone-app"));

    assertThat(names.nameOf("versekit")).isEqualTo("VerseKit");
    assertThat(names.nameOf("gone-app")).isEqualTo("gone-app");
    verify(clientDirectory).displayNames(anyCollection());
    verify(clientDirectory).displayNames(Set.of("versekit", "gone-app"));
  }
}
