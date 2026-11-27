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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.session.PrincipalSessionTerminator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link DeletedMemberSessions} (REQ-DATA-048). */
@ExtendWith(MockitoExtension.class)
class DeletedMemberSessionsTest {

  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Mock private IdentityBackendClient identityClient;
  @Mock private PrincipalSessionTerminator terminator;

  @Test
  void readsTheSignInNameOfTheAccount() {
    UserDto user = mock(UserDto.class);
    when(user.username()).thenReturn("alice");
    when(identityClient.user(USER_ID)).thenReturn(user);

    assertThat(sessions().principalOf(USER_ID)).isEqualTo("alice");
  }

  @Test
  void readsNoName_whenTheBackendCannotAnswer() {
    when(identityClient.user(USER_ID)).thenThrow(BackendServiceException.class);

    assertThat(sessions().principalOf(USER_ID)).isNull();
  }

  @Test
  void readsTheSignInNameOfTheAccountARequestIsAbout() {
    UserDto user = mock(UserDto.class);
    when(user.username()).thenReturn("alice");
    when(identityClient.user(USER_ID)).thenReturn(user);
    when(identityClient.deletionRequests())
        .thenReturn(
            List.of(
                new AdminDeletionRequestDto(
                    REQUEST_ID,
                    USER_ID,
                    "Alice",
                    "PENDING",
                    false,
                    Instant.now(),
                    null,
                    null,
                    0L)));

    assertThat(sessions().principalOfRequest(REQUEST_ID)).isEqualTo("alice");
  }

  @Test
  void readsNoName_forARequestThatIsNotPending() {
    when(identityClient.deletionRequests()).thenReturn(List.of());

    assertThat(sessions().principalOfRequest(REQUEST_ID)).isNull();
  }

  @Test
  void endsTheSessionsOfTheName() {
    sessions().end("alice");

    verify(terminator).endSessionsOf("alice");
  }

  private DeletedMemberSessions sessions() {
    return new DeletedMemberSessions(identityClient, terminator);
  }
}
