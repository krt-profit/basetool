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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.architecture.fixture.MisplacedBackendClient;
import de.greluc.krt.profit.basetool.frontend.architecture.fixture.RogueBackendController;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriBuilderFactory;

/**
 * Keeps every controller on its domain's typed backend client (plan §5.9, F3, REQ-FE-029): only a
 * typed client in a {@code <domain>.client} package and the backend kernel call {@link
 * BackendApiClient}; a typed client is a {@code @Service} named {@code <Domain>BackendClient},
 * takes no {@code URI} or {@code UriBuilderFactory} and caches nothing, because a response depends
 * on the implicit bearer and org unit. Each rule is proven against the fixtures in {@code
 * architecture.fixture}.
 */
class TypedBackendClientTest {

  private static final String BASE = "de.greluc.krt.profit.basetool.frontend";

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(BASE);

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages(BASE + ".architecture.fixture");

  /** The package of a typed client: {@code <base>.<domain>.client}. */
  private static final Pattern CLIENT_PACKAGE =
      Pattern.compile(Pattern.quote(BASE) + "\\.[a-z]+\\.client");

  /** The backend kernel packages, which may call {@link BackendApiClient} directly. */
  private static final Set<String> KERNEL_PACKAGES =
      Set.of(BASE + ".kernel.backend", BASE + ".kernel.layout", BASE + ".kernel.security");

  /** The typed clients when the rule was introduced; fewer means the selection broke. */
  private static final int CLIENT_FLOOR = 21;

  @Test
  void onlyTheKernelAndTypedClientsCallTheKernelClient() {
    assertThat(callersOutsideTheSeam(MAIN))
        .as(
            "classes that call BackendApiClient but are neither kernel nor a typed client in a"
                + " <domain>.client package; call the domain's typed client instead")
        .isEmpty();
  }

  @Test
  void everyTypedClientIsAServiceNamedForItsDomain() {
    List<JavaClass> clients = typedClients(MAIN);

    assertThat(clients).hasSizeGreaterThanOrEqualTo(CLIENT_FLOOR);
    assertThat(clients)
        .allSatisfy(
            c -> {
              assertThat(c.getSimpleName()).endsWith("BackendClient");
              assertThat(c.isAnnotatedWith(Service.class))
                  .as(c.getName() + " is a @Service")
                  .isTrue();
            });
  }

  @Test
  void typedClientsNeitherRedirectTheBearerNorCache() {
    assertThat(clientViolations(typedClients(MAIN)))
        .as("typed clients take no URI or UriBuilderFactory and carry no cache annotation")
        .isEmpty();
  }

  @Test
  void theRulesCatchAPlantedControllerAndAMisplacedClient() {
    assertThat(callersOutsideTheSeam(FIXTURES))
        .contains(
            RogueBackendController.class.getSimpleName(),
            MisplacedBackendClient.class.getSimpleName());
    assertThat(clientViolations(List.of(FIXTURES.get(MisplacedBackendClient.class))))
        .containsExactlyInAnyOrder(
            "MisplacedBackendClient#read takes a java.net.URI",
            "MisplacedBackendClient#read is cached");
  }

  /**
   * Finds the classes that call {@link BackendApiClient} although they are neither kernel nor a
   * typed client.
   *
   * @param classes the classes to search
   * @return their simple names
   */
  static Set<String> callersOutsideTheSeam(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      JavaClass top = outermost(type);
      if (KERNEL_PACKAGES.contains(top.getPackageName())
          || CLIENT_PACKAGE.matcher(top.getPackageName()).matches()
          || top.isEquivalentTo(BackendApiClient.class)) {
        continue;
      }
      boolean calls =
          type.getDirectDependenciesFromSelf().stream()
              .anyMatch(d -> d.getTargetClass().isEquivalentTo(BackendApiClient.class));
      if (calls) {
        found.add(top.getSimpleName());
      }
    }
    return found;
  }

  /**
   * Finds the typed clients: the top-level classes in a {@code <domain>.client} package.
   *
   * @param classes the classes to search
   * @return the clients
   */
  static List<JavaClass> typedClients(JavaClasses classes) {
    return classes.stream()
        .filter(c -> c.getEnclosingClass().isEmpty())
        .filter(c -> CLIENT_PACKAGE.matcher(c.getPackageName()).matches())
        .filter(c -> c.getSimpleName().endsWith("Client"))
        .toList();
  }

  /**
   * Lists every breach of the typed-client rules.
   *
   * @param clients the clients to check
   * @return one line per breach
   */
  static List<String> clientViolations(List<JavaClass> clients) {
    List<String> violations = new ArrayList<>();
    for (JavaClass client : clients) {
      for (JavaMethod method : client.getMethods()) {
        String where = client.getSimpleName() + "#" + method.getName();
        for (JavaParameter parameter : method.getParameters()) {
          if (parameter.getRawType().isEquivalentTo(URI.class)
              || parameter.getRawType().isAssignableTo(UriBuilderFactory.class)) {
            violations.add(where + " takes a " + parameter.getRawType().getName());
          }
        }
        if (method.isAnnotatedWith(Cacheable.class)
            || method.isAnnotatedWith(CachePut.class)
            || method.isAnnotatedWith(CacheEvict.class)) {
          violations.add(where + " is cached");
        }
      }
      if (client.isAnnotatedWith(Controller.class)
          || client.isAnnotatedWith(RestController.class)) {
        violations.add(client.getSimpleName() + " is a controller");
      }
    }
    return violations;
  }

  private static JavaClass outermost(JavaClass type) {
    JavaClass current = type;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
