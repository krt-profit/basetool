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

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link BankAccount} to its response DTO. The balance is not an entity
 * property (compute-on-read, ADR-0010), so it travels as a second source parameter — the service
 * computes the balances batch-wise and pairs them in.
 */
@Mapper(config = CentralMapperConfig.class)
public interface BankAccountMapper {

  /**
   * Maps one account plus its externally computed balance to the response DTO.
   *
   * @param account the account entity (org unit pre-fetched where the caller needs it mapped)
   * @param balance the account's compute-on-read balance
   * @return the response DTO
   */
  @Mapping(target = "id", source = "account.id")
  @Mapping(target = "name", source = "account.name")
  @Mapping(target = "version", source = "account.version")
  @Mapping(target = "createdAt", source = "account.createdAt")
  @Mapping(target = "balanceTarget", source = "account.balanceTarget")
  @Mapping(target = "employeeApprovalCeiling", source = "account.employeeApprovalCeiling")
  @Mapping(target = "areaLeadApprovalCeiling", source = "account.areaLeadApprovalCeiling")
  @Mapping(target = "balance", source = "balance")
  BankAccountDto toDto(BankAccount account, BigDecimal balance);

  /**
   * Maps the owning org unit to its lightweight reference projection.
   *
   * @param orgUnit the org unit (Staffel or Spezialkommando)
   * @return the reference DTO carrying id, name, shorthand and kind
   */
  OrgUnitReferenceDto toReference(OrgUnit orgUnit);
}
