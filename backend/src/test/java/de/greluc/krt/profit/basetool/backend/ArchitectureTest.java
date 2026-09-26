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
 * ArchUnit tests enforcing the backend's architectural invariants on production classes: no {@code
 * SecurityContextHolder} outside the auth helper, an authorisation annotation on every endpoint, no
 * JPA entities at controller boundaries, and the scoping, redaction and ledger rules below.
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
   * The verb-agnostic {@code @RequestMapping} annotation, selected alongside the verb-specific ones
   * because ArchUnit matches annotation types, not meta-annotations (REQ-SEC-052).
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
   * Entities still allowed to map the {@code squadron_id} column, exempt from {@link
   * #noNewJoinColumnReferencingSquadronIdOutsideGrandfatheredEntities()}; currently only {@code
   * User.squadron}.
   */
  private static final Set<String> SQUADRON_ID_COLUMN_GRANDFATHERED_FQNS = Set.of(USER_FQN);

  /**
   * Response-only DTOs that must never be accepted as a {@code @RequestBody} on a write endpoint,
   * because they carry server-managed fields ({@code id}, {@code version}, {@code owningSquadron},
   * …). Write endpoints take a dedicated request record instead.
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
   * Method-name prefixes identifying state-mutating service methods for {@link
   * #mutatingServiceMethodsInReadOnlyClassesNeedExplicitTransactional()}; any other name counts as
   * a read.
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
   * Service-layer classes allowed to use {@link
   * org.springframework.security.core.context.SecurityContextHolder}: only {@code
   * AuthHelperService}.
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
   * The only methods that may declare {@code @PreAuthorize("permitAll()")} (REQ-SEC-052): the two
   * anonymous reads of ADR-0159, the Keycloak SPI's shared-secret account precheck, and {@code
   * /error}.
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
   * Every read endpoint must carry its own class- or method-level authorisation annotation
   * (REQ-SEC-052).
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
   * Whether one of the method's request mappings has a path containing the literal {@code "{id}"}
   * placeholder; scopes {@link #staffelScopedWriteEndpointsMustGateOnOwnerScopeService()} to
   * endpoints addressing a primary-resource id.
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
   * Staffel-scoped aggregate services must inject {@code AuthHelperService} or {@code
   * OwnerScopeService}, so their data cannot leak across org units.
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
   * Write endpoints with an {@code {id}} path on the staffel-scoped controllers (missions,
   * operations, hangar, inventory, refinery orders) must use a {@code @PreAuthorize} expression
   * that references {@code ownerScopeService}.
   *
   * <p>Reads, id-less writes, {@code /api/v1/orders} and {@code /api/v1/admin/**} are out of scope;
   * a role-only check still violates.
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
   * Mission endpoints whose gate admits members below Logistician ({@code canSeeMission}, {@code
   * canAccessParticipant}) and return a PII-carrying mission DTO must call a {@code
   * cleanup…ForPeer} helper (REQ-SEC-007).
   *
   * <p>The check is structural: it asserts the helper is referenced, not that the call is
   * conditional, and it fails when it selects no endpoints at all.
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
   * Mission DTOs whose participant data carries PII, recognised by {@link
   * #peerReadableMissionEndpointsMustRedactPii}. Includes {@code MissionFinanceEntryDto}, which
   * embeds {@link de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto}.
   */
  private static final Set<String> MISSION_PII_CARRYING_DTOS =
      Set.of(
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto");

  /**
   * Whether a method name follows the {@code cleanup<EntityName>ForPeer} convention of the
   * participant-PII redaction helpers in {@code MissionPeerRedactor}.
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
   * Returns the PII-carrying mission DTOs a method's return type exposes, directly or as a type
   * argument of a known generic wrapper.
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
   * Write endpoints must not accept a DTO listed in {@code RESPONSE_ONLY_DTOS} as
   * {@code @RequestBody}, which would allow mass assignment of server-managed fields.
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
   * {@code CreateMissionRequest} and {@code UpdateMissionRequest} must not declare server-managed
   * components ({@code id}, {@code owningSquadronId}, {@code parentId}, {@code ownerId}, …), which
   * {@link de.greluc.krt.profit.basetool.backend.service.MissionService#createMission} and {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#addSubMission} stamp server-side.
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
   * The {@code participants} collection of {@code Mission} must keep
   * {@code @OptimisticLock(excluded = true)}, so concurrent signups never bump {@link
   * de.greluc.krt.profit.basetool.backend.model.Mission#getVersion()} and 409.
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
   * {@link de.greluc.krt.profit.basetool.backend.model.PromotionTopic#owningSquadron} must stay
   * typed {@link de.greluc.krt.profit.basetool.backend.model.Squadron}, not {@link
   * de.greluc.krt.profit.basetool.backend.model.OrgUnit}, so a {@link
   * de.greluc.krt.profit.basetool.backend.model.SpecialCommand} can never be assigned.
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
   * The {@code addParticipant} methods of {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionParticipantService} and {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService} must not call {@code
   * MissionRepository#save*}, which would dirty the mission row and cause 409s on concurrent
   * signups.
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
   * Classes in {@code integration.scwiki} exempt from injecting {@code ScWikiClient}; empty. Only a
   * stateless, HTTP-free helper belonging beside the client may be added.
   */
  private static final Set<String> SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES = Set.of();

  /**
   * Every class in {@code integration.scwiki} except {@code ScWikiClient} itself must depend on
   * {@code ScWikiClient}, unless listed in {@link #SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES}.
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
   * No entity outside {@link #SQUADRON_ID_COLUMN_GRANDFATHERED_FQNS} may map
   * {@code @JoinColumn(name = "squadron_id")}; new aggregates use {@code owning_squadron_id} or
   * {@code owning_org_unit_id}.
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
   * No bank class may use {@code OwnerScopeService}; bank authorization depends only on the bank
   * roles and grants (REQ-BANK-008).
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
   * {@code OrgUnitCascadeService} must not consult the security context, so the cascading scope is
   * a pure function of memberships and hierarchy and can never yield an admin scope (REQ-ORG-015).
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
   * {@code OrgRoleManagementSecurityService} must not depend on {@code OwnerScopeService}, so
   * delegated appointment verdicts use only the caller's own membership ranks and the hierarchy
   * (REQ-ROLE-004).
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
   * Only {@code OrgUnitBankAccessService} may couple {@code OwnerScopeService} with the
   * bank-account repository (ADR-0020), keeping the rest of the bank org-unit-blind.
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
   * The bank ledger repositories stay insert-only (REQ-BANK-004): they declare no
   * {@code @Modifying} methods, and no production class calls their {@code delete*} methods.
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
