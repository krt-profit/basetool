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

import de.greluc.krt.profit.basetool.backend.mapper.CentralMapperConfig;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.mapstruct.Mapper;

/** MapStruct mapper between the exchange registry entities and their admin DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface ExchangeClientMapper {

  /**
   * Maps a registry client, its capabilities loaded.
   *
   * @param client the entity
   * @return the DTO
   */
  ExchangeClientDto toDto(ExchangeClient client);

  /**
   * Maps the settings row.
   *
   * @param settings the entity
   * @return the DTO
   */
  ExchangeSettingsDto toDto(ExchangeSettings settings);

  /**
   * Orders capabilities by declaration, so the response is stable.
   *
   * @param capabilities the granted capabilities
   * @return them in declaration order
   */
  default @NotNull List<ExchangeCapability> sortedCapabilities(
      @NotNull Collection<ExchangeCapability> capabilities) {
    return capabilities.stream().sorted().toList();
  }
}
