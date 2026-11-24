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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCaller;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderReadCheck;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderWriterRule;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Who makes an exchange write: the acting member, the registry client and its installation
 * (REQ-XCH-010).
 *
 * @param member the acting member
 * @param clientId the registry client id
 * @param installationKey the installation's DPoP key thumbprint
 */
public record ExchangeCaller(
    @NotNull UUID member, @NotNull String clientId, @NotNull String installationKey) {

  /**
   * Reads the caller of a request the ingest gateway relayed.
   *
   * @param authentication the relayed authentication
   * @return the caller
   * @throws IllegalStateException when the request was not relayed for a client
   */
  public static @NotNull ExchangeCaller of(@NotNull SubjectAuthentication authentication) {
    String client = authentication.externalClient();
    String key = authentication.exchangeInstallationKey();
    if (client == null || key == null) {
      throw new IllegalStateException("Not an exchange client request");
    }
    return new ExchangeCaller(UUID.fromString(authentication.subject()), client, key);
  }

  /**
   * The member acting through the client, as the job-order commands attribute a write to it.
   *
   * @return the client actor of the member and the client
   */
  @Contract(" -> new")
  public @NotNull JobOrderActor.Client jobOrderActor() {
    return new JobOrderActor.Client(member, clientId);
  }

  /**
   * The authority a client's write on an existing job order runs under (ADR-0408): the member's
   * direct Logistician units, the read of the orders the member relates to, and the client actor.
   *
   * @return the job-order caller of the client write
   */
  @Contract(" -> new")
  public @NotNull JobOrderCaller jobOrderCaller() {
    return new JobOrderCaller(
        JobOrderWriterRule.DIRECT_LOGISTICIAN, JobOrderReadCheck.OWN_RELATION, jobOrderActor());
  }
}
