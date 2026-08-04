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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Which Keycloak clients may act for another member via {@link
 * ActingSubjectResolver#ON_BEHALF_OF_HEADER} (ADR-0129).
 *
 * <p>Matched on the token's {@code azp}, so only a token minted for one of these clients qualifies
 * — possessing a user's token is not enough, and neither is holding any role.
 *
 * <p><strong>Empty by default, and empty means nobody.</strong> A deployment that has not created
 * the gateway's confidential client refuses every on-behalf-of header rather than trusting one, so
 * the dangerous direction requires a deliberate act of configuration.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.security.ingest-gateway")
public class IngestGatewayProperties {

  /** The {@code azp} values allowed to act for another member. Empty disables the mechanism. */
  private List<String> clientIds = List.of();
}
