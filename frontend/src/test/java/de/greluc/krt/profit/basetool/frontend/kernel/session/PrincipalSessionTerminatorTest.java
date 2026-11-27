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

package de.greluc.krt.profit.basetool.frontend.kernel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;

/** Unit tests for {@link PrincipalSessionTerminator}. */
@ExtendWith(MockitoExtension.class)
class PrincipalSessionTerminatorTest {

  @Mock private FindByIndexNameSessionRepository<MapSession> repository;

  @Mock private ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> provider;

  @Test
  void deletesEverySessionIndexedUnderThePrincipal() {
    doReturn(repository).when(provider).getIfAvailable();
    when(repository.findByPrincipalName("alice"))
        .thenReturn(Map.of("s1", new MapSession("s1"), "s2", new MapSession("s2")));

    int ended = new PrincipalSessionTerminator(provider).endSessionsOf("alice");

    assertThat(ended).isEqualTo(2);
    verify(repository).deleteById("s1");
    verify(repository).deleteById("s2");
  }

  @Test
  void endsNothing_withoutAPrincipal() {
    PrincipalSessionTerminator terminator = new PrincipalSessionTerminator(provider);

    assertThat(terminator.endSessionsOf(null)).isZero();
    assertThat(terminator.endSessionsOf("  ")).isZero();

    verify(repository, never()).findByPrincipalName(any());
  }

  @Test
  void endsNothing_withoutAnIndexedSessionStore() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThat(new PrincipalSessionTerminator(provider).endSessionsOf("alice")).isZero();
  }
}
