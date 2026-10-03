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

package de.greluc.krt.profit.basetool.ingest;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import de.greluc.krt.profit.basetool.ingest.archfixture.cycle.alpha.CycleAlpha;
import de.greluc.krt.profit.basetool.ingest.archfixture.cycle.beta.CycleBeta;
import java.net.URLConnection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Structural rules of the gateway's concern packages (REQ-INGEST-013, REQ-INGEST-014): every class
 * lives in a named concern, the concerns form no cycle, only {@code relay} holds an outbound HTTP
 * client, and only {@code registry}, {@code store} and {@code handoff} touch Redis. Each rule has a
 * selection floor and a planted violation it must detect.
 */
class ConcernPackageRulesTest {

  private static final String ROOT = "de.greluc.krt.profit.basetool.ingest";

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  /** The concern packages directly below the root, and the only ones allowed there. */
  private static final Set<String> CONCERNS =
      Set.of(
          "assembly",
          "auth",
          "config",
          "contract",
          "edge",
          "gate",
          "handoff",
          "idempotency",
          "limits",
          "observability",
          "problem",
          "registry",
          "relay",
          "store",
          "web");

  /** The concern that holds every outbound HTTP client. */
  private static final String RELAY = "relay";

  /** The concerns allowed to touch Redis. */
  private static final Set<String> REDIS_CONCERNS = Set.of("registry", "store", "handoff");

  /**
   * Classes outside {@code relay} that hold an outbound HTTP client type, each with the reason it
   * may.
   */
  private static final Map<String, String> OUTBOUND_HTTP_EXCEPTIONS =
      Map.of(
          ROOT + ".assembly.SecurityConfig",
          "builds the resource server's JWKS decoder: Spring Security's NimbusJwtDecoder fetches"
              + " Keycloak's signing keys over a RestTemplate on relay's pinned"
              + " KeycloakTrustSupport request factory");

  /** The fewest classes in {@code relay} that hold an outbound HTTP client type today. */
  private static final int RELAY_OUTBOUND_FLOOR = 6;

  /** The fewest classes in the Redis concerns that touch Redis today. */
  private static final int REDIS_USER_FLOOR = 6;

  /** An outbound HTTP client type: anything that can open a connection to another host. */
  private static final DescribedPredicate<JavaClass> OUTBOUND_HTTP =
      resideInAnyPackage(
              "java.net.http..",
              "org.springframework.http.client..",
              "org.springframework.web.reactive.function.client..")
          .or(belongToAnyOf(RestClient.class, RestTemplate.class, RestOperations.class))
          .or(assignableTo(URLConnection.class))
          .as("an outbound HTTP client type");

  /** A Redis client type. */
  private static final DescribedPredicate<JavaClass> REDIS =
      resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..", "redis.clients..")
          .as("a Redis client type");

  @Test
  void everyClassLivesInANamedConcern() {
    Map<String, Integer> perConcern = new TreeMap<>();
    Set<String> strays = new TreeSet<>();
    for (JavaClass type : MAIN) {
      String concern = concernOf(type);
      if (concern == null) {
        if (!type.getPackageName().equals(ROOT)) {
          strays.add(type.getName());
        }
      } else if (CONCERNS.contains(concern)) {
        perConcern.merge(concern, 1, Integer::sum);
      } else {
        strays.add(type.getName());
      }
    }

    assertThat(strays).as("classes outside the named concern packages").isEmpty();
    assertThat(perConcern.keySet())
        .as("every named concern still holds a class")
        .isEqualTo(CONCERNS);
  }

  @Test
  void theConcernsFormNoCycle() {
    cycleFree(ROOT).check(MAIN);
  }

  @Test
  void theCycleRuleDetectsAPlantedCycle() {
    JavaClasses fixture = new ClassFileImporter().importClasses(CycleAlpha.class, CycleBeta.class);

    assertThatThrownBy(() -> cycleFree(ROOT + ".archfixture.cycle").check(fixture))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Cycle detected");
  }

  @Test
  void onlyRelayHoldsAnOutboundHttpClient() {
    outboundHttpOnlyInRelay().check(MAIN);
  }

  @Test
  void theOutboundRuleSelectsTheRelayAndEveryExceptionStillApplies() {
    long relayUsers =
        MAIN.stream()
            .filter(type -> RELAY.equals(concernOf(type)))
            .filter(type -> dependsOn(type, OUTBOUND_HTTP))
            .count();
    Set<String> stale = new TreeSet<>();
    for (String exception : OUTBOUND_HTTP_EXCEPTIONS.keySet()) {
      boolean holds =
          MAIN.stream()
              .filter(type -> topLevelName(type).equals(exception))
              .anyMatch(type -> dependsOn(type, OUTBOUND_HTTP));
      if (!holds) {
        stale.add(exception);
      }
    }

    assertThat(relayUsers)
        .as("relay classes holding an outbound HTTP client")
        .isGreaterThanOrEqualTo(RELAY_OUTBOUND_FLOOR);
    assertThat(stale).as("exceptions that no longer hold an outbound HTTP client").isEmpty();
  }

  @Test
  void theOutboundRuleDetectsAPlantedCaller() {
    JavaClasses fixture = new ClassFileImporter().importClasses(PlantedOutboundCaller.class);

    assertThatThrownBy(() -> outboundHttpOnlyInRelay().check(fixture))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(PlantedOutboundCaller.class.getSimpleName());
  }

  @Test
  void onlyTheRedisConcernsTouchRedis() {
    redisOnlyInItsConcerns().check(MAIN);
  }

  @Test
  void theRedisRuleSelectsEveryRedisConcern() {
    Map<String, Integer> perConcern = new TreeMap<>();
    for (JavaClass type : MAIN) {
      String concern = concernOf(type);
      if (concern != null && REDIS_CONCERNS.contains(concern) && dependsOn(type, REDIS)) {
        perConcern.merge(concern, 1, Integer::sum);
      }
    }

    assertThat(perConcern.keySet())
        .as("every Redis concern still touches Redis")
        .isEqualTo(REDIS_CONCERNS);
    assertThat(perConcern.values().stream().mapToInt(Integer::intValue).sum())
        .as("classes touching Redis")
        .isGreaterThanOrEqualTo(REDIS_USER_FLOOR);
  }

  @Test
  void theRedisRuleDetectsAPlantedToucher() {
    JavaClasses fixture = new ClassFileImporter().importClasses(PlantedRedisToucher.class);

    assertThatThrownBy(() -> redisOnlyInItsConcerns().check(fixture))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(PlantedRedisToucher.class.getSimpleName());
  }

  /**
   * The cycle rule over the packages directly below a root.
   *
   * @param root the package whose sub-packages are the slices
   * @return the rule
   */
  private static ArchRule cycleFree(String root) {
    return slices()
        .matching(root + ".(*)..")
        .should()
        .beFreeOfCycles()
        .as("the packages below " + root + " form no cycle");
  }

  /**
   * The rule that confines outbound HTTP clients to {@code relay}.
   *
   * @return the rule
   */
  private static ArchRule outboundHttpOnlyInRelay() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + "." + RELAY + "..")
        .and(
            describe(
                "are not a reviewed exception",
                type -> !OUTBOUND_HTTP_EXCEPTIONS.containsKey(topLevelName(type))))
        .should()
        .dependOnClassesThat(OUTBOUND_HTTP)
        .as("only relay holds an outbound HTTP client");
  }

  /**
   * The rule that confines Redis access to the Redis concerns.
   *
   * @return the rule
   */
  private static ArchRule redisOnlyInItsConcerns() {
    return noClasses()
        .that()
        .resideOutsideOfPackages(
            REDIS_CONCERNS.stream()
                .map(concern -> ROOT + "." + concern + "..")
                .toArray(String[]::new))
        .should()
        .dependOnClassesThat(REDIS)
        .as("only " + new TreeSet<>(REDIS_CONCERNS) + " touch Redis");
  }

  /**
   * Returns the concern a class belongs to.
   *
   * @param type the class
   * @return the first package segment below the root, or {@code null} for a class in the root
   */
  private static String concernOf(JavaClass type) {
    String pkg = type.getPackageName();
    if (!pkg.startsWith(ROOT + ".")) {
      return null;
    }
    String rest = pkg.substring(ROOT.length() + 1);
    int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }

  /**
   * Returns the name of the top-level class a class is nested in, or its own name.
   *
   * @param type the class
   * @return the binary name up to the first {@code $}
   */
  private static String topLevelName(JavaClass type) {
    String name = type.getName();
    int dollar = name.indexOf('$');
    return dollar < 0 ? name : name.substring(0, dollar);
  }

  /**
   * Whether a class depends directly on a type the predicate accepts.
   *
   * @param type the class
   * @param target the predicate on the dependency's target
   * @return {@code true} when one of its direct dependencies matches
   */
  private static boolean dependsOn(JavaClass type, Predicate<JavaClass> target) {
    return type.getDirectDependenciesFromSelf().stream()
        .map(Dependency::getTargetClass)
        .anyMatch(target);
  }

  /** A planted class outside {@code relay} that holds an outbound HTTP client. */
  static final class PlantedOutboundCaller {

    /** The client the rule must find. */
    private RestClient client;

    /**
     * Returns the planted client.
     *
     * @return the client, never set
     */
    RestClient client() {
      return client;
    }
  }

  /** A planted class outside the Redis concerns that touches Redis. */
  static final class PlantedRedisToucher {

    /** The template the rule must find. */
    private StringRedisTemplate template;

    /**
     * Returns the planted template.
     *
     * @return the template, never set
     */
    StringRedisTemplate template() {
      return template;
    }
  }
}
