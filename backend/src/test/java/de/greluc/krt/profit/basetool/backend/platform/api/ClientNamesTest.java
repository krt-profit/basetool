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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link ClientNames} names a client by its registry display name, or by its id once the registry
 * no longer holds it, and reads every name a transaction needs in one query (REQ-DATA-003).
 */
class ClientNamesTest {

  /** Names nothing beside a miss. */
  private static final Supplier<Collection<String>> NONE = List::of;

  /** Fails when a lookup asks for the clients beside it although it needs no read. */
  private static final Supplier<Collection<String>> UNASKED =
      () -> {
        throw new AssertionError("a lookup that needs no read asked for the clients beside it");
      };

  private ClientDirectory clientDirectory;
  private ClientNames names;

  @BeforeEach
  void setUp() {
    clientDirectory = mock(ClientDirectory.class);
    when(clientDirectory.displayNames(anyCollection())).thenReturn(Map.of("versekit", "VerseKit"));
    names = new ClientNames(clientDirectory);
  }

  @AfterEach
  void endTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void aRegisteredClientIsNamedByItsDisplayName() {
    assertThat(names.nameOf("versekit", NONE)).isEqualTo("VerseKit");
  }

  @Test
  void aClientTheRegistryNoLongerHoldsIsNamedByItsId() {
    assertThat(names.nameOf("gone-app", NONE)).isEqualTo("gone-app");
  }

  @Test
  void theWebIsNamedByNoClientAndReadsNothing() {
    assertThat(names.nameOf(null, UNASKED)).isNull();

    verifyNoInteractions(clientDirectory);
  }

  @Test
  void aMissReadsTheClientsBesideItInTheSameQueryAndTheTransactionReadsNoMore() {
    TransactionSynchronizationManager.initSynchronization();

    assertThat(names.nameOf("versekit", () -> Arrays.asList("gone-app", null, "versekit")))
        .isEqualTo("VerseKit");
    assertThat(names.nameOf("gone-app", UNASKED)).isEqualTo("gone-app");
    assertThat(names.nameOf("versekit", UNASKED)).isEqualTo("VerseKit");

    verify(clientDirectory).displayNames(anyCollection());
    verify(clientDirectory).displayNames(Set.of("versekit", "gone-app"));
  }

  @Test
  void oneTransactionReadsThePrimedNamesOnce() {
    TransactionSynchronizationManager.initSynchronization();

    names.prime(List.of("versekit"), () -> List.of("gone-app"));

    assertThat(names.nameOf("versekit", UNASKED)).isEqualTo("VerseKit");
    assertThat(names.nameOf("gone-app", UNASKED)).isEqualTo("gone-app");
    verify(clientDirectory).displayNames(anyCollection());
    verify(clientDirectory).displayNames(Set.of("versekit", "gone-app"));
  }
}
