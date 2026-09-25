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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditService;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit tests that mechanically enforce the architectural invariants from CLAUDE.md.
 *
 * <p>Each rule below corresponds to a bullet in the project guide:
 *
 * <ul>
 *   <li>"Authorization is centralized in {@code @PreAuthorize} annotations on services/controllers
 *       — keep checks out of business logic." → {@link
 *       #serviceLayerShouldNotReachIntoSecurityContext()}, {@link
 *       #controllerLayerShouldNotReachIntoSecurityContext()} and {@link
 *       #mapperLayerShouldNotReachIntoSecurityContext()} (no {@code SecurityContextHolder} outside
 *       the dedicated auth-helper services) plus {@link
 *       #everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation()}.
 *   <li>"DTOs only at boundaries. Never expose JPA entities at controller boundaries." → {@link
 *       #controllerMethodsShouldNotReturnJpaEntities()}.
 * </ul>
 *
 * <p>These rules are static checks against the imported bytecode under {@code
 * de.greluc.krt.profit.basetool.backend.*}; tests on the test classpath are excluded so the rules
 * describe the production-code contract only.
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final String SECURITY_CONTEXT_HOLDER =
      "org.springframework.security.core.context.SecurityContextHolder";

  /** The concrete authentication type that may only be named inside the authentication seam. */
  private static final String JWT_AUTHENTICATION_TOKEN =
      "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken";

  /**
   * The two classes allowed to name {@link #JWT_AUTHENTICATION_TOKEN}.
   *
   * <p>{@code AuthenticatedSubject} is the seam every other consumer asks instead; {@code
   * ActingMemberFilter} inspects the gateway's <em>own</em> token to read its {@code azp}, which is
   * a genuine token question and not an identity question.
   */
  private static final String[] AUTHENTICATION_SEAM = {
    "de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject",
    "de.greluc.krt.profit.basetool.backend.config.ActingMemberFilter"
  };

  private static final String PRE_AUTHORIZE =
      "org.springframework.security.access.prepost.PreAuthorize";

  private static final String JPA_ENTITY = "jakarta.persistence.Entity";

  private static final String TRANSACTIONAL =
      "org.springframework.transaction.annotation.Transactional";

  private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";

  /**
   * The verb-agnostic spelling, selected alongside every verb-specific one.
   *
   * <p>ArchUnit compares the annotation TYPE, not its meta-annotations, so {@code @GetMapping} and
   * {@code @RequestMapping(method = GET)} are two different things to a rule even though Spring
   * treats them alike. A read declared the second way would have shipped with no
   * {@code @PreAuthorize} at all and left the build green - on the guard REQ-SEC-052 relies on now
   * that the URL matrix names only the public surface. Selecting it here closes the spelling gap
   * the same way the permitAll rule closes the matcher gap. It is deliberately added to the write
   * rule too: the same reasoning holds for a write.
   */
  private static final String REQUEST_MAPPING =
      "org.springframework.web.bind.annotation.RequestMapping";

  private static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
  private static final String PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";
  private static final String DELETE_MAPPING =
      "org.springframework.web.bind.annotation.DeleteMapping";
  private static final String PATCH_MAPPING =
      "org.springframework.web.bind.annotation.PatchMapping";

  private static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";

  private static final String JOIN_COLUMN = "jakarta.persistence.JoinColumn";

  private static final String PROMOTION_TOPIC_FQN =
      "de.greluc.krt.profit.basetool.backend.model.PromotionTopic";

  private static final String SQUADRON_FQN = "de.greluc.krt.profit.basetool.backend.model.Squadron";

  private static final String USER_FQN = "de.greluc.krt.profit.basetool.backend.model.User";

  /**
   * Legacy entities that legitimately reference the {@code squadron_id} column today and are
   * grandfathered by {@link #noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities()}.
   * The destructive-cleanup release drops the column on both tables in a coordinated migration;
   * until then, both fields stay. New staffel-scoped aggregates MUST use the {@code
   * owning_squadron_id} (legacy mirror) or {@code owning_org_unit_id} (new column) name instead of
   * {@code squadron_id}.
   *
   * <ul>
   *   <li>{@code User.squadron} — the global user→squadron link on {@code app_user}. Migration to
   *       per-membership ownership is the destructive-cleanup release.
   * </ul>
   *
   * <p>{@code MissionParticipant} no longer references {@code squadron_id}: the per-participant
   * affiliation snapshot moved to the {@code mission_participant_org_unit} join table (FK to {@code
   * org_unit}, supporting Staffel + Spezialkommando), so the entity is no longer in this set. The
   * legacy {@code mission_participant.squadron_id} column is dropped in the destructive-cleanup
   * release.
   */
  private static final Set<String> SQUADRON_ID_COLUMN_GRANDFATHERED_FQNS = Set.of(USER_FQN);

  /**
   * DTOs that are response-only — they may be returned from {@code @GetMapping} methods or used as
   * {@code @PostMapping} return types, but MUST NOT be accepted as a {@code @RequestBody} on any
   * state-changing endpoint. They carry server-managed fields ({@code id}, {@code version}, {@code
   * owningSquadron}, {@code parent}, role-derived flags) which, if let through a write binding,
   * become a mass-assignment vector (audit finding C-3: the original {@code POST /api/v1/missions}
   * accepted the full {@code MissionDto} and let any authenticated caller overwrite a foreign
   * squadron's mission via {@code EntityManager.merge}).
   *
   * <p>Add to this list when a new response DTO ships with server-managed fields. The corresponding
   * write endpoints must then accept a dedicated {@code …Request} record from {@code dto/request/}
   * carrying only caller-controllable fields.
   */
  private static final Set<String> RESPONSE_ONLY_DTOS =
      Set.of("de.greluc.krt.profit.basetool.backend.model.dto.MissionDto");

  /**
   * Java-generic wrappers that controllers legitimately return (paging envelopes, optional results,
   * response wrappers). The Entity-Generic rule below scans the actual type arguments of these
   * wrappers to make sure a JPA {@code @Entity} never leaks through.
   */
  private static final Set<String> ENTITY_GENERIC_WRAPPERS =
      Set.of(
          "org.springframework.http.ResponseEntity",
          "org.springframework.data.domain.Page",
          "org.springframework.data.domain.Slice",
          "java.util.List",
          "java.util.Set",
          "java.util.Collection",
          "java.util.Optional",
          "java.lang.Iterable");

  /**
   * Method-name prefixes that the codebase uses for state-mutating service operations. Used by
   * {@link #mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional()} to find methods
   * that must override a class-level {@code @Transactional(readOnly = true)} with their own
   * {@code @Transactional}. The list is conservative — anything that does NOT start with one of
   * these prefixes is treated as a read operation.
   */
  private static final Set<String> MUTATING_METHOD_PREFIXES =
      Set.of(
          "create",
          "update",
          "delete",
          "add",
          "remove",
          "save",
          "store",
          "book",
          "handover",
          "link",
          "unlink",
          "move",
          "reset",
          "patch",
          "toggle",
          "complete",
          "approve",
          "reject",
          "publish",
          "cancel",
          "join",
          "leave",
          "register",
          "unregister",
          "set",
          "insert",
          "merge",
          "assign",
          "unassign",
          "increment",
          "decrement",
          "clear",
          "purge",
          "import",
          "sync");

  /**
   * Classes that are allowed to reach into {@link
   * org.springframework.security.core.context.SecurityContextHolder} despite being on the
   * service-layer package. By design the list contains exactly one entry — {@code
   * AuthHelperService} — so there is a single source of truth for "what is the current
   * authentication" across the codebase.
   */
  private static final java.util.Set<String> SECURITY_CONTEXT_HOLDER_EXCEPTIONS =
      java.util.Set.of("de.greluc.krt.profit.basetool.backend.service.AuthHelperService");

  @Test
  void serviceLayerShouldNotReachIntoSecurityContext() {
    noClasses()
        .that()
        .resideInAPackage("..backend.service..")
        .and()
        .haveNameNotMatching(allowedClassNamesRegex())
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(SECURITY_CONTEXT_HOLDER)
        .because(
            "Business services must not pull the authenticated principal directly; "
                + "use a controller-side @PreAuthorize check or inject AuthHelperService instead. "
                + "The allow-list lives at the top of this test file.")
        .check(CLASSES);
  }

  @Test
  void controllerLayerShouldNotReachIntoSecurityContext() {
    noClasses()
        .that()
        .resideInAPackage("..backend.controller..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(SECURITY_CONTEXT_HOLDER)
        .because(
            "Controllers must read the principal via @AuthenticationPrincipal / "
                + "Authentication parameters, or delegate to AuthHelperService — direct "
                + "SecurityContextHolder access splits the auth contract across the codebase.")
        .check(CLASSES);
  }

  @Test
  void identityMustBeReadThroughTheSeamNotTheAuthenticationType() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(AUTHENTICATION_SEAM[0])
        .and()
        .doNotHaveFullyQualifiedName(AUTHENTICATION_SEAM[1])
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(JWT_AUTHENTICATION_TOKEN)
        .because(
            "asking for the authentication TYPE instead of the subject splits every consumer into "
                + "fail-closed and fail-open the moment a second authentication type exists "
                + "(ADR-0129): the acting member carries no token, so an instanceof test skipped "
                + "the consent gate and 403'd the argument resolver. Read the subject via "
                + "support.AuthenticatedSubject; only the authentication seam may name the token.")
        .check(CLASSES);
  }

  @Test
  void mapperLayerShouldNotReachIntoSecurityContext() {
    noClasses()
        .that()
        .resideInAPackage("..backend.mapper..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(SECURITY_CONTEXT_HOLDER)
        .because(
            "Mappers must stay pure transformers; route any auth lookup through a dependency-leaf "
                + "SPI (e.g. support.MissionViewerAccess) so the mapper depends on neither the "
                + "request-scoped SecurityContextHolder nor the service layer.")
        .check(CLASSES);
  }

  @Test
  void controllerMethodsShouldNotReturnJpaEntities() {
    noMethods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .should()
        .haveRawReturnType(annotatedWith(JPA_ENTITY))
        .because(
            "Controllers must return DTOs (or Page<Dto>/ResponseEntity<Dto>), never raw JPA"
                + " entities.")
        .check(CLASSES);
  }

  @Test
  void toOneAssociationsAreDeclaredLazy() {
    fields()
        .that()
        .areAnnotatedWith(ManyToOne.class)
        .or()
        .areAnnotatedWith(OneToOne.class)
        .should(declareFetchTypeLazy())
        .because(
            "to-one associations are LAZY by project rule (BE-PERF-11); fetch what a read needs"
                + " with an @EntityGraph or JOIN FETCH instead of making every load eager")
        .check(CLASSES);
  }

  @Test
  void controllersMustNotInjectTheLazyMembershipMapper() {
    noClasses()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            "de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper")
        .because(
            "Membership DTO projection happens inside OrgUnitMembershipService (ADR-0067) — a"
                + " controller-side mapping outside a transaction throws"
                + " LazyInitializationException on the LAZY user association once the service"
                + " transaction has committed (the write succeeds but the response 500s). Use the"
                + " service's …Dto projection methods instead.")
        .check(CLASSES);
  }

  @Test
  void everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation() {
    classes()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should(haveAtLeastOnePreAuthorizeAnnotation())
        .because(
            "Every REST controller class must declare at least one @PreAuthorize annotation (either"
                + " on the class or on any method) so it cannot silently bypass authorisation."
                + " Public endpoints must use @PreAuthorize(\"permitAll()\") and are limited to"
                + " the four REQ-SEC-052 names.")
        .check(CLASSES);
  }

  /**
   * The four methods that may declare {@code @PreAuthorize("permitAll()")} — and no others.
   *
   * <p>REQ-SEC-052 states the public surface as a list, and a list is only a requirement if
   * something refuses to grow it. Two are the anonymous reads (D2 / D3 of ADR-0159): an app too old
   * to log in must still learn that it is too old, and a document everyone must be able to read
   * before agreeing to anything cannot require having agreed. The third is the Keycloak SPI's
   * account-existence precheck, which is machine-to-machine behind a constant-time shared-secret
   * header — not an anonymous data path, and it carries no JWT because Keycloak sits outside the
   * resource server's trust boundary.
   *
   * <p>The fourth is {@code /error}, the fourth REQ-SEC-052 path and Spring's own dispatch. It
   * gained the annotation on 2026-09-07, when the read/write rules learned to select
   * {@code @RequestMapping} alongside the verb-specific spellings and found the one endpoint in the
   * codebase declared that way — a read whose publicness had only ever been stated in the URL
   * matrix. It carries no data of its own: only the status the failed request already produced.
   */
  private static final Set<String> PERMIT_ALL_ALLOWED_METHODS =
      Set.of(
          "de.greluc.krt.profit.basetool.backend.controller.AppVersionPolicyController"
              + ".versionPolicy()",
          "de.greluc.krt.profit.basetool.backend.controller.TermsDocumentController.document"
              + "(java.util.Locale)",
          "de.greluc.krt.profit.basetool.backend.controller.DiscordAccountExistenceController",
          "de.greluc.krt.profit.basetool.backend.controller.BasetoolErrorController.handleError"
              + "(jakarta.servlet.http.HttpServletRequest)");

  @Test
  void permitAllIsDeclaredOnlyOnTheFourPublicEndpoints() {
    List<String> offenders = new java.util.ArrayList<>();
    for (JavaClass clazz : CLASSES) {
      if (!clazz.getPackageName().contains(".backend.controller")) {
        continue;
      }
      for (JavaMethod method : clazz.getMethods()) {
        if (!method.isAnnotatedWith(PRE_AUTHORIZE)) {
          continue;
        }
        String value =
            method
                .getAnnotationOfType(PRE_AUTHORIZE)
                .tryGetExplicitlyDeclaredProperty("value")
                .map(Object::toString)
                .orElse("");
        if (!value.contains("permitAll")) {
          continue;
        }
        boolean allowed =
            PERMIT_ALL_ALLOWED_METHODS.stream()
                .anyMatch(
                    allowedName ->
                        method.getFullName().startsWith(allowedName)
                            || clazz.getFullName().equals(allowedName));
        if (!allowed) {
          offenders.add(method.getFullName());
        }
      }
    }
    org.assertj.core.api.Assertions.assertThat(offenders)
        .as(
            "REQ-SEC-052: only the two anonymous reads, the Keycloak SPI precheck and Spring's"
                + " own /error dispatch may declare permitAll() -- the four entries of"
                + " PERMIT_ALL_ALLOWED_METHODS. A new one is a widening of the public surface and"
                + " needs the requirement amended first.")
        .isEmpty();
  }

  /**
   * Every read endpoint carries an authorisation decision of its own, class-level or method-level.
   *
   * <p>The write sibling of this rule has existed since the 2026-05-20 audit. Reads were left out
   * because the URL matrix answered for them — and that is exactly what REQ-SEC-052 removed. Before
   * ADR-0159 twelve {@code InventoryItemController} reads, both {@code MaterialCategoryController}
   * reads, {@code AnnouncementController}, {@code TerminalController} and the two {@code
   * HangarController} reads carried no gate at all; they were safe only because a matcher two
   * folders away said {@code authenticated()}. A read is where data leaves, so it gets the same
   * treatment as a write.
   */
  @Test
  void readEndpointsMustDeclareAnAuthorisationAnnotation() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(isAnnotatedWithAnyOf(GET_MAPPING, REQUEST_MAPPING))
        .should(haveMethodOrClassLevelPreAuthorize())
        .because(
            "Every read endpoint must carry an explicit @PreAuthorize (method- or class-level)."
                + " REQ-SEC-052 leaves the URL matrix naming only the public surface, so a read"
                + " without a gate of its own is protected by nothing that lives next to it.")
        .check(CLASSES);
  }

  @Test
  void orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .haveSimpleName("OrgUnitBankAccessService")
        .and()
        .arePublic()
        .and()
        .haveNameMatching("(set|add|remove|clear).*")
        .and()
        .haveRawReturnType(
            "de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto")
        .should(callARequireCanAuthorizationHelper())
        .because(
            "Every org-unit bank settings mutation must authorize via a requireCan* helper; a"
                + " dropped check would be reachable by any authenticated member (the controller"
                + " and proxy only require isAuthenticated()).")
        .check(CLASSES);
  }

  @Test
  void controllerLayerShouldNotDependOnRepositoryLayer() {
    noClasses()
        .that()
        .resideInAPackage("..backend.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..backend.repository..")
        .because(
            "Controllers must go through the service layer — a controller depending on a "
                + "repository bypasses @Transactional boundaries, owner filtering and the "
                + "@PreAuthorize seam, all of which live in services.")
        .check(CLASSES);
  }

  @Test
  void controllerLayerMustNotWriteAuditRowsDirectly() {
    noClasses()
        .that()
        .resideInAPackage("..backend.controller..")
        .should()
        .callMethodWhere(callsAuditRecord())
        .because(
            "record(...) needs a surrounding transaction (MANDATORY) that a controller cannot "
                + "provide. Put the call in the service that owns the operation — inside the "
                + "business transaction for a mutation, or in a small @Transactional method of its "
                + "own for an audited read (see DataExportService#recordExport / "
                + "PersonSearchService#recordSearch).")
        .check(CLASSES);
  }

  /**
   * Matches a call to {@code record(..)} on either audit service, whatever its signature.
   *
   * @return the predicate, matched on owner and method name so a future parameter change cannot
   *     silently disarm the rule
   */
  private static DescribedPredicate<JavaCall<?>> callsAuditRecord() {
    Set<String> owners = Set.of(AuditService.class.getName(), BankAuditService.class.getName());
    return new DescribedPredicate<>("a call to AuditService/BankAuditService.record(..)") {
      @Override
      public boolean test(JavaCall<?> call) {
        return owners.contains(call.getTargetOwner().getFullName())
            && "record".equals(call.getTarget().getName());
      }
    };
  }

  @Test
  void supportPackageMustStayADependencyLeaf() {
    noClasses()
        .that()
        .resideInAPackage("..backend.support..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..backend.controller..",
            "..backend.service..",
            "..backend.mapper..",
            "..backend.config..",
            "..backend.integration..",
            "..backend.task..",
            "..backend.filter..",
            "..backend.interceptor..",
            "..backend.web..",
            "..backend.event..",
            "..backend.health..")
        .because(
            "The `support` package must stay a dependency leaf (depending only on model + "
                + "repository) so the helpers it holds can be shared by both the mapper and the "
                + "service layer without forming a package cycle.")
        .check(CLASSES);
  }

  @Test
  void backendPackagesShouldBeFreeOfDependencyCycles() {
    slices()
        .matching("de.greluc.krt.profit.basetool.backend.(*)..")
        .should()
        .beFreeOfCycles()
        .because(
            "backend packages must form an acyclic dependency graph (ADR-0047); put shared"
                + " dependency-free helpers in the `support` leaf or invert the dependency through"
                + " a leaf interface instead of closing a package cycle.")
        .check(CLASSES);
  }

  @Test
  void mapperLayerShouldNotDependOnServiceLayer() {
    noClasses()
        .that()
        .resideInAPackage("..backend.mapper..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..backend.service..")
        .because(
            "the service layer depends on mappers, so a mapper -> service edge re-creates the "
                + "mapper <-> service package cycle (ADR-0047). Put shared logic in the `support` "
                + "leaf or invert it through a leaf SPI.")
        .check(CLASSES);
  }

  @Test
  void integrationLayerShouldNotDependOnServiceLayer() {
    noClasses()
        .that()
        .resideInAPackage("..backend.integration..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..backend.service..")
        .because(
            "the service layer orchestrates the integration clients, so an integration -> service "
                + "edge re-creates the integration <-> service package cycle (ADR-0047). "
                + "Orchestrators that call services belong in service.scwiki, not integration.")
        .check(CLASSES);
  }

  @Test
  void eventLayerShouldNotDependOnServiceLayer() {
    noClasses()
        .that()
        .resideInAPackage("..backend.event..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..backend.service..")
        .because(
            "event payloads are data-only, so an event -> service edge re-creates the "
                + "event <-> service package cycle (ADR-0047). Event listeners/producers belong in "
                + "the service layer.")
        .check(CLASSES);
  }

  @Test
  void validationLayerMustStayADependencyLeaf() {
    noClasses()
        .that()
        .resideInAPackage("..backend.validation..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..backend.model..", "..backend.repository..", "..backend.service..")
        .because(
            "model.dto references the constraint annotations, so validation must stay a leaf; a"
                + " validation -> model/repository/service edge re-creates the model <-> validation"
                + " package cycle (ADR-0047). Invert through a leaf SPI like"
                + " MaterialPieceTypeLookup.")
        .check(CLASSES);
  }

  @Test
  void controllerMethodsShouldNotExposeJpaEntitiesInGenericWrappers() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .should(notReturnAnEntityInsideAGenericWrapper())
        .because(
            "Controllers must wrap DTOs, never JPA entities, even when the entity is "
                + "tucked inside ResponseEntity<…>/Page<…>/List<…>/Optional<…>/etc.")
        .check(CLASSES);
  }

  @Test
  void mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional() {
    classes()
        .that()
        .resideInAPackage("..backend.service..")
        .should(declareTransactionalForMutatingMethodsWhenClassIsReadOnly())
        .because(
            "A class-level @Transactional(readOnly = true) silently propagates to every "
                + "method — mutating operations must explicitly override it with their own "
                + "@Transactional, otherwise the write happens in a read-only transaction.")
        .check(CLASSES);
  }

  @Test
  void repositoriesMustNotDeclareNoArgFindAll() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.repository..")
        .and()
        .haveName("findAll")
        .and()
        .haveRawParameterTypes(new Class<?>[0])
        .should(failArchitectureCheckBecauseM9())
        .because(
            "Repositories must not override no-arg findAll() (M-9 from the performance "
                + "audit). Use findAll(Pageable) or a scoped query method instead.")
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  private static ArchCondition<JavaMethod> failArchitectureCheckBecauseM9() {
    return new ArchCondition<>("not exist (no-arg findAll() override violates M-9)") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — no-arg findAll() in a repository is the M-9 anti-pattern. "
                    + "Switch to findAll(Pageable) or a scoped query method."));
      }
    };
  }

  @Test
  void writeEndpointsMustDeclareAnAuthorisationAnnotation() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(
            isAnnotatedWithAnyOf(
                POST_MAPPING, PUT_MAPPING, DELETE_MAPPING, PATCH_MAPPING, REQUEST_MAPPING))
        .should(haveMethodOrClassLevelPreAuthorize())
        .because(
            "Every state-changing HTTP endpoint must carry an explicit @PreAuthorize "
                + "(either method-level or class-level). For deliberately public endpoints "
                + "use @PreAuthorize(\"permitAll()\") so the auth contract stays visible "
                + "next to the handler instead of buried in SecurityConfig.")
        .check(CLASSES);
  }

  private static String allowedClassNamesRegex() {
    return SECURITY_CONTEXT_HOLDER_EXCEPTIONS.stream()
        .map(java.util.regex.Pattern::quote)
        .reduce((a, b) -> a + "|" + b)
        .orElseThrow();
  }

  /**
   * The condition behind {@link #toOneAssociationsAreDeclaredLazy()}: the field's {@code ManyToOne}
   * or {@code OneToOne} annotation states {@code fetch = FetchType.LAZY}.
   *
   * @return the condition
   */
  private static ArchCondition<JavaField> declareFetchTypeLazy() {
    return new ArchCondition<>("declare fetch = FetchType.LAZY") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        FetchType fetch =
            field.isAnnotatedWith(ManyToOne.class)
                ? field.getAnnotationOfType(ManyToOne.class).fetch()
                : field.getAnnotationOfType(OneToOne.class).fetch();
        if (fetch != FetchType.LAZY) {
          events.add(
              SimpleConditionEvent.violated(
                  field, field.getFullName() + " is fetched " + fetch + ", not LAZY"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> haveAtLeastOnePreAuthorizeAnnotation() {
    return new ArchCondition<JavaClass>(
        "declare @PreAuthorize on the class or on at least one method") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        if (clazz.isAnnotatedWith(PRE_AUTHORIZE)) {
          return;
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (method.isAnnotatedWith(PRE_AUTHORIZE)) {
            return;
          }
        }
        events.add(
            SimpleConditionEvent.violated(
                clazz,
                clazz.getFullName()
                    + " is a @RestController but declares no @PreAuthorize "
                    + "annotation on the class or on any of its methods"));
      }
    };
  }

  private static DescribedPredicate<JavaClass> annotatedWith(String annotationFqn) {
    return new DescribedPredicate<JavaClass>("annotated with @" + annotationFqn) {
      @Override
      public boolean test(JavaClass clazz) {
        return clazz.isAnnotatedWith(annotationFqn);
      }
    };
  }

  private static DescribedPredicate<JavaMethod> isAnnotatedWithAnyOf(String... annotationFqns) {
    String description = "annotated with any of " + String.join(", ", annotationFqns);
    return new DescribedPredicate<JavaMethod>(description) {
      @Override
      public boolean test(JavaMethod method) {
        for (String fqn : annotationFqns) {
          if (method.isAnnotatedWith(fqn)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  private static ArchCondition<JavaMethod> notReturnAnEntityInsideAGenericWrapper() {
    return new ArchCondition<JavaMethod>("not return a JPA entity inside a known generic wrapper") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        JavaType returnType = method.getReturnType();
        if (!(returnType instanceof JavaParameterizedType parameterized)) {
          return;
        }
        JavaClass rawType = parameterized.toErasure();
        if (!ENTITY_GENERIC_WRAPPERS.contains(rawType.getFullName())) {
          return;
        }
        for (JavaType arg : parameterized.getActualTypeArguments()) {
          JavaClass argClass = arg.toErasure();
          if (argClass.isAnnotatedWith(JPA_ENTITY)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    method.getFullName()
                        + " returns "
                        + rawType.getSimpleName()
                        + "<"
                        + argClass.getSimpleName()
                        + "> — JPA entities must not "
                        + "be exposed through generic wrappers; map to a DTO first."));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass>
      declareTransactionalForMutatingMethodsWhenClassIsReadOnly() {
    return new ArchCondition<JavaClass>(
        "declare method-level @Transactional on mutating methods when the class is"
            + " @Transactional(readOnly = true)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        if (!isClassReadOnlyTransactional(clazz)) {
          return;
        }
        for (JavaMethod method : clazz.getMethods()) {
          if (!method
              .getModifiers()
              .contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
            continue;
          }
          if (!hasMutatingNamePrefix(method.getName())) {
            continue;
          }
          if (method.isAnnotatedWith(TRANSACTIONAL)) {
            continue;
          }
          events.add(
              SimpleConditionEvent.violated(
                  method,
                  method.getFullName()
                      + " — declaring class is @Transactional(readOnly = true) "
                      + "but this mutating method has no @Transactional override; writes "
                      + "would happen in a read-only transaction. Annotate the method with "
                      + "@Transactional or rename it to a non-mutating prefix."));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> haveMethodOrClassLevelPreAuthorize() {
    return new ArchCondition<JavaMethod>(
        "declare @PreAuthorize on the method or on the declaring class") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        if (method.isAnnotatedWith(PRE_AUTHORIZE)) {
          return;
        }
        if (method.getOwner().isAnnotatedWith(PRE_AUTHORIZE)) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — state-changing endpoint without @PreAuthorize. "
                    + "Add @PreAuthorize on the method (or class-level) — use "
                    + "@PreAuthorize(\"permitAll()\") if the endpoint is deliberately public."));
      }
    };
  }

  private static boolean isClassReadOnlyTransactional(JavaClass clazz) {
    if (!clazz.isAnnotatedWith(TRANSACTIONAL)) {
      return false;
    }
    JavaAnnotation<?> annotation = clazz.getAnnotationOfType(TRANSACTIONAL);
    return annotation
        .tryGetExplicitlyDeclaredProperty("readOnly")
        .map(value -> Boolean.TRUE.equals(value))
        .orElse(false);
  }

  private static boolean hasMutatingNamePrefix(String methodName) {
    String lower = methodName.toLowerCase(java.util.Locale.ROOT);
    for (String prefix : MUTATING_METHOD_PREFIXES) {
      if (lower.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} iff one of the method's request-mapping annotations declares a path that contains
   * the literal {@code "{id}"} placeholder. Used by {@link
   * #staffelScopedWriteEndpointsMustGateOnOwnerScopeService()} to scope the rule to endpoints that
   * target a primary-resource aggregate id (and skip create / bulk / cross-user-administrative
   * endpoints whose only {@code UUID} path variable is a related entity like {@code userId}).
   */
  private static boolean mappingPathContainsIdPlaceholder(JavaMethod method) {
    String[] candidateAnnotations = {POST_MAPPING, PUT_MAPPING, PATCH_MAPPING, DELETE_MAPPING};
    for (String fqn : candidateAnnotations) {
      if (!method.isAnnotatedWith(fqn)) {
        continue;
      }
      JavaAnnotation<?> ann = method.getAnnotationOfType(fqn);
      Object raw = ann.tryGetExplicitlyDeclaredProperty("value").orElse(null);
      if (raw == null) {
        continue;
      }
      if (raw instanceof String s) {
        if (s.contains("{id}")) {
          return true;
        }
      } else if (raw instanceof Object[] arr) {
        for (Object o : arr) {
          if (o instanceof String s && s.contains("{id}")) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Staffel-scoped aggregate services MUST consult either {@code AuthHelperService} (for raw
   * principal / role lookups) or {@code OwnerScopeService} (for canSee/canEdit + active-context
   * resolution) - otherwise the data they emit might leak across org units. Phase 3 of
   * MULTI_SQUADRON_PLAN.md tracks this as a defensive ArchUnit guard against future drift, and
   * SPEZIALKOMMANDO_PLAN.md §5.3 carried the rule forward from the now-deleted {@code
   * SquadronScopeService} shim to its successor in R2.c.
   *
   * <p>{@code JobOrderService} is included as of Phase 3 (#343): Job Orders are no longer an
   * unconditional cross-staffel workspace but a <em>conditionally</em> staffel-scoped aggregate
   * (SK-responsible = public, squadron-responsible = private to that squadron + admins), so the
   * service now wires {@code OwnerScopeService} to resolve the visibility scope. {@code
   * JobOrderHandoverService} stays excluded — handover writes inherit their access gate from the
   * parent order's {@code @ownerScopeService.canEditJobOrder} controller check, and the service
   * itself only injects {@code AuthHelperService} for the audit stamp on the handover record.
   *
   * <p>{@code InventoryAggregationService} and {@code InventoryCheckoutService} are included as of
   * the L2 split (#921): the org-unit scoping the {@code InventoryItemService} facade used to carry
   * ({@code OwnerScopeService.currentScopePredicate()}) moved wholesale into these two extracted
   * services, so the guard follows the scoped data rather than staying pinned to the now-thin
   * facade.
   */
  @Test
  void staffelScopedServicesMustWireOwnerScopeOrAuthHelper() {
    Set<String> staffelScopedServiceNames =
        Set.of(
            "MissionService",
            "InventoryItemService",
            "InventoryAggregationService",
            "InventoryCheckoutService",
            "RefineryOrderService",
            "HangarService",
            "OperationService",
            "JobOrderService",
            "JobOrderQueryService",
            "MaterialClaimService",
            "PersonalBlueprintOverviewService",
            "OrgUnitBankAccessService");

    String authHelper = "de.greluc.krt.profit.basetool.backend.service.AuthHelperService";
    String ownerScope = "de.greluc.krt.profit.basetool.backend.service.OwnerScopeService";

    classes()
        .that(
            new DescribedPredicate<JavaClass>("is one of the staffel-scoped aggregate services") {
              @Override
              public boolean test(JavaClass javaClass) {
                return staffelScopedServiceNames.contains(javaClass.getSimpleName());
              }
            })
        .should(
            new ArchCondition<>("depend on AuthHelperService or OwnerScopeService") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                boolean hasIt =
                    javaClass.getFields().stream()
                        .map(f -> f.getRawType().getFullName())
                        .anyMatch(t -> t.equals(authHelper) || t.equals(ownerScope));
                if (!hasIt) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " is in the staffel-scoped service whitelist but injects neither"
                              + " AuthHelperService nor OwnerScopeService - that means it"
                              + " cannot enforce the multi-tenant filter / org-unit stamp."));
                }
              }
            })
        .check(CLASSES);
  }

  /**
   * Plan-compliant ArchUnit guard #3 (MULTI_SQUADRON_PLAN.md section 4.6 + SPEZIALKOMMANDO_PLAN.md
   * §5.3): write endpoints on staffel-scoped aggregates MUST use a {@code @PreAuthorize} expression
   * that calls into the {@code OwnerScopeService} (canEdit* / canSee*). A bare
   * {@code @PreAuthorize("isAuthenticated()")} on POST / PUT / PATCH / DELETE for {@code
   * /api/v1/missions}, {@code /api/v1/operations}, {@code /api/v1/hangar}, {@code
   * /api/v1/inventory} or {@code /api/v1/refinery-orders} would silently allow cross-staffel writes
   * — exactly the regression class this rule prevents.
   *
   * <p>The rule inspects all write methods (POST/PUT/PATCH/DELETE) on the affected controllers but
   * only fires when the URL path carries a primary-resource id placeholder (i.e. {@code /{id}}).
   * POSTs that do not target a specific resource (top-level create, bulk operations,
   * cross-user-administrative endpoints like {@code /users/{userId}/...}) are skipped — the service
   * layer enforces ownership there and a per-id org-unit gate has nothing to bind to.
   *
   * <ul>
   *   <li>Read endpoints stay free of the rule — list endpoints lean on service-layer filtering
   *       rather than per-row {@code @PreAuthorize}.
   *   <li>{@code /api/v1/orders} (job orders) and {@code /api/v1/admin/**} are excluded — job
   *       orders are a cross-staffel workspace by design, admin endpoints already require {@code
   *       hasRole('ADMIN')} which carries no squadron component.
   *   <li>Endpoints that use a role-only check ({@code hasRole('LOGISTICIAN')} etc.) without
   *       additionally calling the owner-scope service still violate — the rule looks for the
   *       literal {@code ownerScopeService} reference in the SpEL expression.
   * </ul>
   */
  @Test
  void staffelScopedWriteEndpointsMustGateOnOwnerScopeService() {
    Set<String> staffelScopedControllerSimpleNames =
        Set.of(
            "MissionController",
            "OperationController",
            "HangarController",
            "InventoryItemController",
            "RefineryOrderController",
            "SpecialCommandController",
            "SpecialCommandMembershipController");

    noMethods()
        .that()
        .areDeclaredInClassesThat(
            new DescribedPredicate<JavaClass>("are staffel-scoped aggregate REST controllers") {
              @Override
              public boolean test(JavaClass javaClass) {
                return staffelScopedControllerSimpleNames.contains(javaClass.getSimpleName());
              }
            })
        .and()
        .areAnnotatedWith(
            new DescribedPredicate<JavaAnnotation<?>>(
                "are a modify-mapping annotation (POST/PUT/PATCH/DELETE)") {
              @Override
              public boolean test(JavaAnnotation<?> annotation) {
                String fqcn = annotation.getRawType().getFullName();
                return POST_MAPPING.equals(fqcn)
                    || PUT_MAPPING.equals(fqcn)
                    || PATCH_MAPPING.equals(fqcn)
                    || DELETE_MAPPING.equals(fqcn);
              }
            })
        .and()
        .areAnnotatedWith(PRE_AUTHORIZE)
        .should(
            new ArchCondition<JavaMethod>(
                "gate on @ownerScopeService in the @PreAuthorize SpEL expression") {
              @Override
              public void check(JavaMethod method, ConditionEvents events) {
                boolean takesResourceIdPathVariable =
                    method.getParameters().stream()
                        .anyMatch(
                            p ->
                                p.isAnnotatedWith(
                                        "org.springframework.web.bind.annotation.PathVariable")
                                    && p.getRawType().getFullName().equals("java.util.UUID"));
                if (!takesResourceIdPathVariable) {
                  return;
                }
                if (!mappingPathContainsIdPlaceholder(method)) {
                  return;
                }

                JavaAnnotation<?> ann = method.getAnnotationOfType(PRE_AUTHORIZE);
                String value =
                    ann.tryGetExplicitlyDeclaredProperty("value").map(Object::toString).orElse("");
                boolean hasOwnerScope = value.contains("ownerScopeService");
                boolean hasMissionSecurity = value.contains("missionSecurityService");
                boolean hasSpecialCommandSecurity = value.contains("specialCommandSecurityService");
                boolean hasOrgRoleManagement = value.contains("orgRoleManagementSecurityService");
                boolean hasAdminOnly =
                    value.contains("hasRole('ADMIN')") && !value.contains("hasAnyRole(");
                if (!hasOwnerScope
                    && !hasMissionSecurity
                    && !hasSpecialCommandSecurity
                    && !hasOrgRoleManagement
                    && !hasAdminOnly) {
                  events.add(
                      SimpleConditionEvent.violated(
                          method,
                          method.getFullName()
                              + " is a write endpoint on a staffel-scoped aggregate but its"
                              + " @PreAuthorize expression does not gate on @ownerScopeService"
                              + " (or @missionSecurityService / hasRole('ADMIN')) - that means"
                              + " cross-staffel writes are not blocked. Add `and"
                              + " @ownerScopeService.canEdit*(#id)` to the SpEL"
                              + " (SPEZIALKOMMANDO_PLAN.md §5.3)."));
                }
              }
            })
        .check(CLASSES);
  }

  /**
   * Audit finding C-1 guard, re-keyed by ADR-0159: a mission endpoint whose gate admits a member
   * <b>below Logistician</b> and which returns a PII-carrying mission DTO MUST call one of the
   * {@code cleanup…ForPeer} helpers, or it ships participant e-mail and real name to a peer
   * (REQ-SEC-007).
   *
   * <p><b>What changed, and why the rule had to be rewritten rather than renamed.</b> It used to
   * select endpoints whose {@code @PreAuthorize} carried <em>no</em> {@code isAuthenticated()} /
   * {@code hasRole(...)} clause — the shape that made an endpoint anonymously reachable under the
   * old {@code permitAll} matrix. Every one of those gates now carries {@code isAuthenticated()},
   * so the old predicate would select <b>nothing</b> and the rule would pass by matching no
   * members: a guard that is green because it checks an empty set is worse than no guard, which is
   * why the non-empty assertion below is part of the test rather than a nicety.
   *
   * <p>The replacement keys on <em>which</em> gate rather than on the absence of one. {@code
   * canSeeMission} and {@code canAccessParticipant} both admit an ordinary member; {@code
   * canManageMission}, {@code canManageManagers}, {@code canChangeOwner} and any {@code hasRole}
   * gate do not, and those endpoints legitimately return the unredacted aggregate to the
   * leadership.
   *
   * <p>The check is structural: it asserts the helper is referenced in the bytecode, NOT that the
   * call is conditional. The conditional branching is verified by the per-endpoint unit tests. The
   * intent is to catch the "I forgot the redaction entirely" regression, which was the actual C-1
   * root cause in {@code addParticipantPublic} / {@code addParticipantSlim}.
   */
  @Test
  void peerReadableMissionEndpointsMustRedactPii() {
    long selected =
        CLASSES.stream()
            .filter(c -> c.getPackageName().contains(".backend.controller"))
            .flatMap(c -> c.getMethods().stream())
            .filter(
                m ->
                    m.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC))
            .filter(m -> hasPeerReachableMissionGate().test(m))
            .filter(m -> returnsMissionDtoOrMissionParticipantDtoOrCollection().test(m))
            .count();
    org.assertj.core.api.Assertions.assertThat(selected)
        .as(
            "peer-reachable mission endpoints returning a PII-carrying DTO — if this is 0 the rule"
                + " below checks nothing and passes for the wrong reason")
        .isGreaterThanOrEqualTo(10);

    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(hasPeerReachableMissionGate())
        .and(returnsMissionDtoOrMissionParticipantDtoOrCollection())
        .should(callOneOfTheGuestRedactionHelpers())
        .because(
            "Mission endpoints reachable by a member below Logistician must apply "
                + "cleanupMissionForPeer or cleanupParticipantForPeer before returning "
                + "(REQ-SEC-007) — audit finding C-1: addParticipantPublic / addParticipantSlim "
                + "previously leaked full participant emails and real names because the redaction "
                + "pass that getMissionById / getNextMission already used was skipped on the "
                + "write paths.")
        .check(CLASSES);
  }

  /**
   * Mission DTOs whose participant nesting carries PII (email, first/last name, roles). Used by
   * {@link #peerReadableMissionEndpointsMustRedactPii} to recognise return shapes that must go
   * through guest-redaction before reaching an anonymous caller. {@code MissionFinanceEntryDto} is
   * included because it embeds {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto} directly — the audit
   * found this transitive leak (C-2) in {@code MissionFinanceEntryController.createFinanceEntry}.
   */
  private static final Set<String> MISSION_PII_CARRYING_DTOS =
      Set.of(
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto");

  /**
   * Naming convention for helper methods that strip participant PII for a peer: {@code
   * cleanup<EntityName>ForPeer}. The helpers live in {@code MissionPeerRedactor} and are called by
   * the controllers: {@code cleanupMissionForPeer}, {@code cleanupParticipantForPeer}, {@code
   * cleanupUserForPeer}, {@code cleanupUnitForPeer}, {@code cleanupShipForPeer}. The rule
   * recognises any call to a method matching this pattern as a valid redaction call — so adding a
   * new peer-reachable controller with its own entity-specific redactor (named accordingly) does
   * not require updating this test.
   *
   * @param name candidate method name
   * @return {@code true} iff {@code name} matches the {@code cleanup…ForPeer} convention
   */
  private static boolean isGuestRedactionHelperName(String name) {
    return name.startsWith("cleanup") && name.endsWith("ForPeer");
  }

  private static DescribedPredicate<JavaMethod> hasPeerReachableMissionGate() {
    return new DescribedPredicate<JavaMethod>(
        "annotated with @PreAuthorize whose gate admits a member below Logistician") {
      @Override
      public boolean test(JavaMethod method) {
        if (!method.isAnnotatedWith(PRE_AUTHORIZE)) {
          return false;
        }
        JavaAnnotation<?> ann = method.getAnnotationOfType(PRE_AUTHORIZE);
        String value =
            ann.tryGetExplicitlyDeclaredProperty("value").map(Object::toString).orElse("");
        return !value.contains("hasRole(")
            && !value.contains("hasAnyRole(")
            && !value.contains("hasAuthority(")
            && !value.contains("hasAnyAuthority(");
      }
    };
  }

  /**
   * The PII-carrying mission DTOs a method's return type exposes.
   *
   * <p>The raw type when it is one of {@link #MISSION_PII_CARRYING_DTOS}, otherwise the matching
   * type arguments of a known generic wrapper. Returned as a set rather than a boolean because the
   * redaction rule needs to compare a handler's protected type against its helper's: a hop that
   * only proves "some redaction happened somewhere" proves nothing about the object being returned.
   *
   * @param method the method whose return type to inspect
   * @return the matching entries of {@link #MISSION_PII_CARRYING_DTOS}; empty when the return type
   *     carries no participant PII
   */
  private static Set<String> protectedDtosOf(JavaMethod method) {
    JavaClass rawReturnType = method.getRawReturnType();
    if (MISSION_PII_CARRYING_DTOS.contains(rawReturnType.getFullName())) {
      return Set.of(rawReturnType.getFullName());
    }
    JavaType returnType = method.getReturnType();
    if (!(returnType instanceof JavaParameterizedType parameterized)) {
      return Set.of();
    }
    if (!ENTITY_GENERIC_WRAPPERS.contains(parameterized.toErasure().getFullName())) {
      return Set.of();
    }
    Set<String> exposed = new java.util.LinkedHashSet<>();
    for (JavaType arg : parameterized.getActualTypeArguments()) {
      String name = arg.toErasure().getFullName();
      if (MISSION_PII_CARRYING_DTOS.contains(name)) {
        exposed.add(name);
      }
    }
    return exposed;
  }

  private static DescribedPredicate<JavaMethod>
      returnsMissionDtoOrMissionParticipantDtoOrCollection() {
    return new DescribedPredicate<JavaMethod>(
        "returns MissionDto / MissionParticipantDto, or a known generic wrapper of either") {
      @Override
      public boolean test(JavaMethod method) {
        return !protectedDtosOf(method).isEmpty();
      }
    };
  }

  /**
   * Audit finding C-3 guard (2026-05-20 security audit): write endpoints on REST controllers must
   * not accept a response-only DTO as {@code @RequestBody}. Response DTOs carry server-managed
   * fields ({@code id}, {@code version}, {@code owningSquadron}, …) which, if let through a JSON
   * binding into a fresh entity, become a mass-assignment vector — the original {@code POST
   * /api/v1/missions} accepted a full {@code MissionDto} and let any authenticated caller overwrite
   * a foreign squadron's mission row via {@code EntityManager.merge}. The fix migrated those
   * endpoints to dedicated {@code CreateMissionRequest} / {@code UpdateMissionRequest} records that
   * physically lack the dangerous fields.
   *
   * <p>This rule keeps the migration one-way: any future {@code @PostMapping} / {@code @PutMapping}
   * / {@code @PatchMapping} that tries to take a listed response-only DTO as its request body fails
   * the build. The {@code RESPONSE_ONLY_DTOS} allowlist at the top of this test file is the
   * explicit registry — extend it when a new response DTO ships with server-managed fields (every
   * staffel-scoped aggregate's main DTO is a candidate).
   */
  @Test
  void responseOnlyDtosMustNotBeAcceptedAsRequestBodyOnWriteEndpoints() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(isAnnotatedWithAnyOf(POST_MAPPING, PUT_MAPPING, PATCH_MAPPING))
        .should(notAcceptResponseOnlyDtoAsRequestBody())
        .because(
            "Write endpoints must accept a dedicated request DTO (e.g. CreateMissionRequest, "
                + "UpdateMissionRequest) that structurally excludes server-managed fields — "
                + "binding the full response DTO opens a mass-assignment vector. See audit "
                + "finding C-3 in CHANGELOG / MissionMapper#toEntity removal.")
        .check(CLASSES);
  }

  private static ArchCondition<JavaMethod> notAcceptResponseOnlyDtoAsRequestBody() {
    return new ArchCondition<JavaMethod>(
        "not declare a @RequestBody parameter of a response-only DTO type") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        method.getParameters().stream()
            .filter(p -> p.isAnnotatedWith(REQUEST_BODY))
            .filter(p -> RESPONSE_ONLY_DTOS.contains(p.getRawType().getFullName()))
            .forEach(
                p ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " — @RequestBody parameter of type "
                                + p.getRawType().getSimpleName()
                                + " is a response-only DTO; binding it on a write endpoint enables"
                                + " mass-assignment of server-managed fields (id, version,"
                                + " owningSquadron, …). Switch to a dedicated *Request record"
                                + " from backend/.../dto/request/. See audit finding C-3.")));
      }
    };
  }

  /**
   * Condition backing {@link #orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper()}: the
   * method body (or a method reference from it) must invoke a {@code requireCan*} authorization
   * helper, which is what fails a mutation closed when the caller is not entitled.
   *
   * @return the ArchUnit condition
   */
  private static ArchCondition<JavaMethod> callARequireCanAuthorizationHelper() {
    return new ArchCondition<JavaMethod>(
        "call a requireCan* authorization helper from its own body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean guarded =
            method.getMethodCallsFromSelf().stream()
                .map(call -> call.getTarget().getName())
                .anyMatch(name -> name.startsWith("requireCan"));
        if (guarded) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " mutates org-unit bank settings but does not call a requireCan*"
                    + " authorization helper — it would ship reachable by any authenticated member"
                    + " (the controller and proxy only require isAuthenticated())."));
      }
    };
  }

  private static ArchCondition<JavaMethod> callOneOfTheGuestRedactionHelpers() {
    return new ArchCondition<JavaMethod>(
        "call a cleanup…ForPeer redaction helper from its own body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean callsHelper =
            method.getMethodCallsFromSelf().stream()
                .map(call -> call.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        if (callsHelper) {
          return;
        }
        boolean referencesHelper =
            method.getAccessesFromSelf().stream()
                .map(access -> access.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        if (referencesHelper) {
          return;
        }
        Set<String> protectedByHandler = protectedDtosOf(method);
        boolean callsALocalHelperThatRedacts =
            method.getMethodCallsFromSelf().stream()
                .filter(
                    call ->
                        call.getTargetOwner().getFullName().equals(method.getOwner().getFullName()))
                .flatMap(call -> call.getTarget().resolveMember().stream())
                .filter(
                    target ->
                        !java.util.Collections.disjoint(
                            protectedDtosOf(target), protectedByHandler))
                .anyMatch(
                    target ->
                        target.getAccessesFromSelf().stream()
                            .map(access -> access.getTarget().getName())
                            .anyMatch(ArchitectureTest::isGuestRedactionHelperName));
        if (callsALocalHelperThatRedacts) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — a member below Logistician reaches this endpoint (its @PreAuthorize"
                    + " gate admits one) and the return type carries participant PII, but the"
                    + " method body does not invoke any cleanup…ForPeer redaction helper."
                    + " Participant e-mail addresses and real names will leak to a peer"
                    + " (REQ-SEC-007) — see audit findings C-1 / C-2."));
      }
    };
  }

  /**
   * Audit finding C-4 guard (2026-05-20 security audit): the unconditional server-side stamping of
   * {@code owningSquadron} / {@code owner} / {@code parent} in {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#createMission} and {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#addSubMission} relies on the
   * corresponding columns NEVER being present on the request DTOs. The C-3 refactor enforces this
   * structurally by giving the records only safe components, but a future maintainer could ship a
   * "small convenience" patch like adding {@code UUID owningSquadronId} to {@code
   * CreateMissionRequest} and re-wiring the service to honour it — that single step re-opens the
   * squadron-stamp-forgery vector (an authenticated KRT_MEMBER of squadron A creates a mission
   * stamped as squadron B's, optionally with {@code isInternal=true} so it is hidden from A's
   * roster).
   *
   * <p>This rule locks down the shape: {@code CreateMissionRequest} and {@code
   * UpdateMissionRequest} must not declare any record component whose name matches a server-
   * managed concern. Adding a new column to {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.MissionDto} response side is fine; adding
   * {@code owningSquadronId} / {@code parentId} / {@code ownerId} / {@code id} / etc. to the
   * write-side records is what this guard prevents.
   */
  @Test
  void missionWriteRequestDtosMustNotCarryServerManagedFields() {
    classes()
        .that()
        .haveFullyQualifiedName(
            "de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest")
        .or()
        .haveFullyQualifiedName(
            "de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest")
        .should(notDeclareServerManagedRecordComponents())
        .because(
            "MissionService.createMission / addSubMission stamp owner / owningSquadron / parent"
                + " from the authenticated principal and the path-resolved parent — never from the"
                + " request body. The request records must not grow components for those concerns"
                + " or the squadron-stamp-forgery vector returns. See audit finding C-4.")
        .check(CLASSES);
  }

  /**
   * Record component names that are forbidden on Mission write DTOs (audit finding C-4): server-
   * managed concerns that the service stamps unconditionally and which a client-supplied value
   * would silently override. {@code version} is allowed on {@code UpdateMissionRequest} because it
   * is the optimistic-lock token — the check below carves it out for the update DTO only.
   */
  private static final Set<String> FORBIDDEN_MISSION_REQUEST_COMPONENTS =
      Set.of(
          "id",
          "version",
          "coreVersion",
          "scheduleVersion",
          "flagsVersion",
          "owner",
          "ownerId",
          "managers",
          "owningSquadron",
          "owningSquadronId",
          "squadronId",
          "squadron",
          "owningOrgUnit",
          "creatingOrgUnit",
          "requestingOrgUnit",
          "parent",
          "parentId",
          "participants",
          "assignedUnits",
          "frequencies",
          "subMissions",
          "inventoryEntries",
          "refineryOrders",
          "canEdit",
          "canManageManagers",
          "checkedInParticipants",
          "registeredParticipants");

  private static ArchCondition<JavaClass> notDeclareServerManagedRecordComponents() {
    return new ArchCondition<JavaClass>(
        "not declare any server-managed record component (owningSquadron, owner, parent, …)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        boolean isUpdateDto = clazz.getSimpleName().equals("UpdateMissionRequest");
        clazz.getFields().stream()
            .filter(
                f ->
                    !f.getModifiers()
                        .contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC))
            .map(f -> f.getName())
            .filter(FORBIDDEN_MISSION_REQUEST_COMPONENTS::contains)
            .filter(name -> !(isUpdateDto && "version".equals(name)))
            .forEach(
                name ->
                    events.add(
                        SimpleConditionEvent.violated(
                            clazz,
                            clazz.getFullName()
                                + " declares record component `"
                                + name
                                + "` — that field is server-managed (audit finding C-4). Stamp it"
                                + " inside MissionService, not from the request body. If this is"
                                + " legitimately client-supplied, justify in a code comment and"
                                + " carve it out of FORBIDDEN_MISSION_REQUEST_COMPONENTS.")));
      }
    };
  }

  private static final String OPTIMISTIC_LOCK = "org.hibernate.annotations.OptimisticLock";
  private static final String MISSION_FQN = "de.greluc.krt.profit.basetool.backend.model.Mission";
  private static final String MISSION_REPOSITORY_FQN =
      "de.greluc.krt.profit.basetool.backend.repository.MissionRepository";
  private static final String MISSION_SERVICE_FQN =
      "de.greluc.krt.profit.basetool.backend.service.MissionService";
  private static final String MISSION_PARTICIPANT_SERVICE_FQN =
      "de.greluc.krt.profit.basetool.backend.service.MissionParticipantService";

  /**
   * The multi-user signup concurrency contract (see the inline comment block in {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#addParticipant} and the {@code
   * MissionParticipantConcurrencyTest} integration test): adding or removing a participant must
   * never bump {@link de.greluc.krt.profit.basetool.backend.model.Mission#getVersion()}, so
   * concurrent "Anmelden" clicks on the same mission cannot trigger an {@code
   * ObjectOptimisticLockingFailureException} on the parent row. The annotation that enforces this
   * at the Hibernate level is {@code @OptimisticLock(excluded = true)} on the {@code participants}
   * collection in {@code Mission}. Removing the annotation — or flipping {@code excluded} to {@code
   * false} — silently re-opens the 409-on-concurrent-signup regression class, which only surfaces
   * in prod under contention. This rule fails the build the moment that annotation drifts.
   */
  @Test
  void missionParticipantsCollectionMustExcludeOptimisticLock() {
    classes()
        .that()
        .haveFullyQualifiedName(MISSION_FQN)
        .should(missionParticipantsFieldHasOptimisticLockExcluded())
        .because(
            "Mission.participants must remain @OptimisticLock(excluded = true) so concurrent"
                + " participant signups do not bump Mission.version. Removing the annotation"
                + " re-opens 409s on parallel \"Anmelden\" clicks — see"
                + " MissionParticipantConcurrencyTest and the comment block on"
                + " MissionService.addParticipant.")
        .check(CLASSES);
  }

  /**
   * R6.a / SPEZIALKOMMANDO_PLAN.md §8.2 + §11 R4: {@link
   * de.greluc.krt.profit.basetool.backend.model.PromotionTopic#owningSquadron} MUST stay typed
   * {@link de.greluc.krt.profit.basetool.backend.model.Squadron}, never loosened to {@link
   * de.greluc.krt.profit.basetool.backend.model.OrgUnit}. The V97 CHECK constraint blocks the
   * column-level case (Postgres rejects an SK row in {@code promotion_topic.owning_squadron_id} via
   * the trigger from §3.3), but a careless Java-side refactor that retypes the field to {@code
   * OrgUnit} would let a service-layer setter accept a {@link
   * de.greluc.krt.profit.basetool.backend.model.SpecialCommand} reference, bypass the V97
   * application-side guard ({@code SpecialCommand} constructor sets {@code isPromotionEnabled =
   * false}), and only fail at flush time with a generic constraint-violation 500 instead of a clean
   * 400 at the service boundary. This rule catches the type loosening before the code compiles its
   * way into prod.
   */
  @Test
  void promotionTopicOwningSquadronMustStayTypedSquadronNotOrgUnit() {
    classes()
        .that()
        .haveFullyQualifiedName(PROMOTION_TOPIC_FQN)
        .should(
            new ArchCondition<JavaClass>(
                "declare an `owningSquadron` field whose raw type is Squadron (not OrgUnit)") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                var owningSquadronField =
                    javaClass.getFields().stream()
                        .filter(f -> "owningSquadron".equals(f.getName()))
                        .findFirst();
                if (owningSquadronField.isEmpty()) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          "PromotionTopic is missing the `owningSquadron` field —"
                              + " SPEZIALKOMMANDO_PLAN.md §3.3 explicitly keeps this field"
                              + " typed Squadron (not OrgUnit) so promotion data can only"
                              + " reference Squadron rows. If you renamed it, restore the"
                              + " field; if you removed it entirely, drop this guard with a"
                              + " code-comment rationale."));
                  return;
                }
                String rawType = owningSquadronField.get().getRawType().getFullName();
                if (!SQUADRON_FQN.equals(rawType)) {
                  events.add(
                      SimpleConditionEvent.violated(
                          owningSquadronField.get(),
                          "PromotionTopic.owningSquadron has raw type "
                              + rawType
                              + " — must stay "
                              + SQUADRON_FQN
                              + ". Loosening it to OrgUnit lets a SpecialCommand reference"
                              + " sneak past the application-side guard"
                              + " (SPEZIALKOMMANDO_PLAN.md §8.2 / §11 R4)."));
                }
              }
            })
        .check(CLASSES);
  }

  /**
   * Pins the second half of the signup concurrency contract: the {@code addParticipant} overloads
   * must never call {@code missionRepository.save(...)} (or {@code saveAndFlush}). The save is the
   * only realistic way to dirty the parent {@code mission} row from inside this method — and a
   * dirty parent row would issue an {@code UPDATE mission} statement that, under contention, races
   * between threads and surfaces as {@code ObjectOptimisticLockingFailureException}. Persisting the
   * new participant via {@code missionParticipantRepository.save(participant)} is the supported
   * path; Hibernate's cascade + dirty-check on the inverse-side collection handles the rest without
   * touching the parent row.
   *
   * <p>The rule is structural: it walks the bytecode of every method named {@code addParticipant}
   * declared on {@link de.greluc.krt.profit.basetool.backend.service.MissionParticipantService}
   * (where the real signup logic lives since the L1 step-2 split, #920) <em>and</em> on the {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService} facade (whose overloads are thin
   * delegations that must likewise stay save-free), rejecting any direct call into {@code
   * MissionRepository#save*}. Covering both classes keeps the guard pointed at the actual signup
   * body — a rule scoped to the facade alone would pass vacuously after the method body moved.
   * False positives (e.g. a future legitimate reason to re-save the mission inside the signup flow)
   * should be carved out by renaming the method or by extracting the save into a dedicated helper
   * that is itself documented.
   */
  @Test
  void missionServiceAddParticipantMustNotSaveMission() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .haveFullyQualifiedName(MISSION_PARTICIPANT_SERVICE_FQN)
        .or()
        .areDeclaredInClassesThat()
        .haveFullyQualifiedName(MISSION_SERVICE_FQN)
        .and()
        .haveName("addParticipant")
        .should(notCallMissionRepositorySave())
        .because(
            "MissionParticipantService.addParticipant (and the MissionService facade delegation)"
                + " must not bump Mission.version under concurrent signups — calling"
                + " missionRepository.save(mission) inside the flow would dirty the parent row and"
                + " re-open 409s on parallel \"Anmelden\" clicks. Persist the new participant via"
                + " missionParticipantRepository.save(participant) and let Hibernate's cascade"
                + " handle the rest. See MissionParticipantConcurrencyTest and the comment block on"
                + " MissionParticipantService.addParticipant.")
        .check(CLASSES);
  }

  private static ArchCondition<JavaClass> missionParticipantsFieldHasOptimisticLockExcluded() {
    return new ArchCondition<JavaClass>(
        "declare Mission.participants with @OptimisticLock(excluded = true)") {
      @Override
      public void check(JavaClass clazz, ConditionEvents events) {
        var participantsField =
            clazz.getFields().stream()
                .filter(f -> "participants".equals(f.getName()))
                .findFirst()
                .orElse(null);
        if (participantsField == null) {
          events.add(
              SimpleConditionEvent.violated(
                  clazz,
                  clazz.getFullName()
                      + " — `participants` field is missing entirely; the signup concurrency"
                      + " contract assumes Mission has a participants collection annotated with"
                      + " @OptimisticLock(excluded = true)."));
          return;
        }
        if (!participantsField.isAnnotatedWith(OPTIMISTIC_LOCK)) {
          events.add(
              SimpleConditionEvent.violated(
                  participantsField,
                  participantsField.getFullName()
                      + " — missing @OptimisticLock annotation; adding a participant would dirty"
                      + " the parent collection and bump Mission.version, breaking concurrent"
                      + " signups."));
          return;
        }
        JavaAnnotation<?> annotation = participantsField.getAnnotationOfType(OPTIMISTIC_LOCK);
        boolean excluded =
            annotation
                .tryGetExplicitlyDeclaredProperty("excluded")
                .map(value -> Boolean.TRUE.equals(value))
                .orElse(false);
        if (!excluded) {
          events.add(
              SimpleConditionEvent.violated(
                  participantsField,
                  participantsField.getFullName()
                      + " — @OptimisticLock is present but `excluded` is not explicitly set to"
                      + " true. Concurrent signups will bump Mission.version and trigger 409s."
                      + " Restore `@OptimisticLock(excluded = true)`."));
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> notCallMissionRepositorySave() {
    return new ArchCondition<JavaMethod>("not invoke MissionRepository#save* from its body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        method.getMethodCallsFromSelf().stream()
            .filter(
                call -> MISSION_REPOSITORY_FQN.equals(call.getTarget().getOwner().getFullName()))
            .filter(call -> call.getTarget().getName().startsWith("save"))
            .forEach(
                call ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                + " calls "
                                + call.getTarget().getOwner().getSimpleName()
                                + "#"
                                + call.getTarget().getName()
                                + " — that dirties the parent Mission row and re-opens"
                                + " optimistic-locking failures on concurrent participant"
                                + " signups. Persist the new participant via"
                                + " missionParticipantRepository.save(participant) instead.")));
      }
    };
  }

  /**
   * Pure-helper classes under {@code integration.scwiki} that legitimately do NOT inject {@code
   * ScWikiClient}. Empty since the cycle cleanup (ADR-0047) relocated the SC-Wiki sync
   * orchestrators — including the curated {@code BlueprintOutputNameOverrides} map (#327) — to the
   * {@code service.scwiki} package, leaving {@code integration.scwiki} with only {@code
   * ScWikiClient} itself. Re-add a simple name here only if a new stateless, HTTP-free helper
   * genuinely belongs beside the client in {@code integration.scwiki}; anything that talks to the
   * Wiki must inject the client.
   */
  private static final Set<String> SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES = Set.of();

  /**
   * SC_WIKI_SYNC_PLAN.md §3.4 / R1 guard: every class in the {@code integration.scwiki} package
   * MUST depend on {@code ScWikiClient}. The rule keeps the package focused on classes that
   * interact with the SC Wiki HTTP API — a helper / DTO / scheduler that does not consult the
   * client belongs elsewhere (most likely under {@code service.scwiki} once R3 ships).
   *
   * <p>{@code ScWikiClient} itself is exempt: it IS the dependency target. Future sync services
   * ({@code ScWikiCommoditySyncService}, {@code ScWikiBlueprintSyncService}, …) added in R3+
   * inherit the requirement automatically. Stateless pure-helper beans that encode SC-Wiki domain
   * knowledge without making any HTTP call are also exempt via {@link
   * #SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES} (e.g. {@code BlueprintOutputNameOverrides},
   * #327).
   *
   * <p>Modelled on {@link #staffelScopedServicesMustWireOwnerScopeOrAuthHelper}: walks the declared
   * fields of each candidate class and checks the raw-type FQN for the client.
   */
  @Test
  void scWikiIntegrationClassesMustWireScWikiClient() {
    String scWikiClientFqn =
        "de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient";
    classes()
        .that()
        .resideInAPackage("..backend.integration.scwiki..")
        .and()
        .doNotHaveSimpleName("ScWikiClient")
        .should(
            new ArchCondition<JavaClass>("inject ScWikiClient") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.getEnclosingClass().isPresent()) {
                  return;
                }
                if (SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES.contains(
                    javaClass.getSimpleName())) {
                  return;
                }
                boolean injectsClient =
                    javaClass.getFields().stream()
                        .map(f -> f.getRawType().getFullName())
                        .anyMatch(scWikiClientFqn::equals);
                if (!injectsClient) {
                  events.add(
                      SimpleConditionEvent.violated(
                          javaClass,
                          javaClass.getName()
                              + " lives under integration.scwiki but does not inject"
                              + " ScWikiClient. Either depend on the shared HTTP client or move"
                              + " the class to a different package (e.g. service.scwiki)."));
                }
              }
            })
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  /**
   * R6.a / SPEZIALKOMMANDO_PLAN.md §8.5: no new {@code @JoinColumn(name = "squadron_id")} outside
   * the grandfathered legacy entities listed in {@link #SQUADRON_ID_COLUMN_GRANDFATHERED_FQNS}.
   * Those columns are on the destructive-cleanup release's drop list — once {@code
   * app_user.squadron_id} (and the matching {@code mission_participant.squadron_id} snapshot) are
   * gone, every reference to that column name in JPA mappings becomes a Hibernate validation
   * failure at boot. Re-introducing the name on a new entity (e.g. a fresh staffel-scoped aggregate
   * that forgets to follow the {@code owning_squadron_id} convention) would silently re-create the
   * legacy coupling. This rule keeps the migration one-way: only the allowlisted entities may
   * reference the column; anything else has to use {@code owning_squadron_id} (legacy mirror) or
   * {@code owning_org_unit_id} (new column).
   */
  @Test
  void noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities() {
    classes()
        .that()
        .resideInAPackage("de.greluc.krt.profit.basetool.backend.model..")
        .and()
        .areNotInterfaces()
        .should(
            new ArchCondition<JavaClass>(
                "not declare any @JoinColumn(name = \"squadron_id\") field outside the"
                    + " grandfathered legacy entities (User)") {
              @Override
              public void check(JavaClass javaClass, ConditionEvents events) {
                if (SQUADRON_ID_COLUMN_GRANDFATHERED_FQNS.contains(javaClass.getFullName())) {
                  return;
                }
                javaClass
                    .getFields()
                    .forEach(
                        field ->
                            field.getAnnotations().stream()
                                .filter(a -> JOIN_COLUMN.equals(a.getRawType().getFullName()))
                                .forEach(
                                    a -> {
                                      String name =
                                          a.tryGetExplicitlyDeclaredProperty("name")
                                              .map(Object::toString)
                                              .orElse("");
                                      if ("squadron_id".equals(name)) {
                                        events.add(
                                            SimpleConditionEvent.violated(
                                                field,
                                                javaClass.getFullName()
                                                    + "#"
                                                    + field.getName()
                                                    + " uses @JoinColumn(name ="
                                                    + " \"squadron_id\") — that column name is"
                                                    + " on the destructive-cleanup drop list"
                                                    + " (SPEZIALKOMMANDO_PLAN.md §4 R3). Use"
                                                    + " owning_squadron_id (legacy mirror) or"
                                                    + " owning_org_unit_id (new column) on new"
                                                    + " staffel-scoped aggregates. Only the"
                                                    + " grandfathered legacy entities listed"
                                                    + " in SQUADRON_ID_COLUMN_"
                                                    + "GRANDFATHERED_FQNS may use this column"
                                                    + " name; new entries require a"
                                                    + " code-comment rationale."));
                                      }
                                    }));
              }
            })
        .check(CLASSES);
  }

  /**
   * REQ-BANK-019 (season independence): the bank is a standalone ledger with no coupling to
   * seasons, price lines, mission finance, operation payouts or job-order profit flows. No bank
   * production class may depend on those aggregates — an integration (e.g. auto-booking operation
   * payouts) is explicitly out of scope and would require a spec change first.
   */
  @Test
  void bankClassesMustStaySeasonAndProfitIndependent() {
    DescribedPredicate<JavaClass> profitFlowTypes =
        new DescribedPredicate<>(
            "mission/operation/job-order/price-line/season aggregates (REQ-BANK-019)") {
          @Override
          public boolean test(JavaClass input) {
            String name = input.getSimpleName();
            return input.getPackageName().startsWith("de.greluc.krt.profit.basetool.backend")
                && (name.startsWith("Mission")
                    || name.startsWith("Operation")
                    || name.startsWith("JobOrder")
                    || name.startsWith("PriceLine")
                    || name.startsWith("Season"));
          }
        };
    noClasses()
        .that()
        .haveSimpleNameStartingWith("Bank")
        .should()
        .dependOnClassesThat(profitFlowTypes)
        .because(
            "the bank has no coupling to seasons, price lines or profit flows (REQ-BANK-019);"
                + " integrations require a spec change first")
        .check(CLASSES);
  }

  /**
   * REQ-BANK-008 (org-unit independence): bank authorization evaluates only the two bank roles and
   * the grant table. No bank class may consult {@code OwnerScopeService} — org-unit scoping,
   * contextual authorities and the admin pin must have zero influence on bank decisions, by
   * construction. (The {@code BankAccount.orgUnit} reference is an owner <em>label</em> resolved
   * via {@code OrgUnitRepository}, not a scope, and stays allowed.)
   */
  @Test
  void bankClassesMustNotConsultOrgUnitScope() {
    noClasses()
        .that()
        .haveSimpleNameStartingWith("Bank")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("de.greluc.krt.profit.basetool.backend.service.OwnerScopeService")
        .because(
            "bank gates are independent of org-unit membership in both directions"
                + " (REQ-BANK-008); only bank roles and bank_account_grant rows decide")
        .check(CLASSES);
  }

  /**
   * Epic #692 / REQ-ORG-015 (the HARD INVARIANT) + REQ-SEC cascading-scope security: the
   * cascading-scope expansion ({@code OrgUnitCascadeService}) must be a pure function of the
   * caller's memberships plus the persisted hierarchy — it must NEVER consult the security context
   * (admin status). If it could read {@code AuthHelperService.isAdmin()} it could branch on admin
   * and route an OL/Bereich principal through an admin-all grant; pinning the absence of that
   * dependency keeps the cascade strictly officer-equivalent and leaves the {@code
   * adminAllScope=true} branch reachable only from the genuine admin path in {@code
   * OwnerScopeService}. This is the durable, structural guarantee behind "an OL/Bereich principal
   * can never satisfy {@code isAdmin()}" — the cascade literally cannot know whether the caller is
   * an admin, so its output (a concrete org-unit-id union) can never be an admin marker. The
   * runtime value invariant (the cascade path always builds {@code adminAllScope=false}) is pinned
   * by {@code OwnerScopeServiceTest} ({@code cascade_neverSetsAdminAllScope}).
   */
  @Test
  void cascadeServiceMustNotConsultTheSecurityContext() {
    noClasses()
        .that()
        .haveSimpleName("OrgUnitCascadeService")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("de.greluc.krt.profit.basetool.backend.service.AuthHelperService")
        .because(
            "the cascading-scope expansion must be a pure function of memberships + hierarchy and"
                + " must never branch on admin status, so it can never route an OL/Bereich"
                + " principal through adminAllScope / isAdmin (epic #692, REQ-ORG-015 hard"
                + " invariant)")
        .check(CLASSES);
  }

  /**
   * Epic #800 / REQ-ROLE-004: the delegated-appointment authoriser ({@code
   * OrgRoleManagementSecurityService}) must compute its verdict purely from the caller's own
   * membership ranks plus the persisted hierarchy. It must NEVER depend on {@code
   * OwnerScopeService} — that bean folds in the admin-pin header, the admin-all scope and the
   * cascading reach, none of which may leak into a delegated appointment verdict (a Bereichsleiter
   * pinned to a subordinate unit must not thereby gain appointment rights there). The "no
   * SecurityContextHolder" half of the invariant is already covered globally by {@link
   * #serviceLayerShouldNotReachIntoSecurityContext()}.
   */
  @Test
  void delegatedRoleAuthoriserMustNotConsultOwnerScope() {
    noClasses()
        .that()
        .haveSimpleName("OrgRoleManagementSecurityService")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("de.greluc.krt.profit.basetool.backend.service.OwnerScopeService")
        .because(
            "the delegated appointment verdict must read only the caller's own membership ranks +"
                + " the persisted hierarchy, never the admin-pin / admin-all / cascading scope that"
                + " OwnerScopeService carries (epic #800, REQ-ROLE-004 no-self-promotion / no-admin"
                + " invariant)")
        .check(CLASSES);
  }

  /**
   * ADR-0020 (org-unit-aware bank seam): the bank stays org-unit-blind (see {@link
   * #bankClassesMustNotConsultOrgUnitScope()}), and the officer/lead features (REQ-BANK-021/-022)
   * route their org-unit logic through exactly one sanctioned, deliberately non-{@code Bank*}-named
   * bridge — {@code OrgUnitBankAccessService}. Any class that couples {@code OwnerScopeService} to
   * the bank-account repository must be that seam, so a future accidental bridge fails the build
   * instead of silently eroding REQ-BANK-008.
   */
  @Test
  void orgUnitAwareBankSeamIsContainedToOneClass() {
    String ownerScope = "de.greluc.krt.profit.basetool.backend.service.OwnerScopeService";
    String bankAccountRepo =
        "de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository";
    DescribedPredicate<JavaClass> bridgeOrgUnitScopeAndBankAccounts =
        new DescribedPredicate<>(
            "depend on both OwnerScopeService and the bank accounts repository") {
          @Override
          public boolean test(JavaClass input) {
            boolean dependsOnOwnerScope = false;
            boolean dependsOnBankAccounts = false;
            for (com.tngtech.archunit.core.domain.Dependency dependency :
                input.getDirectDependenciesFromSelf()) {
              String target = dependency.getTargetClass().getFullName();
              if (ownerScope.equals(target)) {
                dependsOnOwnerScope = true;
              } else if (bankAccountRepo.equals(target)) {
                dependsOnBankAccounts = true;
              }
            }
            return dependsOnOwnerScope && dependsOnBankAccounts;
          }
        };
    classes()
        .that(bridgeOrgUnitScopeAndBankAccounts)
        .should()
        .haveSimpleName("OrgUnitBankAccessService")
        .because(
            "officer/lead bank access bridges org-unit oversight and the bank through exactly one"
                + " sanctioned, non-Bank*-named seam (ADR-0020); BankSecurityService stays"
                + " org-unit-blind (REQ-BANK-008)")
        .check(CLASSES);
  }

  /**
   * REQ-BANK-004 / ADR-0010/0039 (append-only ledgers): {@code bank_transaction}, {@code
   * bank_posting} and the holder ledger {@code bank_holder_posting} rows are never updated or
   * deleted — corrections are {@code REVERSAL} transactions. Two static pins: the ledger
   * repositories declare no {@code @Modifying} methods, and no production class calls a {@code
   * delete*} method on them (the inherited {@code JpaRepository} deleters exist but must stay
   * unused).
   */
  @Test
  void bankLedgerRepositoriesMustStayInsertOnly() {
    Set<String> ledgerRepositories =
        Set.of(
            "de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository",
            "de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository",
            "de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository");
    Set<String> approvedLedgerMutations =
        Set.of(
            "de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository"
                + ".anonymiseCounterpartyHandle");
    noMethods()
        .that()
        .areDeclaredInClassesThat(
            new DescribedPredicate<>("the bank ledger repositories") {
              @Override
              public boolean test(JavaClass input) {
                return ledgerRepositories.contains(input.getFullName());
              }
            })
        .and(
            new DescribedPredicate<>("are not the one approved Art. 17 anonymisation") {
              @Override
              public boolean test(com.tngtech.archunit.core.domain.JavaMethod input) {
                return !approvedLedgerMutations.contains(
                    input.getOwner().getFullName() + "." + input.getName());
              }
            })
        .should()
        .beAnnotatedWith("org.springframework.data.jpa.repository.Modifying")
        .because("ledger rows are insert-only (ADR-0010) — no UPDATE/DELETE query may exist")
        .check(CLASSES);
    noClasses()
        .should()
        .callMethodWhere(
            new DescribedPredicate<>(
                "a delete method on a bank ledger repository (append-only, ADR-0010)") {
              @Override
              public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall input) {
                return ledgerRepositories.contains(input.getTargetOwner().getFullName())
                    && input.getTarget().getName().startsWith("delete");
              }
            })
        .because("corrections are REVERSAL transactions, never deletes (REQ-BANK-004)")
        .check(CLASSES);
  }
}
