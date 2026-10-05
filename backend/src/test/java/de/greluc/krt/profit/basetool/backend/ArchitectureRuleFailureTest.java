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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import de.greluc.krt.profit.basetool.architecture.fixtures.bank.FixtureLedgerRepository;
import de.greluc.krt.profit.basetool.architecture.fixtures.bank.LedgerFixtureService;
import de.greluc.krt.profit.basetool.architecture.fixtures.bankseam.SanctionedBridge;
import de.greluc.krt.profit.basetool.architecture.fixtures.cycles.alpha.Alpha;
import de.greluc.krt.profit.basetool.architecture.fixtures.exchange.ExchangeFixtureController;
import de.greluc.krt.profit.basetool.architecture.fixtures.exchangeleak.FixtureScopeService;
import de.greluc.krt.profit.basetool.architecture.fixtures.layers.FixtureMapper;
import de.greluc.krt.profit.basetool.architecture.fixtures.layers.event.FixtureEvent;
import de.greluc.krt.profit.basetool.architecture.fixtures.layers.integration.FixtureClient;
import de.greluc.krt.profit.basetool.architecture.fixtures.layers.support.Helper;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureCreateRequest;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureMission;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureMissionRepository;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureParticipantService;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureSettingsAccess;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureSettingsDto;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureSquadron;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureTopic;
import de.greluc.krt.profit.basetool.architecture.fixtures.mission.FixtureUpdateRequest;
import de.greluc.krt.profit.basetool.architecture.fixtures.modules.orders.service.OrderService;
import de.greluc.krt.profit.basetool.architecture.fixtures.profit.MissionLedgerFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.scope.OrgScopeFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.scwiki.FixtureWikiClient;
import de.greluc.krt.profit.basetool.architecture.fixtures.security.CascadeFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.security.LeakyService;
import de.greluc.krt.profit.basetool.architecture.fixtures.security.ScopeFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.tenancy.GuardFixture;
import de.greluc.krt.profit.basetool.architecture.fixtures.tenancy.ScopedFixtureService;
import de.greluc.krt.profit.basetool.architecture.fixtures.unclassified.FixtureBankThing;
import de.greluc.krt.profit.basetool.architecture.fixtures.web.FixtureAudit;
import de.greluc.krt.profit.basetool.architecture.fixtures.web.FixtureMembershipMapper;
import de.greluc.krt.profit.basetool.architecture.fixtures.web.FixturePiiDto;
import de.greluc.krt.profit.basetool.architecture.fixtures.web.UngatedController;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Proves that every rule family of {@link ArchitectureTest} is able to fail: each test evaluates a
 * rule against planted violations in {@code de.greluc.krt.profit.basetool.architecture.fixtures}
 * (test sources outside the production import and outside the component scan) and asserts the
 * violation is reported.
 */
class ArchitectureRuleFailureTest {

  private static final JavaClasses SECURITY = importPackageOf(LeakyService.class);
  private static final JavaClasses WEB = importPackageOf(UngatedController.class);
  private static final JavaClasses EXCHANGE =
      importPackageOf(ExchangeFixtureController.class, FixtureScopeService.class);
  private static final JavaClasses LAYERS = importPackageOf(FixtureMapper.class);
  private static final JavaClasses MISSION = importPackageOf(FixtureMission.class);
  private static final JavaClasses TENANCY = importPackageOf(ScopedFixtureService.class);
  private static final JavaClasses BANK =
      importPackageOf(
          LedgerFixtureService.class,
          SanctionedBridge.class,
          OrgScopeFixture.class,
          MissionLedgerFixture.class);

  private static JavaClasses importPackageOf(Class<?>... anchors) {
    return new ClassFileImporter().importPackagesOf(anchors);
  }

  private static void assertReports(ArchRule rule, JavaClasses classes, String expected) {
    EvaluationResult result = rule.evaluate(classes);
    assertThat(result.hasViolation()).as("%s reports a violation", rule.getDescription()).isTrue();
    assertThat(result.getFailureReport().getDetails())
        .as("%s names %s", rule.getDescription(), expected)
        .anyMatch(detail -> detail.contains(expected));
  }

  @Test
  void securityContextRulesFailOnEveryLayerSelectedByRole() {
    assertReports(
        ArchitectureTest.serviceLayerShouldNotReachIntoSecurityContextRule(
            ArchitectureTest.serviceSelection()),
        SECURITY,
        "LeakyService");
    assertReports(
        ArchitectureTest.controllerLayerShouldNotReachIntoSecurityContextRule(),
        SECURITY,
        "LeakyController");
    assertReports(
        ArchitectureTest.mapperLayerShouldNotReachIntoSecurityContextRule(),
        SECURITY,
        "LeakyMapper");
  }

  @Test
  void theAuthenticationSeamRuleFails() {
    assertReports(
        ArchitectureTest.identityMustBeReadThroughTheSeamRule(
            ArchitectureTest.outsideTheAuthenticationSeam()),
        SECURITY,
        "LeakyService.isJwt");
  }

  @Test
  void theForbiddenCollaboratorRulesFail() {
    assertReports(
        ArchitectureTest.mustNotDependOnRule(CascadeFixture.class, ScopeFixture.class),
        SECURITY,
        "CascadeFixture");
  }

  @Test
  void controllerReturnAndWrapperRulesFail() {
    assertReports(
        ArchitectureTest.controllerMethodsShouldNotReturnJpaEntitiesRule(
            ArchitectureTest.publicMethodsOf(ArchitectureTest.CONTROLLER_CODE)),
        WEB,
        "UngatedController.entity()");
    assertReports(
        ArchitectureTest.controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappersRule(
            ArchitectureTest.publicMethodsOf(ArchitectureTest.CONTROLLER_CODE)),
        WEB,
        "UngatedController.entities()");
  }

  @Test
  void controllerDependencyRulesFail() {
    assertReports(
        ArchitectureTest.controllersMustNotInjectRule(FixtureMembershipMapper.class),
        WEB,
        "membershipMapper");
    assertReports(
        ArchitectureTest.controllerLayerShouldNotDependOnRepositoryLayerRule(),
        WEB,
        "FixtureRepository");
    assertReports(
        ArchitectureTest.controllerLayerMustNotWriteAuditRowsDirectlyRule(FixtureAudit.class),
        WEB,
        "FixtureAudit.record()");
  }

  @Test
  void authorisationAnnotationRulesFail() {
    assertReports(
        ArchitectureTest.everyRestControllerShouldDeclareAnAuthorisationAnnotationRule(),
        WEB,
        "UngatedController");
    assertReports(
        ArchitectureTest.endpointsMustDeclareAnAuthorisationAnnotationRule(
            ArchitectureTest.publicMethodsOf(ArchitectureTest.CONTROLLER_CODE)
                .and(ArchitectureTest.annotatedWithAnyOf(GetMapping.class, RequestMapping.class))),
        WEB,
        "UngatedController.entity()");
    assertReports(
        ArchitectureTest.endpointsMustDeclareAnAuthorisationAnnotationRule(
            ArchitectureTest.bodyAcceptingWrites()),
        WEB,
        "UngatedController.write(");
  }

  @Test
  void thePermitAllAllowListFails() {
    assertReports(
        ArchitectureTest.permitAllRule(Set.of(), Set.of()), WEB, "PublicController.open()");
  }

  @Test
  void thePeerRedactionRuleFails() {
    Set<Class<?>> piiDtos = Set.of(FixturePiiDto.class);
    assertReports(
        ArchitectureTest.peerReadableMissionEndpointsMustRedactPiiRule(
            ArchitectureTest.peerReadableEndpoints(piiDtos), piiDtos),
        WEB,
        "PublicController.pii()");
  }

  @Test
  void persistenceRulesFail() {
    assertReports(ArchitectureTest.toOneAssociationsAreDeclaredLazyRule(), WEB, "parent");
    assertReports(
        ArchitectureTest.noSquadronIdJoinColumnRule(
            ArchitectureTest.nonInterfaces(ArchitectureTest.MODEL_CODE), Set.of()),
        WEB,
        "FixtureEntity#squadron");
    assertReports(
        ArchitectureTest.repositoriesMustNotDeclareNoArgFindAllRule(
            ArchitectureTest.methodsOf(ArchitectureTest.REPOSITORY_CODE)),
        WEB,
        "FixtureRepository.findAll()");
  }

  @Test
  void theReadOnlyTransactionRuleFails() {
    assertReports(
        ArchitectureTest.mutatingServiceMethodsInReadOnlyClassesRule(), WEB, "createThing");
  }

  @Test
  void exchangeRulesFail() {
    assertReports(
        ArchitectureTest.everyExchangeControllerMethodCarriesTheExchangeGateRule(
            ArchitectureTest.publicMethodsOf(
                ArchitectureTest.CONTROLLER_CODE.and(ArchitectureTest.EXCHANGE_DOMAIN))),
        EXCHANGE,
        "ExchangeFixtureController.read()");
    assertReports(
        ArchitectureTest.exchangeControllersCallExchangeServicesOnlyRule(
            ArchitectureTest.CONTROLLER_CODE.and(ArchitectureTest.EXCHANGE_DOMAIN)),
        EXCHANGE,
        "RogueService");
    assertReports(
        ArchitectureTest.exchangeDtosStayInTheExchangeLayerRule(ArchitectureTest.exchangeDtos()),
        EXCHANGE,
        "Leaker");
    assertReports(
        ArchitectureTest.exchangeServicesNeverUseAdminGatesRule(
            ArchitectureTest.SERVICE_CODE.and(ArchitectureTest.EXCHANGE_DOMAIN),
            FixtureScopeService.class),
        EXCHANGE,
        "adminThing");
  }

  @Test
  void theLeafHelperAllowListFails() {
    assertReports(
        ArchitectureTest.leafHelpersMustStayDependencyLeavesRule(
            ArchitectureTest.packageTreeOf("fixture support", Helper.class),
            ArchitectureTest.packageTreeOf("fixture support", Helper.class)
                .or(ArchitectureTest.MODEL_CODE)
                .or(ArchitectureTest.REPOSITORY_CODE),
            Alpha.class.getPackageName().replace(".cycles.alpha", "")),
        LAYERS,
        "SomeService");
  }

  @Test
  void layerDependencyRulesFail() {
    assertReports(
        ArchitectureTest.mustNotDependOnServicesRule(ArchitectureTest.MAPPER_CODE),
        LAYERS,
        "FixtureMapper");
    assertReports(
        ArchitectureTest.mustNotDependOnServicesRule(
            ArchitectureTest.packageTreeOf("fixture integration", FixtureClient.class)),
        LAYERS,
        "FixtureClient");
    assertReports(
        ArchitectureTest.mustNotDependOnServicesRule(
            ArchitectureTest.packageTreeOf("fixture events", FixtureEvent.class)),
        LAYERS,
        "FixtureEvent");
    assertReports(
        ArchitectureTest.validationLayerMustStayADependencyLeafRule(
            ArchitectureTest.VALIDATION_CODE),
        LAYERS,
        "FixtureValidator");
  }

  @Test
  void thePackageCycleRuleFails() {
    String root = Alpha.class.getPackageName().replace(".alpha", "");
    assertReports(
        ArchitectureTest.backendPackagesShouldBeFreeOfDependencyCyclesRule(root),
        new ClassFileImporter().importPackages(root),
        "Alpha");
  }

  @Test
  void theLayerCycleRuleFailsInsideAModuleAndInTheRootModule() {
    String modulesRoot = OrderService.class.getPackageName().replace(".orders.service", "");
    JavaClasses modules = new ClassFileImporter().importPackages(modulesRoot);
    assertThat(
            ArchitectureTest.layerModules(
                modules, modulesRoot, ArchitectureTest.LAYER_PACKAGE_NAMES))
        .containsExactly("billing", "orders", "shipping");
    assertReports(
        ArchitectureTest.layersInsideModuleRule(
            "orders", modulesRoot, ArchitectureTest.LAYER_PACKAGE_NAMES),
        modules,
        "OrderService");
    assertThat(
            ArchitectureTest.layersInsideModuleRule(
                    "billing", modulesRoot, ArchitectureTest.LAYER_PACKAGE_NAMES)
                .evaluate(modules)
                .hasViolation())
        .as("a cycle between two modules is not a layer cycle inside either")
        .isFalse();

    String cyclesRoot = Alpha.class.getPackageName().replace(".alpha", "");
    JavaClasses cycles = new ClassFileImporter().importPackages(cyclesRoot);
    assertReports(
        ArchitectureTest.layersInsideModuleRule("", cyclesRoot, Set.of("alpha", "beta")),
        cycles,
        "Alpha");
  }

  @Test
  void tenancyRulesFail() {
    assertReports(
        ArchitectureTest.staffelScopedServicesMustWireAScopeGuardRule(
            ArchitectureTest.classesIn(Set.of(ScopedFixtureService.class)),
            Set.of(GuardFixture.class)),
        TENANCY,
        "ScopedFixtureService");
  }

  @Test
  void missionRulesFail() {
    assertReports(
        ArchitectureTest.missionWriteRequestDtosRule(
            ArchitectureTest.classesIn(Set.of(FixtureCreateRequest.class)),
            FixtureUpdateRequest.class),
        MISSION,
        "`id`");
    assertReports(
        ArchitectureTest.missionParticipantsCollectionRule(FixtureMission.class),
        MISSION,
        "participants");
    assertReports(
        ArchitectureTest.promotionTopicOwningSquadronRule(
            FixtureTopic.class, FixtureSquadron.class),
        MISSION,
        "FixtureOrgUnit");
    assertReports(
        ArchitectureTest.addParticipantMustNotSaveRule(
            ArchitectureTest.addParticipantMethods(Set.of(FixtureParticipantService.class)),
            FixtureMissionRepository.class),
        MISSION,
        "FixtureMissionRepository#save");
    assertReports(
        ArchitectureTest.orgUnitBankSettingsMutationsRule(
            ArchitectureTest.bankSettingsMutations(
                FixtureSettingsAccess.class, FixtureSettingsDto.class)),
        MISSION,
        "setTarget");
  }

  @Test
  void theScWikiClientRuleFails() {
    assertReports(
        ArchitectureTest.scWikiIntegrationClassesMustWireTheClientRule(
            ArchitectureTest.packageTreeOf("fixture wiki", FixtureWikiClient.class),
            FixtureWikiClient.class,
            Set.of()),
        importPackageOf(FixtureWikiClient.class),
        "FixtureWikiImporter");
  }

  @Test
  void bankRulesFail() {
    String fixturesRoot = Alpha.class.getPackageName().replace(".cycles.alpha", "");
    assertReports(
        ArchitectureTest.bankClassesMustStaySeasonAndProfitIndependentRule(fixturesRoot),
        BANK,
        "MissionLedgerFixture");
    assertReports(
        ArchitectureTest.bankClassesMustNotConsultOrgUnitScopeRule(OrgScopeFixture.class),
        BANK,
        "LedgerFixtureService");
    assertReports(
        ArchitectureTest.orgUnitAwareBankSeamRule(
            ArchitectureTest.bridgesOrgUnitScopeAndTheBank(
                OrgScopeFixture.class, ArchitectureTest.BANK_DOMAIN),
            SanctionedBridge.class),
        BANK,
        "RogueBridge");
    ArchRule insertOnly =
        ArchitectureTest.bankLedgerRepositoriesMustStayInsertOnlyRule(
            ArchitectureTest.ledgerMethods(Set.of(FixtureLedgerRepository.class), Set.of()),
            Set.of(FixtureLedgerRepository.class));
    assertReports(insertOnly, BANK, "FixtureLedgerRepository.wipe()");
    assertReports(insertOnly, BANK, "LedgerWiper.wipe()");
    assertReports(
        ArchitectureTest.everyBankNamedClassIsClassifiedRule(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "fixture classes named after the bank",
                (JavaClass c) -> c.getSimpleName().contains("Bank"))),
        importPackageOf(FixtureBankThing.class),
        "FixtureBankThing");
  }

  @Test
  void aShrunkSelectionFailsItsFloor() {
    assertThatThrownBy(() -> ArchitectureTest.assertFloor("a rule", 3, 4))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("selection floor of a rule");
  }

  @Test
  void aRenamedRuleKeyFailsLoudly() {
    assertThatThrownBy(() -> ArchitectureTest.methodName(LeakyService.class, "gone"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer resolves");
    assertThatThrownBy(() -> ArchitectureTest.method(LeakyService.class, "gone"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer resolves");
  }
}
