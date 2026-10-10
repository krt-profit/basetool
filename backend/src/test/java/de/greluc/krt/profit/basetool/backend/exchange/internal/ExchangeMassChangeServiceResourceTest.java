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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Proves that a staged mass change naming no known resource is refused before the write capability
 * is chosen, so no value can fall through to {@link ExchangeCapability#HANGAR_WRITE}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeMassChangeServiceResourceTest {

  @Mock private ExchangeSettingsRepository settingsRepository;
  @Mock private ExchangeClientRepository clientRepository;
  @Mock private ExchangeBlueprintWriteService blueprintWriteService;
  @Mock private ExchangeStockWriteService stockWriteService;
  @Mock private ExchangeShipWriteService shipWriteService;

  @InjectMocks private ExchangeMassChangeService service;

  @Test
  void anUnknownResourceIsRefusedBeforeTheCapabilityChoice() {
    ExchangeSettings settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(settingsRepository.findById(ExchangeSettings.SINGLETON_ID))
        .thenReturn(Optional.of(settings));
    ExchangeClient client = mock(ExchangeClient.class);
    when(client.getStatus()).thenReturn(ExchangeClientStatus.ACTIVE);
    when(clientRepository.findWithCapabilitiesByClientId(any())).thenReturn(Optional.of(client));
    ConnectedAppMassChangeRequestDto request =
        new ConnectedAppMassChangeRequestDto(
            "client", "installation", "hangar", "{}", Instant.now());

    assertThatThrownBy(() -> service.preview(UUID.randomUUID(), request))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), request))
        .isInstanceOf(BadRequestException.class);

    verify(client, never()).getCapabilities();
    verifyNoInteractions(blueprintWriteService, stockWriteService, shipWriteService);
  }
}
