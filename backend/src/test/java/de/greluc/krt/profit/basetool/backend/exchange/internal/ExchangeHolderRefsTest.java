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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The per-client holder pseudonym of REQ-XCH-043. */
class ExchangeHolderRefsTest {

  private static final UUID USER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void theRefIsTheUnpaddedBase64UrlHmacOfTheUserIdUnderTheSalt() {
    byte[] salt = new byte[32];
    Arrays.fill(salt, (byte) 7);

    String ref = ExchangeHolderRefs.ref(salt, USER);

    assertThat(ref)
        .hasSize(43)
        .matches("[A-Za-z0-9_-]+")
        .isEqualTo(ExchangeHolderRefs.ref(salt, USER));
  }

  @Test
  void anotherSaltOrAnotherUserGivesAnotherRef() {
    byte[] salt = new byte[32];
    byte[] other = new byte[32];
    other[0] = 1;

    assertThat(ExchangeHolderRefs.ref(salt, USER))
        .isNotEqualTo(ExchangeHolderRefs.ref(other, USER))
        .isNotEqualTo(ExchangeHolderRefs.ref(salt, UUID.randomUUID()));
  }

  @Test
  void aClientsRefsUseItsStoredSaltAndAnUnknownClientIsRefused() {
    ExchangeClientRepository repository = mock(ExchangeClientRepository.class);
    byte[] salt = new byte[32];
    salt[5] = 9;
    when(repository.findHolderRefSalt("vk")).thenReturn(Optional.of(salt));
    when(repository.findHolderRefSalt("gone")).thenReturn(Optional.empty());
    ExchangeHolderRefs refs = new ExchangeHolderRefs(repository);

    assertThat(refs.forClient("vk").apply(USER)).isEqualTo(ExchangeHolderRefs.ref(salt, USER));
    assertThatThrownBy(() -> refs.forClient("gone")).isInstanceOf(IllegalStateException.class);
  }
}
