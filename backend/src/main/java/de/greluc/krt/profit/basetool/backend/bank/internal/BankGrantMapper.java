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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.mapper.CentralMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from {@link BankAccountGrant} to its matrix-row DTO. Whether the grantee
 * currently holds the Bank Employee role (the inert-row marker, REQ-BANK-009) is derived by the
 * service from the user's synced roles and travels as a second source parameter.
 */
@Mapper(config = CentralMapperConfig.class)
public interface BankGrantMapper {

  /**
   * Maps one grant row plus the service-derived role status to the matrix DTO. Requires the {@code
   * user} and {@code account} references to be pre-fetched (the repository queries use an entity
   * graph).
   *
   * @param grant the grant entity with references loaded
   * @param granteeHasBankRole whether the grantee currently holds the Bank Employee role (or
   *     above); {@code false} renders the row inert
   * @return the matrix-row DTO
   */
  @Mapping(target = "userId", source = "grant.id.userId")
  @Mapping(target = "userHandle", source = "grant.user.effectiveName")
  @Mapping(target = "accountId", source = "grant.id.accountId")
  @Mapping(target = "accountNo", source = "grant.account.accountNo")
  @Mapping(target = "accountName", source = "grant.account.name")
  @Mapping(target = "canDeposit", source = "grant.canDeposit")
  @Mapping(target = "canWithdraw", source = "grant.canWithdraw")
  @Mapping(target = "canTransfer", source = "grant.canTransfer")
  @Mapping(target = "version", source = "grant.version")
  @Mapping(target = "granteeHasBankRole", source = "granteeHasBankRole")
  BankGrantDto toDto(BankAccountGrant grant, boolean granteeHasBankRole);
}
