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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TerminalService}'s admin-override mutators. The visibility flip is already
 * covered indirectly by {@code TerminalControllerTest}; these tests pin the more delicate contract
 * that "set" mutators write both the value and the {@code *Overridden} flag, and that "clear"
 * mutators flip the flag back AND revert the value column to the last UEX-reported state stored in
 * the {@code uex*} mirror columns (so the materials-overview filter and the UEX-source chip do not
 * keep seeing the stale admin-pinned value until the next UEX sweep runs).
 */
@ExtendWith(MockitoExtension.class)
class TerminalServiceTest {

  @Mock private TerminalRepository terminalRepository;

  @InjectMocks private TerminalService service;

  @Test
  void setLoadingDockOverride_storesValueAndFlag() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.setLoadingDockOverride(id, true);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertTrue(cap.getValue().getHasLoadingDock());
    assertTrue(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void clearLoadingDockOverride_flipsFlagAndRevertsValueToUexMirror() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    terminal.setHasLoadingDock(true);
    terminal.setHasLoadingDockOverridden(true);
    terminal.setUexHasLoadingDock(false);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.clearLoadingDockOverride(id);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertFalse(
        cap.getValue().getHasLoadingDock(),
        "value column must revert to the UEX mirror so the materials filter and the UEX chip stop"
            + " seeing the stale admin-pinned value");
    assertFalse(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void clearLoadingDockOverride_revertsValueToNullWhenUexMirrorIsNull() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    terminal.setHasLoadingDock(true);
    terminal.setHasLoadingDockOverridden(true);
    terminal.setUexHasLoadingDock(null);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.clearLoadingDockOverride(id);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertNull(cap.getValue().getHasLoadingDock());
    assertFalse(cap.getValue().getHasLoadingDockOverridden());
  }

  @Test
  void setAutoLoadOverride_storesValueAndFlag() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.setAutoLoadOverride(id, false);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertFalse(cap.getValue().getIsAutoLoad());
    assertTrue(cap.getValue().getIsAutoLoadOverridden());
  }

  @Test
  void clearAutoLoadOverride_flipsFlagAndRevertsValueToUexMirror() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    terminal.setIsAutoLoad(true);
    terminal.setIsAutoLoadOverridden(true);
    terminal.setUexIsAutoLoad(false);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.clearAutoLoadOverride(id);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertFalse(
        cap.getValue().getIsAutoLoad(),
        "value column must revert to the UEX mirror so the materials filter and the UEX chip stop"
            + " seeing the stale admin-pinned value");
    assertFalse(cap.getValue().getIsAutoLoadOverridden());
  }

  @Test
  void clearAutoLoadOverride_revertsValueToNullWhenUexMirrorIsNull() {
    UUID id = UUID.randomUUID();
    Terminal terminal = new Terminal();
    terminal.setId(id);
    terminal.setIsAutoLoad(true);
    terminal.setIsAutoLoadOverridden(true);
    terminal.setUexIsAutoLoad(null);
    when(terminalRepository.findById(id)).thenReturn(Optional.of(terminal));
    when(terminalRepository.save(any(Terminal.class))).thenAnswer(i -> i.getArgument(0));

    service.clearAutoLoadOverride(id);

    ArgumentCaptor<Terminal> cap = ArgumentCaptor.forClass(Terminal.class);
    verify(terminalRepository).save(cap.capture());
    assertNull(cap.getValue().getIsAutoLoad());
    assertFalse(cap.getValue().getIsAutoLoadOverridden());
  }
}
