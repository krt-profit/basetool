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

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.frontend.architecture.fixture.LeakyHttpClient;
import de.greluc.krt.profit.basetool.frontend.architecture.fixture.RogueWebClientHolder;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendSideChannels;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.WebClientConfig;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.UriBuilderFactory;

/**
 * Keeps every backend {@link WebClient} inside the backend kernel (REQ-FE-029, plan G-17).
 *
 * <p>Only {@link WebClientConfig} builds a client; only the kernel — {@link WebClientConfig},
 * {@link BackendApiClient} and {@link BackendSideChannels} — holds one, with no exception outside
 * it; HTTP-interface clients are created only by the kernel, take no {@code URI}, {@code
 * UriBuilderFactory} or {@code @CookieValue} parameter, name no absolute URL and carry no cache
 * annotation. Each rule is proven against the fixtures in {@code architecture.fixture}.
 */
class WebClientConfinementTest {

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.frontend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter()
          .importPackages("de.greluc.krt.profit.basetool.frontend.architecture.fixture");

  /** The one class that builds backend clients. */
  private static final Set<String> BUILDERS = Set.of(WebClientConfig.class.getName());

  /** The classes that hold a backend client: the bean factory and the backend kernel. */
  private static final Set<String> HOLDERS =
      Set.of(
          WebClientConfig.class.getName(),
          BackendApiClient.class.getName(),
          BackendSideChannels.class.getName());

  /** The classes allowed to create HTTP-interface clients over a {@code WebClient}. */
  private static final Set<String> CLIENT_FACTORIES = Set.of(WebClientConfig.class.getName());

  /** The HTTP-interface clients in the main sources when the rule was introduced. */
  private static final int HTTP_INTERFACE_FLOOR = 0;

  /** The exchange annotations whose {@code url}/{@code value} names a request path. */
  private static final List<Class<? extends Annotation>> EXCHANGES =
      List.of(
          HttpExchange.class,
          GetExchange.class,
          PostExchange.class,
          PutExchange.class,
          PatchExchange.class,
          DeleteExchange.class);

  @Test
  void onlyTheKernelConfigurationBuildsAWebClient() {
    assertThat(builders(MAIN))
        .as(
            "only WebClientConfig builds or mutates a WebClient; a second builder loses the"
                + " filters")
        .containsExactlyInAnyOrderElementsOf(BUILDERS);
  }

  @Test
  void onlyTheKernelHoldsAWebClient() {
    assertThat(holders(MAIN))
        .as(
            "a WebClient field, constructor parameter or @Bean is confined to the backend kernel;"
                + " anything else calls BackendApiClient or BackendSideChannels")
        .containsExactlyInAnyOrderElementsOf(HOLDERS);
  }

  @Test
  void onlyTheKernelConfigurationCreatesHttpInterfaceClients() {
    assertThat(clientFactories(MAIN)).isSubsetOf(CLIENT_FACTORIES);
  }

  @Test
  void httpInterfaceClientsNeverRedirectTheBearerOrCacheAResponse() {
    assertThat(httpInterfaces(MAIN)).hasSizeGreaterThanOrEqualTo(HTTP_INTERFACE_FLOOR);
    assertThat(httpInterfaceViolations(MAIN))
        .as(
            "HTTP-interface clients take no URI, UriBuilderFactory or @CookieValue parameter, name"
                + " only relative paths and carry no cache annotation")
        .isEmpty();
  }

  @Test
  void theRulesCatchAPlantedRogueClient() {
    String rogue = RogueWebClientHolder.class.getName();

    assertThat(builders(FIXTURES)).contains(rogue);
    assertThat(holders(FIXTURES)).contains(rogue);
    assertThat(clientFactories(FIXTURES)).contains(rogue);
  }

  @Test
  void theRulesCatchAPlantedLeakyHttpInterface() {
    String leaky = LeakyHttpClient.class.getSimpleName();

    assertThat(httpInterfaces(FIXTURES)).contains(LeakyHttpClient.class.getName());
    assertThat(httpInterfaceViolations(FIXTURES))
        .containsExactlyInAnyOrder(
            leaky + " is @Cacheable",
            leaky + "#byUri takes a java.net.URI",
            leaky + "#byFactory takes a org.springframework.web.util.UriBuilderFactory",
            leaky + "#withCookie takes a @CookieValue",
            leaky + "#absolute names an absolute URL",
            leaky + "#cached is @Cacheable");
  }

  /**
   * Finds the classes that call a {@code WebClient} factory or depend on {@code WebClient.Builder}.
   *
   * @param classes the classes to search
   * @return their names
   */
  static Set<String> builders(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      boolean builds =
          type.getMethodCallsFromSelf().stream()
              .anyMatch(
                  call ->
                      call.getTargetOwner().isEquivalentTo(WebClient.class)
                          && Set.of("builder", "create", "mutate").contains(call.getName()));
      boolean usesBuilder =
          type.getDirectDependenciesFromSelf().stream()
              .anyMatch(d -> d.getTargetClass().isEquivalentTo(WebClient.Builder.class));
      if (builds || usesBuilder) {
        found.add(outermost(type).getName());
      }
    }
    return found;
  }

  /**
   * Finds the classes with a {@code WebClient} field, constructor parameter or {@code @Bean} method
   * returning one.
   *
   * @param classes the classes to search
   * @return their names
   */
  static Set<String> holders(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      boolean field =
          type.getFields().stream().anyMatch(f -> f.getRawType().isEquivalentTo(WebClient.class));
      boolean constructor =
          type.getConstructors().stream()
              .flatMap(c -> c.getRawParameterTypes().stream())
              .anyMatch(p -> p.isEquivalentTo(WebClient.class));
      boolean bean =
          type.getMethods().stream()
              .anyMatch(
                  m ->
                      m.isAnnotatedWith(Bean.class)
                          && m.getRawReturnType().isEquivalentTo(WebClient.class));
      if (field || constructor || bean) {
        found.add(outermost(type).getName());
      }
    }
    return found;
  }

  /**
   * Finds the classes that touch the HTTP-interface client factories.
   *
   * @param classes the classes to search
   * @return their names
   */
  static Set<String> clientFactories(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      if (type.getDirectDependenciesFromSelf().stream()
          .anyMatch(
              d ->
                  d.getTargetClass().isEquivalentTo(HttpServiceProxyFactory.class)
                      || d.getTargetClass().isEquivalentTo(WebClientAdapter.class))) {
        found.add(outermost(type).getName());
      }
    }
    return found;
  }

  /**
   * Finds the HTTP-interface clients: interfaces with an exchange annotation on the type or a
   * method.
   *
   * @param classes the classes to search
   * @return their names
   */
  static Set<String> httpInterfaces(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      if (type.isInterface()
          && (isExchange(type.getAnnotations().stream().map(a -> a.getRawType()))
              || type.getMethods().stream()
                  .anyMatch(
                      m -> isExchange(m.getAnnotations().stream().map(a -> a.getRawType()))))) {
        found.add(type.getName());
      }
    }
    return found;
  }

  /**
   * Lists every rule breach of the HTTP-interface clients.
   *
   * @param classes the classes to search
   * @return one line per breach
   */
  static List<String> httpInterfaceViolations(JavaClasses classes) {
    List<String> violations = new ArrayList<>();
    for (String name : httpInterfaces(classes)) {
      JavaClass type = classes.get(name);
      if (isCaching(type.getAnnotations().stream().map(a -> a.getRawType()))) {
        violations.add(type.getSimpleName() + " is @Cacheable");
      }
      absoluteUrl(type.getAnnotations())
          .ifPresent(u -> violations.add(type.getSimpleName() + " names an absolute URL"));
      for (JavaMethod method : type.getMethods()) {
        String where = type.getSimpleName() + "#" + method.getName();
        for (JavaParameter parameter : method.getParameters()) {
          if (parameter.getRawType().isEquivalentTo(URI.class)
              || parameter.getRawType().isAssignableTo(UriBuilderFactory.class)) {
            violations.add(where + " takes a " + parameter.getRawType().getName());
          }
          if (parameter.isAnnotatedWith(CookieValue.class)) {
            violations.add(where + " takes a @CookieValue");
          }
        }
        if (isCaching(method.getAnnotations().stream().map(a -> a.getRawType()))) {
          violations.add(where + " is @Cacheable");
        }
        absoluteUrl(method.getAnnotations())
            .ifPresent(u -> violations.add(where + " names an absolute URL"));
      }
    }
    return violations;
  }

  private static boolean isExchange(Stream<JavaClass> annotationTypes) {
    return annotationTypes.anyMatch(t -> EXCHANGES.stream().anyMatch(t::isEquivalentTo));
  }

  private static boolean isCaching(Stream<JavaClass> annotationTypes) {
    return annotationTypes.anyMatch(
        t ->
            t.isEquivalentTo(Cacheable.class)
                || t.isEquivalentTo(CachePut.class)
                || t.isEquivalentTo(CacheEvict.class));
  }

  private static Optional<String> absoluteUrl(Set<? extends JavaAnnotation<?>> annotations) {
    return annotations.stream()
        .filter(a -> EXCHANGES.stream().anyMatch(a.getRawType()::isEquivalentTo))
        .flatMap(a -> Stream.of(a.get("url"), a.get("value")))
        .flatMap(Optional::stream)
        .map(String::valueOf)
        .filter(v -> v.contains("://") || v.startsWith("//"))
        .findFirst();
  }

  private static JavaClass outermost(JavaClass type) {
    JavaClass current = type;
    while (current.getEnclosingClass().isPresent()) {
      current = current.getEnclosingClass().get();
    }
    return current;
  }
}
