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

package de.greluc.krt.profit.basetool.backend.exchange.api;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the exchange module:
 * the on-behalf-of gate, the exchange writes and the registry (REQ-OBS-011). The names and values
 * are part of the dashboards and alert rules under {@code monitoring/}.
 */
public final class ExchangeMetricNames {

  /** Tag key for an exchange resource. */
  public static final String TAG_RESOURCE = "resource";

  /**
   * Counter {@code basetool_on_behalf_of_refused_total} — tag {@code reason} ({@link
   * #ON_BEHALF_OF_NOT_A_GATEWAY}, {@link #ON_BEHALF_OF_ENDPOINT_NOT_BOUND}, {@link
   * #ON_BEHALF_OF_NO_CALLER}, {@link #ON_BEHALF_OF_MALFORMED}, {@link
   * #ON_BEHALF_OF_MEMBER_NOT_LIVE}, {@link #ON_BEHALF_OF_FORGED_EXCHANGE_HEADER}, {@link
   * #ON_BEHALF_OF_EXCHANGE_CLIENT_INVALID}, {@link #ON_BEHALF_OF_EXCHANGE_INSTALLATION_INVALID}).
   *
   * <p>Counts on-behalf-of headers refused because the presenter is not the ingest gateway or the
   * request is otherwise invalid (ADR-0129).
   */
  public static final String ON_BEHALF_OF_REFUSED = "basetool.on.behalf.of.refused";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the caller's {@code azp} is not an approved gateway. */
  public static final String ON_BEHALF_OF_NOT_A_GATEWAY = "not_a_gateway";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the named subject was not a UUID. */
  public static final String ON_BEHALF_OF_MALFORMED = "malformed_subject";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the header arrived at an exchange endpoint with no
   * authenticated caller behind it.
   *
   * <p>Usually a probe or an expired token; a sustained rate alongside failing relays points at a
   * changed filter ordering (ADR-0129).
   */
  public static final String ON_BEHALF_OF_NO_CALLER = "no_authenticated_caller";

  /** {@link #ON_BEHALF_OF_REFUSED} reason: the endpoint does not accept an acting member. */
  public static final String ON_BEHALF_OF_ENDPOINT_NOT_BOUND = "endpoint_not_bound";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the named member is unknown here, or the last roster sync
   * no longer found them in the identity provider.
   */
  public static final String ON_BEHALF_OF_MEMBER_NOT_LIVE = "member_not_live";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: an exchange relay header arrived from anything but the
   * gateway acting for a member on an exchange path (REQ-XCH-010).
   */
  public static final String ON_BEHALF_OF_FORGED_EXCHANGE_HEADER = "forged_exchange_header";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the gateway acted for a member on an exchange path
   * without a well-formed {@code X-Exchange-Client}.
   */
  public static final String ON_BEHALF_OF_EXCHANGE_CLIENT_INVALID = "exchange_client_invalid";

  /**
   * Counter {@code basetool_exchange_gate_refused_total{reason}} — exchange-layer requests the
   * backend's {@code ExchangeGate} refused although the gateway had let them through (REQ-XCH-004).
   */
  public static final String EXCHANGE_GATE_REFUSED = "basetool.exchange.gate.refused";

  /**
   * Counter {@code basetool_exchange_disconnects_total{kind}} — a member disconnecting one
   * installation or a whole client ({@code installation} / {@code client}, REQ-XCH-008).
   */
  public static final String EXCHANGE_DISCONNECTS = "basetool.exchange.disconnects";

  /**
   * Counter {@code basetool_exchange_departures_total{outcome}} — a departed member's exchange
   * access ended ({@code done}) or ended only in part ({@code failed}, REQ-XCH-008).
   */
  public static final String EXCHANGE_DEPARTURES = "basetool.exchange.departures";

  /**
   * Counter {@code basetool_exchange_account_checks_total{outcome}} — exchange account checks by
   * answer ({@code match} / {@code mismatch} / {@code unknown}, REQ-XCH-031).
   */
  public static final String EXCHANGE_ACCOUNT_CHECKS = "basetool.exchange.account.checks";

  /**
   * Counter {@code basetool_exchange_writes_total{client_id,resource,outcome}} — exchange ops by
   * registered client, resource ({@code blueprint} / {@code stock} / {@code ship} / {@code
   * org_stock}) and outcome ({@code applied} / {@code unchanged} / {@code unmatched} / {@code
   * ambiguous} / {@code rejected}), plus {@code held} per change set the mass-change guard held
   * back (REQ-XCH-015…-017, REQ-XCH-021, REQ-XCH-028, REQ-XCH-045).
   */
  public static final String EXCHANGE_WRITES = "basetool.exchange.writes";

  /**
   * Counter {@code basetool_exchange_org_stock_pages_total{client_id}} — org-stock snapshot pages
   * served, by registered client (REQ-XCH-042).
   */
  public static final String EXCHANGE_ORG_STOCK_PAGES = "basetool.exchange.org.stock.pages";

  /**
   * Counter of the entries a member's undo of a client's writes restored or skipped, tagged by
   * client, resource ({@code blueprint} / {@code stock} / {@code ship}) and outcome ({@code
   * restored} / {@code skipped}) (REQ-XCH-022).
   */
  public static final String EXCHANGE_UNDO = "basetool.exchange.undo";

  /**
   * Counter of the removals exchange clients committed to members' entries, tagged by client and
   * resource; feeds {@code ExchangeRemoveSpike} (REQ-XCH-028).
   */
  public static final String EXCHANGE_REMOVALS = "basetool.exchange.removals";

  /**
   * Counter of the installations the exchange saw for the first time, tagged by client; feeds
   * {@code ExchangeInstallationSurge} (REQ-XCH-028).
   */
  public static final String EXCHANGE_INSTALLATIONS_CREATED =
      "basetool.exchange.installations.created";

  /**
   * Counter of the held-back change sets members confirmed, tagged by client and resource ({@code
   * blueprint} / {@code stock} / {@code ship}) (REQ-XCH-021).
   */
  public static final String EXCHANGE_MASS_CHANGES_CONFIRMED =
      "basetool.exchange.mass.changes.confirmed";

  /**
   * {@link #ON_BEHALF_OF_REFUSED} reason: the gateway acted for a member on an exchange path
   * without a well-formed {@code X-Exchange-Installation}.
   */
  public static final String ON_BEHALF_OF_EXCHANGE_INSTALLATION_INVALID =
      "exchange_installation_invalid";

  /**
   * Counter {@code basetool_exchange_registry_changes_total{action}} — one per committed change of
   * the exchange client registry or its switch (REQ-XCH-003); feeds {@code
   * ExchangeRegistryChanged}.
   */
  public static final String EXCHANGE_REGISTRY_CHANGES = "basetool.exchange.registry.changes";

  /**
   * Counter {@code basetool_exchange_mirror_writes_total{phase,outcome}} — every attempt to bring
   * the registry's Redis mirror in line (REQ-XCH-003).
   */
  public static final String EXCHANGE_MIRROR_WRITES = "basetool.exchange.mirror.writes";

  /**
   * Gauge {@code basetool_exchange_clients{status}} — the registry clients per {@code
   * ExchangeClientStatus}, refreshed from the registry the mirror sync reads (REQ-XCH-028).
   */
  public static final String EXCHANGE_CLIENTS = "basetool.exchange.clients";

  /**
   * Counter {@code basetool_exchange_resolve_refs_total{kind,status}} — one per reference {@code
   * catalog/resolve} answered, by catalogue and outcome (REQ-XCH-012).
   */
  public static final String EXCHANGE_RESOLVE_REFS = "basetool.exchange.resolve.refs";

  /**
   * Counter {@code basetool_exchange_blueprint_bt_refs_total} — one per blueprint reference that
   * carries the deprecated {@code bt}, watching its use through the 90-day window before it is
   * removed (REQ-XCH-015, ADR-0343).
   */
  public static final String EXCHANGE_BLUEPRINT_BT_REFS = "basetool.exchange.blueprint.bt.refs";

  /**
   * Tag key: the registry change on {@link #EXCHANGE_REGISTRY_CHANGES}, bounded by {@code
   * ExchangeRegistryAction}.
   */
  public static final String TAG_ACTION = "action";

  /**
   * Tag key: when a mirror write ran, bounded by {@code ExchangeMirrorPhase} on {@link
   * #EXCHANGE_MIRROR_WRITES}.
   */
  public static final String TAG_PHASE = "phase";

  /**
   * {@link MetricNames#TAG_OUTCOME} value on {@link #EXCHANGE_MIRROR_WRITES}: the document was
   * written.
   */
  public static final String OUTCOME_WRITTEN = "written";

  /**
   * {@link MetricNames#TAG_OUTCOME} value on {@link #EXCHANGE_MIRROR_WRITES}: the mirror already
   * agreed.
   */
  public static final String OUTCOME_UNCHANGED = "unchanged";

  private ExchangeMetricNames() {}
}
