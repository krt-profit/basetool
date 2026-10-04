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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserActorHandleResolver}, the identity side of the audit actor snapshot.
 */
class UserActorHandleResolverTest {

  private final UserRepository userRepository = mock(UserRepository.class);

  private final UserActorHandleResolver resolver = new UserActorHandleResolver(userRepository);

  @Test
  void resolvesTheDisplayNameWhenOneIsSet() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setUsername("logi_jo");
    user.setDisplayName("Jo the Logistician");
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThat(resolver.handleOf(id)).contains("Jo the Logistician");
  }

  @Test
  void fallsBackToTheUsernameWithoutADisplayName() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setUsername("logi_jo");
    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThat(resolver.handleOf(id)).contains("logi_jo");
  }

  @Test
  void isEmptyForAnUnknownUser() {
    UUID id = UUID.randomUUID();
    when(userRepository.findById(id)).thenReturn(Optional.empty());

    assertThat(resolver.handleOf(id)).isEmpty();
  }
}
