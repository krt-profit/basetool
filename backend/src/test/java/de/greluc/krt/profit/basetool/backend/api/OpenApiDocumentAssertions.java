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

package de.greluc.krt.profit.basetool.backend.api;

import de.greluc.krt.profit.basetool.backend.config.ApiDomains;
import de.greluc.krt.profit.basetool.backend.config.ContractTiers;
import de.greluc.krt.profit.basetool.backend.config.OpenApiDomainConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import tools.jackson.databind.JsonNode;

/**
 * What a generated OpenAPI document must satisfy before it is written (REQ-API-007, REQ-API-018):
 * the bearer security scheme, exactly the two anonymous operations, one domain tag and one contract
 * tier on every operation, and a per-domain operation-count floor.
 */
public final class OpenApiDocumentAssertions {

  /** The verbs the document's operations use. */
  private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");

  /** The operations that answer without a token (REQ-SEC-037). */
  public static final Set<String> ANONYMOUS =
      Set.of("GET /api/v1/app/version-policy", "GET /api/v1/terms/document");

  /**
   * How many operations each domain held when the floor was last raised; a domain that loses
   * operations without lowering its floor fails, and so does an emptied tag.
   */
  public static final Map<String, Integer> DOMAIN_FLOOR =
      Map.ofEntries(
          Map.entry("admin-system", 6),
          Map.entry("audit", 4),
          Map.entry("bank", 65),
          Map.entry("blueprint", 25),
          Map.entry("catalogue", 90),
          Map.entry("dashboard", 4),
          Map.entry("exchange", 35),
          Map.entry("hangar", 15),
          Map.entry("identity", 58),
          Map.entry("inventory", 28),
          Map.entry("joborder", 39),
          Map.entry("leadership", 1),
          Map.entry("livesync", 2),
          Map.entry("materialexchange", 21),
          Map.entry("mission", 53),
          Map.entry("notification", 13),
          Map.entry("operation", 12),
          Map.entry("orgchart", 5),
          Map.entry("orgunit", 40),
          Map.entry("personalinventory", 9),
          Map.entry("promotion", 34),
          Map.entry("refinery", 14));

  /** Not instantiable. */
  private OpenApiDocumentAssertions() {}

  /**
   * Lists everything a document gets wrong.
   *
   * @param document the generated document
   * @param tiers the contract tier list
   * @param floor the per-domain operation-count floor
   * @return one line per problem; empty when the document is sound
   */
  public static @NotNull @Unmodifiable List<String> problems(
      @NotNull JsonNode document,
      @NotNull ContractTiers tiers,
      @NotNull Map<String, Integer> floor) {
    List<String> problems = new ArrayList<>();
    JsonNode scheme = document.path("components").path("securitySchemes").path("bearer-jwt");
    if (!"http".equals(scheme.path("type").asString(""))
        || !"bearer".equals(scheme.path("scheme").asString(""))
        || !"JWT".equals(scheme.path("bearerFormat").asString(""))) {
      problems.add("the bearer-jwt security scheme (http, bearer, JWT) is missing or changed");
    }
    JsonNode security = document.path("security");
    if (security.size() != 1 || !security.path(0).has("bearer-jwt")) {
      problems.add("the document-wide security requirement is not exactly bearer-jwt");
    }

    Set<String> anonymous = new TreeSet<>();
    Set<String> usedDomains = new TreeSet<>();
    Map<String, Integer> perDomain = new TreeMap<>();
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> verb : path.getValue().properties()) {
        if (!VERBS.contains(verb.getKey())) {
          continue;
        }
        String key = verb.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();
        JsonNode operation = verb.getValue();
        JsonNode operationSecurity = operation.get("security");
        if (operationSecurity != null && operationSecurity.isEmpty()) {
          anonymous.add(key);
        }
        checkTags(key, operation, tiers, problems, usedDomains, perDomain);
      }
    }
    if (!anonymous.equals(ANONYMOUS)) {
      problems.add("the anonymous operations are " + anonymous + ", expected " + ANONYMOUS);
    }

    Set<String> declared = new TreeSet<>();
    document.path("tags").forEach(tag -> declared.add(tag.path("name").asString("")));
    if (!declared.equals(usedDomains)) {
      problems.add("the document's tag list " + declared + " is not the domains " + usedDomains);
    }
    floor.forEach(
        (domain, minimum) -> {
          int count = perDomain.getOrDefault(domain, 0);
          if (count < minimum) {
            problems.add(
                "domain "
                    + domain
                    + " has "
                    + count
                    + " operations, below its floor of "
                    + minimum
                    + "; lower the floor in the same change if they moved deliberately");
          }
        });
    return List.copyOf(problems);
  }

  /**
   * Checks one operation's tag, domain extension and tier.
   *
   * @param key the operation as {@code VERB path}
   * @param operation the operation node
   * @param tiers the contract tier list
   * @param problems the accumulator
   * @param usedDomains the domains seen so far
   * @param perDomain the operation count per domain so far
   */
  private static void checkTags(
      String key,
      JsonNode operation,
      ContractTiers tiers,
      List<String> problems,
      Set<String> usedDomains,
      Map<String, Integer> perDomain) {
    JsonNode tags = operation.path("tags");
    String domain = operation.path(OpenApiDomainConfig.DOMAIN_EXTENSION).asString("");
    if (tags.size() != 1 || !tags.path(0).asString("").equals(domain)) {
      problems.add(
          key + " carries tags " + tags + " instead of exactly its domain '" + domain + "'");
    }
    if (!ApiDomains.all().contains(domain)) {
      problems.add(
          key + " belongs to no known domain ('" + domain + "'); add its controller to ApiDomains");
    } else {
      usedDomains.add(domain);
      perDomain.merge(domain, 1, Integer::sum);
    }
    String[] parts = key.split(" ", 2);
    String expected = tiers.tierOf(parts[0], parts[1]);
    String tier = operation.path(OpenApiDomainConfig.TIER_EXTENSION).asString("");
    if (!expected.equals(tier)) {
      problems.add(key + " has contract tier '" + tier + "', expected " + expected);
    }
  }
}
