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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DiscordAccountExistenceService} (REQ-SEC-022): candidate normalisation
 * (trim + lower-case + dedupe), the name-vs-email matching split, and the empty-candidate
 * short-circuit that keeps the JPQL {@code IN} clause non-empty.
 */
@ExtendWith(MockitoExtension.class)
class DiscordAccountExistenceServiceTest {

  @Mock private UserRepository userRepository;
  @InjectMocks private DiscordAccountExistenceService service;

  @Test
  void matchesOnName_whenRepositoryFindsUsernameOrDisplayName() {
    when(userRepository.existsByLowerUsernameOrDisplayNameIn(any())).thenReturn(true);

    boolean exists = service.accountExistsForDiscordIdentity("Maverick", null, null);

    assertThat(exists).isTrue();
    verify(userRepository).existsByLowerUsernameOrDisplayNameIn(any());
    verify(userRepository, org.mockito.Mockito.never()).existsByLowerEmail(any());
  }

  @Test
  void matchesOnEmail_whenOnlyEmailMatches() {
    when(userRepository.existsByLowerEmail(eq("mav@example.com"))).thenReturn(true);

    boolean exists = service.accountExistsForDiscordIdentity("   ", "Mav@Example.com", null);

    assertThat(exists).isTrue();
    verify(userRepository).existsByLowerEmail(eq("mav@example.com"));
    verify(userRepository, org.mockito.Mockito.never()).existsByLowerUsernameOrDisplayNameIn(any());
  }

  @Test
  void noMatch_whenNeitherNameNorEmailExists() {
    when(userRepository.existsByLowerUsernameOrDisplayNameIn(any())).thenReturn(false);
    when(userRepository.existsByLowerEmail(any())).thenReturn(false);

    boolean exists = service.accountExistsForDiscordIdentity("Maverick", "mav@example.com", "Mav");

    assertThat(exists).isFalse();
  }

  @Test
  void shortCircuits_whenAllCandidatesBlank() {
    boolean exists = service.accountExistsForDiscordIdentity(null, "   ", "");

    assertThat(exists).isFalse();
    verifyNoInteractions(userRepository);
  }

  @Test
  void normalisesCandidates_trimLowercaseAndDedupe() {
    when(userRepository.existsByLowerUsernameOrDisplayNameIn(any())).thenReturn(false);

    service.accountExistsForDiscordIdentity("  Maverick  ", null, "MAVERICK");

    verify(userRepository)
        .existsByLowerUsernameOrDisplayNameIn(
            argThat(names -> names.size() == 1 && names.contains("maverick")));
  }
}
