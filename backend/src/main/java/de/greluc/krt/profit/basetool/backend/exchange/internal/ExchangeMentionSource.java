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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The exchange's part of the person search: the columns of connected apps and their journal that
 * can name a person, and those left out (REQ-SEC-060).
 */
@Component
public class ExchangeMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("CONNECTED_APPS", "exchange_installation", "label", "id", null),
        new Target("CONNECTED_APPS", "exchange_journal", "after_state", "id", null),
        new Target("CONNECTED_APPS", "exchange_journal", "before_state", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "exchange_bulk_undo_run.client_id",
        "exchange_bulk_undo_run.installation_key",
        "exchange_bulk_undo_run.resource",
        "exchange_bulk_undo_run.status",
        "exchange_bulk_undo_skip.reason",
        "exchange_bulk_undo_skip.resource",
        "exchange_change.entity_key",
        "exchange_change.resource",
        "exchange_change.source_channel",
        "exchange_change.source_client",
        "exchange_change.source_key",
        "exchange_client.client_id",
        "exchange_client.contact_url",
        "exchange_client.display_name",
        "exchange_client.min_client_version",
        "exchange_client.status",
        "exchange_client_capability.capability",
        "exchange_installation.key_thumbprint",
        "exchange_journal.action",
        "exchange_journal.client_id",
        "exchange_journal.entity_key",
        "exchange_journal.installation_key",
        "exchange_journal.resource",
        "exchange_ship_link.client_id",
        "exchange_ship_link.external_id",
        "exchange_ship_link.installation_key");
  }
}
