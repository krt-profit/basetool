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

package de.greluc.krt.profit.basetool.ingest.contract;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The authenticated exchange routes and the capability each needs (REQ-XCH-001, REQ-XCH-004). Deny
 * by default: a path or method not listed here is {@code 404 NOT_FOUND}.
 */
public final class ExchangeRoutes {

  /** The base capability. */
  public static final String CONNECT = "exchange.connect";

  /** Stands for "any granted exchange capability". */
  public static final String ANY = "*";

  /** Every authenticated exchange route. */
  public static final @Unmodifiable List<Route> ROUTES =
      List.of(
          route(HttpMethod.GET, "/exchange/v1", CONNECT),
          route(HttpMethod.POST, "/exchange/v1/me/installation", CONNECT),
          route(HttpMethod.POST, "/exchange/v1/me/account-check", CONNECT),
          route(HttpMethod.POST, "/exchange/v1/catalog/resolve", ANY),
          route(HttpMethod.GET, "/exchange/v1/catalog/locations", ANY),
          route(HttpMethod.GET, "/exchange/v1/me/blueprints", "exchange.blueprints.read"),
          write(HttpMethod.POST, "/exchange/v1/me/blueprints/changes", "exchange.blueprints.write"),
          route(HttpMethod.GET, "/exchange/v1/me/stock", "exchange.stock.read"),
          write(HttpMethod.POST, "/exchange/v1/me/stock/changes", "exchange.stock.write"),
          route(HttpMethod.GET, "/exchange/v1/me/ships", "exchange.hangar.read"),
          write(HttpMethod.POST, "/exchange/v1/me/ships/changes", "exchange.hangar.write"),
          route(HttpMethod.GET, "/exchange/v1/me/org-demand", "exchange.demand.read"),
          write(HttpMethod.POST, "/exchange/v1/me/drafts/blueprints", "exchange.drafts.blueprints"),
          write(
              HttpMethod.POST,
              "/exchange/v1/me/drafts/refinery-orders",
              "exchange.drafts.refinery"));

  /** Not instantiable. */
  private ExchangeRoutes() {}

  /**
   * Finds the route a request targets.
   *
   * @param method the HTTP method
   * @param path the request path
   * @return the route, or empty when the exchange has no such route
   */
  public static @NotNull Optional<Route> find(@NotNull String method, @NotNull String path) {
    PathContainer container = PathContainer.parsePath(path);
    return ROUTES.stream()
        .filter(route -> route.method().matches(method) && route.pattern().matches(container))
        .findFirst();
  }

  /**
   * Builds one route that reads.
   *
   * @param method the method
   * @param path the path pattern
   * @param capability the capability it needs, or {@link #ANY}
   * @return the route
   */
  private static @NotNull Route route(
      @NotNull HttpMethod method, @NotNull String path, @NotNull String capability) {
    return new Route(method, PathPatternParser.defaultInstance.parse(path), capability, false);
  }

  /**
   * Builds one route that writes, and so counts against the daily quota.
   *
   * @param method the method
   * @param path the path pattern
   * @param capability the capability it needs
   * @return the route
   */
  private static @NotNull Route write(
      @NotNull HttpMethod method, @NotNull String path, @NotNull String capability) {
    return new Route(method, PathPatternParser.defaultInstance.parse(path), capability, true);
  }

  /**
   * One authenticated exchange route.
   *
   * @param method the method
   * @param pattern the path pattern
   * @param capability the capability it needs, or {@link #ANY}
   * @param write whether the route writes, and so counts against the daily quota
   */
  public record Route(
      @NotNull HttpMethod method,
      @NotNull PathPattern pattern,
      @NotNull String capability,
      boolean write) {

    /** The account check's path, which has its own hourly limit. */
    static final String ACCOUNT_CHECK = "/exchange/v1/me/account-check";

    /**
     * Whether this is the account check.
     *
     * @return {@code true} for {@code POST /exchange/v1/me/account-check}
     */
    public boolean accountCheck() {
      return ACCOUNT_CHECK.equals(pattern.getPatternString());
    }

    /**
     * Returns the route's bounded template for logs.
     *
     * @return the method and the path pattern, such as {@code GET /exchange/v1/me/stock}
     */
    public @NotNull String template() {
      return method.name() + " " + pattern.getPatternString();
    }

    /**
     * Whether a set of capabilities admits this route.
     *
     * @param granted the capabilities both the token and the registry hold
     * @return {@code true} when the route's capability is among them, or any is for {@link #ANY}
     */
    public boolean admits(@Nullable Set<String> granted) {
      if (granted == null || granted.isEmpty()) {
        return false;
      }
      return ANY.equals(capability) || granted.contains(capability);
    }
  }
}
