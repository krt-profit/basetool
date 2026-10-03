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

package de.greluc.krt.profit.basetool.backend.controller.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.config.ActingMemberFilter;
import de.greluc.krt.profit.basetool.backend.config.PendingApprovalAccessFilter;
import de.greluc.krt.profit.basetool.backend.config.TermsAcceptanceAccessFilter;
import de.greluc.krt.profit.basetool.backend.exception.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Pins the backend half of the frozen exchange relay seam to {@link ExchangeSeam} (REQ-XCH-037):
 * the operations the exchange controllers serve and the capability each demands, {@code
 * ActingMemberFilter}'s exact path list, the five relay headers, the capability scopes, the gate
 * codes with their statuses and the codes the gateway passes through or translates. The ingest
 * gateway pins its half to the same class, so a rename on one side alone fails a build.
 */
class ExchangeRelaySeamParityTest {

  private static final String BACKEND_PACKAGE = "de.greluc.krt.profit.basetool.backend";

  private static final String EXCHANGE_CONTROLLERS = BACKEND_PACKAGE + ".controller.exchange";

  private static final Pattern ALLOWS =
      Pattern.compile("^@exchangeGate\\.allows\\('([a-z.]+)', authentication\\)$");

  private static final String ALLOWS_ANY = "@exchangeGate.allowsAny(authentication)";

  @Test
  void theExchangeControllersServeExactlyTheRelayOperationsWithTheirCapabilities() {
    assertServedOperations(expectedOperations());
  }

  @Test
  void aRenamedRelayPathOrCapabilityIsCaught() {
    Map<String, String> renamedPath = new TreeMap<>(expectedOperations());
    renamedPath.put(
        "GET " + ExchangeSeam.RELAY_PREFIX + "/me/stocks",
        renamedPath.remove("GET " + ExchangeSeam.RELAY_PREFIX + "/me/stock"));
    assertThatThrownBy(() -> assertServedOperations(renamedPath))
        .isInstanceOf(AssertionError.class);

    Map<String, String> otherCapability = new TreeMap<>(expectedOperations());
    otherCapability.put("GET " + ExchangeSeam.RELAY_PREFIX + "/me/stock", "exchange.stock.write");
    assertThatThrownBy(() -> assertServedOperations(otherCapability))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theActingMemberFilterListsExactlyTheRelayPaths() {
    assertActingMemberPaths(ExchangeSeam.relayPaths());
  }

  @Test
  void aPathMissingFromTheActingMemberFilterIsCaught() {
    Set<String> extra = new TreeSet<>(ExchangeSeam.relayPaths());
    extra.add(ExchangeSeam.RELAY_PREFIX + "/me/stock/history");
    assertThatThrownBy(() -> assertActingMemberPaths(extra)).isInstanceOf(AssertionError.class);
  }

  @Test
  void theRelayHeadersAreTheSeamsHeaders() {
    assertRelayHeaders(ExchangeSeam.RELAY_HEADERS);
    assertThatThrownBy(
            () ->
                assertRelayHeaders(
                    List.of(
                        "X-Ingest-On-Behalf-Of",
                        "X-Exchange-Client",
                        "X-Exchange-Capability",
                        "X-Exchange-Installation",
                        "X-Exchange-Connected-At")))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void theCapabilityScopesAreTheSeamsScopes() {
    assertCapabilityScopes(ExchangeSeam.CAPABILITY_SCOPES);
    Set<String> renamed = new HashSet<>(ExchangeSeam.CAPABILITY_SCOPES);
    renamed.remove("exchange.hangar.read");
    renamed.add("exchange.ships.read");
    assertThatThrownBy(() -> assertCapabilityScopes(renamed)).isInstanceOf(AssertionError.class);
  }

  @Test
  void theBackendGateAnswersEveryGateCodeWithTheGatewaysStatus() {
    assertGateStatuses(ExchangeSeam.GATE_STATUSES);
    Map<String, Integer> otherStatus = new HashMap<>(ExchangeSeam.GATE_STATUSES);
    otherStatus.put("CLIENT_REVOKED", 403);
    assertThatThrownBy(() -> assertGateStatuses(otherStatus)).isInstanceOf(AssertionError.class);
  }

  @Test
  void everyCodeTheBackendRelaysIsOneTheGatewayPassesOrTranslates() throws Exception {
    assertRelayedCodes(ExchangeSeam.PASSED_THROUGH_CODES, ExchangeSeam.TRANSLATED_CODES.keySet());
    Set<String> withoutTerms = new HashSet<>(ExchangeSeam.PASSED_THROUGH_CODES);
    withoutTerms.remove("TERMS_NOT_ACCEPTED");
    assertThatThrownBy(
            () -> assertRelayedCodes(withoutTerms, ExchangeSeam.TRANSLATED_CODES.keySet()))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * Returns the seam's operations as the backend serves them.
   *
   * @return {@code METHOD /api/v1/exchange/...} to the demanded capability
   */
  private static @NotNull Map<String, String> expectedOperations() {
    Map<String, String> expected = new TreeMap<>();
    ExchangeSeam.OPERATIONS.forEach(
        operation -> expected.put(operation.relayed(), operation.capability()));
    assertThat(expected).as("the seam lists 14 relay operations").hasSize(14);
    return expected;
  }

  /**
   * Asserts that the backend's controllers serve exactly the given operations under the relay
   * prefix, from the exchange controllers only, each behind {@code @exchangeGate} with the given
   * capability.
   *
   * @param expected {@code METHOD path} to capability, {@link ExchangeSeam#ANY_CAPABILITY} for any
   */
  private static void assertServedOperations(@NotNull Map<String, String> expected) {
    Map<String, String> served = new TreeMap<>();
    Set<String> owners = new TreeSet<>();
    for (Class<?> controller : controllers(BACKEND_PACKAGE)) {
      RequestMapping type =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      String[] prefixes = type == null || type.path().length == 0 ? new String[] {""} : type.path();
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
          continue;
        }
        String[] paths = mapping.path().length == 0 ? new String[] {""} : mapping.path();
        for (String prefix : prefixes) {
          for (String path : paths) {
            String full = prefix + path;
            if (!full.equals(ExchangeSeam.RELAY_PREFIX)
                && !full.startsWith(ExchangeSeam.RELAY_PREFIX + "/")) {
              continue;
            }
            assertThat(controller.getPackageName())
                .as("%s serves %s outside the exchange controllers", controller, full)
                .isEqualTo(EXCHANGE_CONTROLLERS);
            assertThat(mapping.method()).as(full).hasSize(1);
            String key = mapping.method()[0].name() + " " + full;
            assertThat(served.put(key, capability(method))).as("served twice: " + key).isNull();
            owners.add(controller.getSimpleName());
          }
        }
      }
    }
    assertThat(owners).as("the exchange controllers").hasSize(8);
    assertThat(served).isEqualTo(expected);
  }

  /**
   * Reads the capability an exchange handler's {@code @PreAuthorize} demands.
   *
   * @param handler the handler method
   * @return the capability, or {@link ExchangeSeam#ANY_CAPABILITY} for {@code allowsAny}
   */
  private static @NotNull String capability(@NotNull Method handler) {
    PreAuthorize gate = AnnotatedElementUtils.findMergedAnnotation(handler, PreAuthorize.class);
    assertThat(gate).as("%s has no @PreAuthorize", handler).isNotNull();
    if (ALLOWS_ANY.equals(gate.value())) {
      return ExchangeSeam.ANY_CAPABILITY;
    }
    Matcher matcher = ALLOWS.matcher(gate.value());
    assertThat(matcher.matches())
        .as("%s is not behind @exchangeGate: %s", handler, gate.value())
        .isTrue();
    return matcher.group(1);
  }

  /**
   * Finds every {@code @RestController} below a package.
   *
   * @param basePackage the package to scan
   * @return the controller classes
   */
  private static @NotNull List<Class<?>> controllers(@NotNull String basePackage) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<Class<?>> found =
        scanner.findCandidateComponents(basePackage).stream()
            .map(BeanDefinition::getBeanClassName)
            .map(name -> ClassUtils.resolveClassName(name, null))
            .collect(Collectors.toList());
    assertThat(found).as("the controller scan found nothing").hasSizeGreaterThan(50);
    return found;
  }

  /**
   * Asserts that {@code ActingMemberFilter} admits an acting member on exactly the given paths,
   * each listed once and as an exact path, never a wildcard.
   *
   * @param expected the backend paths
   */
  private static void assertActingMemberPaths(@NotNull Set<String> expected) {
    List<?> patterns = (List<?>) staticField(ActingMemberFilter.class, "EXCHANGE_PATHS");
    List<String> listed = patterns.stream().map(p -> ((PathPattern) p).getPatternString()).toList();
    assertThat(listed).as("13 exact paths").hasSize(13).doesNotHaveDuplicates();
    assertThat(listed).allSatisfy(path -> assertThat(path).doesNotContain("*", "{"));
    assertThat(new TreeSet<>(listed)).isEqualTo(new TreeSet<>(expected));
  }

  /**
   * Asserts that the backend reads the given relay headers.
   *
   * @param expected the on-behalf-of, client, capabilities, installation and connected-at headers
   */
  private static void assertRelayHeaders(@NotNull List<String> expected) {
    assertThat(
            List.of(
                ActingMemberHeader.ON_BEHALF_OF_HEADER,
                ActingMemberHeader.EXCHANGE_CLIENT_HEADER,
                ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER,
                ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER,
                ActingMemberHeader.EXCHANGE_CONNECTED_AT_HEADER))
        .isEqualTo(expected);
  }

  /**
   * Asserts that the backend knows exactly the given capability scopes.
   *
   * @param expected the scopes
   */
  private static void assertCapabilityScopes(@NotNull Set<String> expected) {
    Set<String> scopes =
        Arrays.stream(ExchangeCapability.values())
            .map(ExchangeCapability::getScope)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(scopes).hasSize(10).isEqualTo(new TreeSet<>(expected));
  }

  /**
   * Asserts that the backend gate's refusals carry exactly the given codes and statuses.
   *
   * @param expected each gate code to its status
   */
  private static void assertGateStatuses(@NotNull Map<String, Integer> expected) {
    List<Supplier<ExchangeProblemException>> refusals =
        List.of(
            ExchangeProblemException::exchangeDisabled,
            ExchangeProblemException::clientNotAllowed,
            ExchangeProblemException::clientSuspended,
            ExchangeProblemException::installationRevoked,
            ExchangeProblemException::clientRevoked,
            ExchangeProblemException::scopeMissing,
            () -> ExchangeProblemException.registryUnavailable(new IllegalStateException("x")));
    Map<String, Integer> answered = new TreeMap<>();
    for (Supplier<ExchangeProblemException> refusal : refusals) {
      ExchangeProblemException problem = refusal.get();
      answered.put(problem.code(), problem.status().value());
    }
    assertThat(answered).hasSize(7).isEqualTo(new TreeMap<>(expected));
  }

  /**
   * Asserts that every refusal code the backend's exchange path emits is one the gateway passes
   * through or translates.
   *
   * @param passedThrough the codes the gateway passes as they are
   * @param translated the backend codes the gateway translates
   * @throws Exception if a constant cannot be read
   */
  private static void assertRelayedCodes(
      @NotNull Set<String> passedThrough, @NotNull Set<String> translated) throws Exception {
    Set<String> passed =
        Set.of(
            (String) staticField(TermsAcceptanceAccessFilter.class, "CODE_TERMS_NOT_ACCEPTED"),
            (String) staticField(PendingApprovalAccessFilter.class, "CODE_PENDING_APPROVAL"),
            (String) staticField(PendingApprovalAccessFilter.class, "CODE_NO_ROLE"),
            (String) staticField(ActingMemberFilter.class, "CODE_ACTING_MEMBER_REFUSED"),
            ExchangeProblemException.cursorExpired().code(),
            ExchangeProblemException.massChangeConfirmationRequired().code());
    assertThat(passedThrough).containsAll(passed);
    assertThat(
            Set.of(
                GlobalExceptionHandler.CODE_ACCESS_DENIED,
                GlobalExceptionHandler.CODE_VALIDATION_FAILED,
                GlobalExceptionHandler.CODE_BAD_REQUEST,
                GlobalExceptionHandler.CODE_OPTIMISTIC_LOCK))
        .isEqualTo(translated);
  }

  /**
   * Reads a static field, whatever its visibility.
   *
   * @param owner the declaring class
   * @param name the field's name
   * @return its value
   */
  private static Object staticField(@NotNull Class<?> owner, @NotNull String name) {
    try {
      Field field = owner.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(owner.getSimpleName() + "." + name + " is gone", e);
    }
  }
}
