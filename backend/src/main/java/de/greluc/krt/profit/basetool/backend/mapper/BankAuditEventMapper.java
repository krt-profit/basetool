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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link BankAuditEvent} to its admin-viewer DTO. The affected account's
 * display number is resolved batch-wise by the service (audit rows keep plain UUID references so
 * they outlive every aggregate) and travels as a second source parameter.
 */
@Mapper(config = CentralMapperConfig.class)
public interface BankAuditEventMapper {

  /**
   * Maps one audit row plus the batch-resolved account number to the viewer DTO.
   *
   * @param event the audit row
   * @param accountNo the affected account's display number, or {@code null} when the event has no
   *     account reference or the account vanished
   * @return the viewer DTO
   */
  @Mapping(target = "id", source = "event.id")
  @Mapping(target = "accountId", source = "event.accountId")
  @Mapping(target = "accountNo", source = "accountNo")
  @Mapping(target = "clientId", source = "event.clientId")
  BankAuditEventDto toDto(BankAuditEvent event, @Nullable String accountNo);
}
