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

package de.greluc.krt.profit.basetool.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankSecurityService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppsGate;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeGate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionAccessPolicy;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.operation.internal.OperationAccessPolicy;
import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionAnalyzer.Analysis;
import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionAnalyzer.BeanCall;
import de.greluc.krt.profit.basetool.backend.security.SecurityExpressionSources.Declared;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.InventoryAccessPolicy;
import de.greluc.krt.profit.basetool.backend.service.OrgRoleManagementSecurityService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.SpecialCommandSecurityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * Holds every backend security expression to the constant-SpEL rules and ratchets the number of
 * bean references, so a gate that loses its bean check is noticed (REQ-SEC-075, plan guard G-04).
 */
class SecurityExpressionRulesTest {

  /** Declared security expressions on 2026-10-02; fewer means the scan lost its subject. */
  private static final int EXPRESSION_FLOOR = 441;

  /**
   * Bean references per security bean. A count below its floor means a gate lost a bean check; a
   * count above it is new coverage, and the floor is raised with it.
   */
  private static final Map<String, Integer> REFERENCE_FLOORS =
      Map.ofEntries(
          Map.entry("ownerScopeService", 6),
          Map.entry("operationAccessPolicy", 8),
          Map.entry("refineryAccessPolicy", 8),
          Map.entry("missionSecurityService", 40),
          Map.entry("authHelperService", 17),
          Map.entry("exchangeGate", 14),
          Map.entry("orgRoleManagementSecurityService", 13),
          Map.entry("bankSecurityService", 10),
          Map.entry("specialCommandSecurityService", 5),
          Map.entry("connectedAppsGate", 1),
          Map.entry("jobOrderAccessPolicy", 29),
          Map.entry("missionAccessPolicy", 7),
          Map.entry("inventoryAccessPolicy", 9));

  /**
   * The classes that carry the security beans' explicit names; a bean referenced from SpEL must be
   * named by its class, so a class rename cannot rename the bean.
   */
  private static final Map<String, Class<?>> NAMED_SECURITY_BEANS =
      Map.ofEntries(
          Map.entry("ownerScopeService", OwnerScopeService.class),
          Map.entry("operationAccessPolicy", OperationAccessPolicy.class),
          Map.entry("refineryAccessPolicy", RefineryAccessPolicy.class),
          Map.entry("missionSecurityService", MissionSecurityService.class),
          Map.entry("authHelperService", AuthHelperService.class),
          Map.entry("exchangeGate", ExchangeGate.class),
          Map.entry("orgRoleManagementSecurityService", OrgRoleManagementSecurityService.class),
          Map.entry("bankSecurityService", BankSecurityService.class),
          Map.entry("specialCommandSecurityService", SpecialCommandSecurityService.class),
          Map.entry("connectedAppsGate", ConnectedAppsGate.class),
          Map.entry("jobOrderAccessPolicy", JobOrderAccessPolicy.class),
          Map.entry("missionAccessPolicy", MissionAccessPolicy.class),
          Map.entry("inventoryAccessPolicy", InventoryAccessPolicy.class));

  @Test
  @DisplayName("every security expression follows the constant-SpEL rules")
  void everyExpressionIsConstantSpel() {
    List<Declared> declared = SecurityExpressionSources.declared();
    assertThat(declared)
        .as("the scan must find every security expression, or it checks nothing")
        .hasSizeGreaterThanOrEqualTo(EXPRESSION_FLOOR);

    List<String> violations = new ArrayList<>();
    for (Declared entry : declared) {
      violations.addAll(
          SecurityExpressionAnalyzer.analyze(entry.expression(), entry.origin()).violations());
    }
    assertThat(violations).as("security expressions outside the constant-SpEL rules").isEmpty();
  }

  @Test
  @DisplayName("no security bean loses references to below its floor")
  void beanReferenceCountsDoNotDrop() {
    Map<String, Integer> counts = referenceCounts();
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    int floor = REFERENCE_FLOORS.values().stream().mapToInt(Integer::intValue).sum();

    assertThat(total)
        .as("bean references in security expressions (per bean: %s)", counts)
        .isGreaterThanOrEqualTo(floor);
    REFERENCE_FLOORS.forEach(
        (bean, minimum) ->
            assertThat(counts.getOrDefault(bean, 0))
                .as(
                    "references to @%s dropped below %d: a gate lost its bean check. If that is"
                        + " intended, lower the floor in the same change and say why",
                    bean, minimum)
                .isGreaterThanOrEqualTo(minimum));
  }

  @Test
  @DisplayName("every bean a security expression names carries that name explicitly")
  void everyReferencedBeanIsNamedExplicitly() {
    Map<String, Class<?>> declaredNames = explicitComponentNames();
    for (String bean : referenceCounts().keySet()) {
      assertThat(declaredNames)
          .as(
              "@%s is referenced from a security expression, so its class must declare that"
                  + " name in @Service/@Component and be listed in NAMED_SECURITY_BEANS",
              bean)
          .containsKey(bean);
      assertThat(NAMED_SECURITY_BEANS).containsEntry(bean, declaredNames.get(bean));
    }
    NAMED_SECURITY_BEANS.forEach(
        (bean, type) -> assertThat(declaredNames).containsEntry(bean, type));
  }

  /**
   * Counts the bean references of every declared expression per bean name.
   *
   * @return bean name to number of references, sorted by name
   */
  static Map<String, Integer> referenceCounts() {
    Map<String, Integer> counts = new TreeMap<>();
    for (Declared entry : SecurityExpressionSources.declared()) {
      Analysis analysis = SecurityExpressionAnalyzer.analyze(entry.expression(), entry.origin());
      for (BeanCall call : analysis.beanCalls()) {
        counts.merge(call.bean(), 1, Integer::sum);
      }
    }
    return counts;
  }

  /**
   * Maps every explicitly declared component name of the main classes to its class.
   *
   * @return explicit bean name to declaring class
   */
  private static Map<String, Class<?>> explicitComponentNames() {
    Map<String, Class<?>> names = new TreeMap<>();
    for (JavaClass javaClass :
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(SecurityExpressionSources.BASE_PACKAGE)) {
      Class<?> type = javaClass.reflect();
      Component component = AnnotatedElementUtils.findMergedAnnotation(type, Component.class);
      if (component != null && !component.value().isEmpty()) {
        names.put(component.value(), type);
      }
    }
    return names;
  }
}
