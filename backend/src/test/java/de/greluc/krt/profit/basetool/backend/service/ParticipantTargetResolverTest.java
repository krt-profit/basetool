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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.ParticipantTargetResolver.ParticipantTarget;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The one rule behind every free-text roster name (BE-SIMP-04): a unique member match links the
 * member, none keeps the name as an external person, several are a 409.
 *
 * <p>The rule was written four times before, and the fourth copy — inside {@code
 * MissionParticipantService} — used a single-result query that threw a 500 on the ambiguous case
 * the other three answered with a 409. One implementation, pinned here, is what keeps the answer
 * the same whichever door the name came through.
 */
@ExtendWith(MockitoExtension.class)
class ParticipantTargetResolverTest {

  private static final String AMBIGUOUS = "Participant name is ambiguous.";

  @Mock private UserService userService;

  private ParticipantTargetResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new ParticipantTargetResolver(userService);
  }

  private static User member(UUID id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  @Test
  void anExplicitIdWinsAndNoNameIsLookedUp() {
    UUID picked = UUID.randomUUID();

    ParticipantTarget target = resolver.resolve(picked, "Somebody", AMBIGUOUS);

    assertThat(target).isEqualTo(new ParticipantTarget(picked, "Somebody"));
    verify(userService, never()).findMatchesByExactName(any());
  }

  @Test
  void aBlankNameIsNotLookedUp() {
    ParticipantTarget target = resolver.resolve(null, "   ", AMBIGUOUS);

    assertThat(target).isEqualTo(new ParticipantTarget(null, "   "));
    verify(userService, never()).findMatchesByExactName(any());
  }

  @Test
  void nothingSubmittedResolvesToNobody() {
    assertThat(resolver.resolve(null, null, AMBIGUOUS))
        .isEqualTo(new ParticipantTarget(null, null));
  }

  @Test
  void aUniqueMatchLinksTheMemberAndDropsTheName() {
    UUID alice = UUID.randomUUID();
    when(userService.findMatchesByExactName("Alice")).thenReturn(List.of(member(alice)));

    assertThat(resolver.resolve(null, "Alice", AMBIGUOUS))
        .isEqualTo(new ParticipantTarget(alice, null));
  }

  @Test
  void noMatchKeepsTheNameAsAnExternalPersonUntouched() {
    when(userService.findMatchesByExactName(" Stranger ")).thenReturn(List.of());

    assertThat(resolver.resolve(null, " Stranger ", AMBIGUOUS))
        .isEqualTo(new ParticipantTarget(null, " Stranger "));
  }

  @Test
  void severalMatchesAreAConflictCarryingTheCallersMessage() {
    when(userService.findMatchesByExactName("Sam"))
        .thenReturn(List.of(member(UUID.randomUUID()), member(UUID.randomUUID())));

    assertThatThrownBy(() -> resolver.resolve(null, "Sam", "Party lead name is ambiguous."))
        .isInstanceOf(BusinessConflictException.class)
        .hasMessage("Party lead name is ambiguous.");
  }
}
