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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.MemberRelocationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the reads of {@link HomeLocationPageReader} against a stubbed {@link
 * PatchChangeBackendClient} (REQ-PATCH-020, REQ-PATCH-022, ADR-0291): a prompt or a member that
 * cannot be read, or arrives without a body, reads as empty, the head names the member by the
 * effective name before the username, and a lost session propagates.
 */
class HomeLocationPageReaderTest {

  private static final UUID MEMBER = UUID.fromString("10c4710c-0000-4000-8000-0000000000c1");

  private static final HomeLocationPromptDto PENDING =
      new HomeLocationPromptDto(
          MemberRelocationState.PENDING,
          UUID.fromString("10c4710c-0000-4000-8000-0000000000c2"),
          "4.4",
          Instant.parse("2026-11-14T20:00:00Z"),
          null,
          null,
          List.of(),
          List.of());

  private final PatchChangeBackendClient client = mock(PatchChangeBackendClient.class);

  private final HomeLocationPageReader reader = new HomeLocationPageReader(client);

  /**
   * Builds a member with the given names.
   *
   * @param username the login name
   * @param effectiveName the shown name, or {@code null}
   * @return the member
   */
  private static UserDto member(String username, String effectiveName) {
    return new UserDto(
        MEMBER,
        username,
        effectiveName,
        effectiveName,
        null,
        1,
        null,
        Set.of(),
        Set.of(),
        null,
        false,
        false,
        true,
        null,
        null,
        0L,
        null,
        null);
  }

  @Test
  void theOwnPromptIsReadOrEmpty() {
    when(client.myHomeLocationPrompt()).thenReturn(PENDING);
    assertThat(reader.myPrompt()).contains(PENDING);

    when(client.myHomeLocationPrompt()).thenReturn(null);
    assertThat(reader.myPrompt()).isEmpty();

    when(client.myHomeLocationPrompt())
        .thenThrow(new BackendServiceException("backend down", null, 503));
    assertThat(reader.myPrompt()).isEmpty();
  }

  @Test
  void theMemberTabNamesTheMemberByTheEffectiveName() {
    when(client.memberHomeLocationPrompt(MEMBER)).thenReturn(PENDING);
    when(client.member(MEMBER)).thenReturn(member("pilot", "Pilot Alpha"));

    assertThat(reader.memberTab(MEMBER, true))
        .isEqualTo(
            new HomeLocationPageReader.MemberTab(Optional.of(PENDING), Optional.of("Pilot Alpha")));
  }

  @Test
  void aMemberWithoutAnEffectiveNameIsNamedByTheUsername() {
    when(client.memberHomeLocationPrompt(MEMBER)).thenReturn(PENDING);
    when(client.member(MEMBER)).thenReturn(member("pilot", null));

    assertThat(reader.memberTab(MEMBER, true).memberName()).contains("pilot");
  }

  @Test
  void aMemberThatCannotBeReadLeavesThePromptStanding() {
    when(client.memberHomeLocationPrompt(MEMBER)).thenReturn(PENDING);
    when(client.member(MEMBER)).thenThrow(new BackendServiceException("backend down", null, 503));

    HomeLocationPageReader.MemberTab tab = reader.memberTab(MEMBER, true);

    assertThat(tab.prompt()).contains(PENDING);
    assertThat(tab.memberName()).isEmpty();
  }

  @Test
  void aPromptThatCannotBeReadIsEmpty() {
    when(client.memberHomeLocationPrompt(MEMBER))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    assertThat(reader.memberTab(MEMBER, false).prompt()).isEmpty();
  }

  @Test
  void theContentAloneDoesNotReadTheMember() {
    when(client.memberHomeLocationPrompt(MEMBER)).thenReturn(PENDING);

    assertThat(reader.memberTab(MEMBER, false).memberName()).isEmpty();
    verify(client, never()).member(MEMBER);
  }

  @Test
  void aLostSessionPropagates() {
    when(client.myHomeLocationPrompt())
        .thenThrow(new ReauthenticationRequiredException("session lost", null));

    assertThatThrownBy(reader::myPrompt).isInstanceOf(ReauthenticationRequiredException.class);
  }
}
