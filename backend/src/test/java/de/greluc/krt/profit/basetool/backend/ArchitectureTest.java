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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
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

  private static final String PRE_AUTHORIZE =
      "org.springframework.security.access.prepost.PreAuthorize";

  private static final String JPA_ENTITY = "jakarta.persistence.Entity";

  private static final String TRANSACTIONAL =
      "org.springframework.transaction.annotation.Transactional";

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
      java.util.Set.of(
          // The dedicated auth-helper service. Centralises the SecurityContextHolder
          // read so the rest of the codebase can consult the current authentication
          // through a constructor-injected dependency. THIS is the seam — every other
          // service/controller/mapper must depend on AuthHelperService instead of
          // touching SecurityContextHolder directly.
          "de.greluc.krt.profit.basetool.backend.service.AuthHelperService");

  @Test
  void serviceLayerShouldNotReachIntoSecurityContext() {
    // Reasoning: business logic in the service layer must rely on the @PreAuthorize
    // boundary at the controller (or the service method itself, when the rule is
    // role-based) instead of pulling the JWT subject straight from
    // SecurityContextHolder. Otherwise the same service method behaves differently
    // depending on which thread invokes it (Spring scheduling, async, message
    // listeners) and the data-isolation rules become testable only through full
    // Spring context tests. The single allowed escape valve is AuthHelperService;
    // nothing else inside the business-service package should bypass that.
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
    // Reasoning: same rationale as serviceLayerShouldNotReachIntoSecurityContext —
    // controllers used to inline role-hierarchy checks via
    // `SecurityContextHolder.getContext().getAuthentication()` (see
    // JobOrderController#verifyAssigneeAccess, InventoryItemController#isLogisticianOrAbove
    // and RefineryOrderController#isLogisticianOrAbove before the refactor). The rule
    // now forbids that pattern across the controller package; controllers must
    // either accept the authentication as a method parameter (via @AuthenticationPrincipal
    // or `Authentication authentication`) or delegate the lookup to AuthHelperService.
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
  void mapperLayerShouldNotReachIntoSecurityContext() {
    // Reasoning: MapStruct mappers are supposed to be pure transformers. Reaching
    // into SecurityContextHolder from a resolver method (as MissionMapper did before
    // the refactor) couples DTO shaping to the request-scoped security context and
    // makes the mapper untestable without a full Spring security setup. If a mapper
    // needs to know "is the caller authenticated / may they edit this", it must depend
    // on a dependency-leaf SPI (e.g. support.MissionViewerAccess, implemented in the
    // service layer) — never on SecurityContextHolder directly, and (since ADR-0047's
    // cycle cleanup) never on the service layer directly either, which would re-close
    // the mapper <-> service package cycle the leaf interface was introduced to break.
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
    // Reasoning: CLAUDE.md is explicit — "Never expose JPA entities at controller
    // boundaries." A leaked entity drags in Hibernate lazy-loading semantics across
    // the HTTP boundary (Jackson serialising a proxy triggers the famous
    // LazyInitializationException) AND can expose internal columns to the client.
    // The check is intentionally narrow: ArchUnit can only inspect the *raw* return
    // type, so `ResponseEntity<User>` would not be caught by `haveRawReturnType(...)`.
    // In this codebase entity-returning controller methods would still be visible
    // here because the convention is to return the entity directly, not wrap it.
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
  void controllersMustNotInjectTheLazyMembershipMapper() {
    // Reasoning: OrgUnitMembershipMapper.toDto reads user.effectiveName through the LAZY user
    // association. With open-in-view disabled, a controller that maps the entity to its DTO
    // response after the service transaction committed throws LazyInitializationException — the
    // write succeeds but the response 500s (the shipped /organisation/leitung "assign
    // Kommandoleiter" regression). ADR-0067 therefore moved the membership DTO projection into
    // OrgUnitMembershipService's own transactions; this rule pins the new invariant by keeping
    // the mapper out of the controller layer entirely, replacing the retired
    // controllersUsingTheLazyMembershipMapperMustBeTransactional rule (which only demanded a
    // class-level @Transactional around controller-side mapping and became vacuous once no
    // controller injected the mapper anymore).
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
    // Reasoning: every @RestController must make at least one explicit authorisation
    // decision somewhere — either a class-level @PreAuthorize or at least one
    // method-level @PreAuthorize. The weaker form (class-level) is sufficient to
    // catch the worst regression case: a controller that ships with zero auth
    // annotations and silently falls through to SecurityConfig's catch-all.
    //
    // We deliberately do NOT require every handler method to be annotated, because
    // the current codebase mixes the two patterns ("controller-level @PreAuthorize
    // covers everything" vs. "method-level @PreAuthorize per endpoint") and many
    // public endpoints are gated by SecurityConfig's `requestMatchers(...).permitAll()`
    // instead. Tightening to per-method is a separate, larger follow-up.
    classes()
        .that()
        .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should(haveAtLeastOnePreAuthorizeAnnotation())
        .because(
            "Every REST controller class must declare at least one @PreAuthorize annotation (either"
                + " on the class or on any method) so it cannot silently bypass authorisation."
                + " Public endpoints should use @PreAuthorize(\"permitAll()\").")
        .check(CLASSES);
  }

  @Test
  void orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper() {
    // Security review (INFO regression guard): the org-unit bank settings mutations (balance
    // target, view-visibility grants, per-tier approval limits) are authorized ONLY by an in-body
    // require* helper — the controller and the frontend proxy both gate merely on
    // isAuthenticated(),
    // with no @PreAuthorize predicate and no annotation at the boundary. That is correct today
    // (every mutation calls its requireCan* helper), but a future mutation that dropped the check
    // would ship reachable by ANY authenticated member, with no failing gate to catch it. This rule
    // pins the invariant: every public OrgUnitBankAccessService method that returns the settings
    // DTO
    // and mutates (set/add/remove/clear) MUST invoke a requireCan* authorization helper, so a
    // dropped check fails the build instead of shipping fail-open.
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
    // Reasoning: CLAUDE.md prescribes a strict controller → service → repository layering.
    // A controller injecting a Spring Data repository directly skips the service layer where
    // multi-user data isolation, transactional boundaries and the @PreAuthorize logic live —
    // and once that shortcut exists, it tends to multiply. Forbidding the dependency at the
    // package level (controllers must not even see repositories) makes the layering breach
    // visible at compile time instead of in a long code review.
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
  void supportPackageMustStayADependencyLeaf() {
    // Reasoning: the `support` package holds cross-layer collaborators (e.g.
    // StaffelMembershipResolver) that BOTH the `mapper` and the `service` layer reuse. For that
    // sharing to be safe it must stay a dependency LEAF — depending only downward on `model` /
    // `repository` — so it can never sit on both ends of a package cycle. Forbidding any dependency
    // on the orchestration / web layers (which themselves depend on `support`) keeps the graph
    // acyclic by construction.
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
    // Reasoning: a package dependency cycle (slice A -> slice B -> ... -> slice A) is the
    // structural
    // smell behind "everything depends on everything" — it defeats layering, makes the build order
    // ambiguous, blocks extracting a package into its own module, and lets an innocent-looking edit
    // close a loop that ripples across unrelated subsystems. The backend was made fully acyclic
    // (ADR-0047): the per-first-segment slices (config, service, controller, mapper, model,
    // repository, exception, integration, event, filter, validation, support, …) form a DAG. This
    // rule pins that — any new cross-package edge that re-introduces a cycle (a mapper importing a
    // service, `support` importing upward, a config @Component reaching into service, …) fails
    // here.
    // Shared, dependency-free collaborators belong in the `support` leaf (see
    // supportPackageMustStayADependencyLeaf); a layer that needs a peer's behaviour without owning
    // the dependency direction inverts it through a leaf interface (e.g. MaterialPieceTypeLookup,
    // MissionViewerAccess).
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
    // Directional, clear-message guard pinning one edge of
    // backendPackagesShouldBeFreeOfDependencyCycles. The service layer already depends on the
    // mapper
    // layer (services map entities to DTOs through the mappers), so a mapper depending back on a
    // service re-closes the mapper <-> service package cycle ADR-0047 removed. A mapper that needs
    // caller-aware behaviour depends on a dependency-leaf SPI instead (e.g.
    // support.MissionViewerAccess), implemented in the service layer.
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
    // integration is the low-level external-API client layer (UexClient, ScWikiClient); the service
    // layer orchestrates those clients, so service -> integration is the only legal direction. The
    // SC-Wiki sync orchestrators that used to live here (and import services) moved to
    // service.scwiki (ADR-0047); a new integration -> service edge would re-close the
    // integration <-> service cycle.
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
    // The event package holds after-commit domain-event payload records (data only); the listeners
    // and producers that consume services live in the service layer (service -> event is the legal
    // direction). NotificationEventListener moved to service (ADR-0047), so an event -> service
    // edge would re-close the event <-> service package cycle.
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
    // model.dto carries the bean-validation constraint annotations (model -> validation), so the
    // validation package must stay a dependency leaf: a validation -> model / repository / service
    // edge would re-close the model <-> validation (and model -> validation -> repository -> model)
    // cycles ADR-0047 removed. A validator that needs domain data depends on a leaf SPI instead
    // (e.g. validation.MaterialPieceTypeLookup, implemented by the service layer).
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
    // Reasoning: complements `controllerMethodsShouldNotReturnJpaEntities()`. That sister rule
    // only inspects the *raw* return type, so a method that returns `ResponseEntity<User>` or
    // `Page<Mission>` slips through — even though the JPA entity still ends up serialised on
    // the wire with all the lazy-loading / column-leak risks CLAUDE.md warns about. This rule
    // walks the actual generic type arguments of the known wrapper types
    // ({@link #ENTITY_GENERIC_WRAPPERS}) and rejects any wrapper carrying a {@code @Entity}.
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
    // Reasoning: many services declare a class-level @Transactional(readOnly = true) so that
    // their query methods inherit a read-only transaction by default. Mutating methods on such
    // a class MUST override that with their own @Transactional, otherwise the JPA writes
    // either fail (Postgres refuses INSERT/UPDATE under SET TRANSACTION READ ONLY) or, worse,
    // silently no-op because the persistence context is never flushed. Forgetting this
    // override is a subtle bug that does not surface in `application-dev.yml` (some drivers
    // tolerate it) but breaks in prod.
    //
    // The rule fires when:
    //   * the declaring class carries @Transactional(readOnly = true), AND
    //   * the method name starts with a mutating prefix (see MUTATING_METHOD_PREFIXES), AND
    //   * the method itself is not annotated with @Transactional.
    // It does NOT inspect the method body, so the heuristic relies on the project's naming
    // convention (createX/updateX/deleteX/addX/removeX/…). False positives can be silenced
    // by simply annotating the method with @Transactional(readOnly = true) explicitly.
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
    // Reasoning: M-9 from the performance audit. A repository that overrides the inherited
    // {@code List<T> findAll()} typically does so to attach an {@code @EntityGraph} — which
    // means it intends to load every row WITH its eager-fetched collections in one shot.
    // That is a latency / OOM bomb the moment the table grows: an unbounded result set joined
    // against multiple collections produces a Cartesian explosion, and there is no pagination
    // gate to catch it. Every read path through our repositories must go through
    // {@code findAll(Pageable)}, a scoped query method (e.g. {@code searchMissions}), or a
    // {@code findById} lookup. The inherited {@code CrudRepository.findAll()} cannot be
    // blocked here (it lives on the parent interface), but at least our own code must not
    // re-declare it — this rule fails the build the moment someone adds the override back.
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
        // The intended steady state is zero matches: nothing in our repository package
        // re-declares no-arg findAll(). ArchUnit fails empty `should` clauses by default
        // ("did your rule actually run?"), so opt out — the inverse `noMethods` framing
        // would also work but reads worse with the custom violation message above.
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
    // Reasoning: tightens `everyRestControllerShouldDeclareAtLeastOneAuthorisationAnnotation`
    // from "the class declares *some* @PreAuthorize" to "every state-changing endpoint
    // (@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping) carries explicit authorisation",
    // either inline on the method or class-wide. The previous, weaker form let a brand-new
    // write endpoint slip through without an explicit decision as long as some other method
    // on the same controller had a @PreAuthorize — exactly the regression case that hides a
    // missing auth check behind an unrelated annotation.
    //
    // Public endpoints are allowed but must be EXPLICIT: annotate them with
    // @PreAuthorize("permitAll()") so the decision is visible at the method level instead of
    // hiding two folders away in SecurityConfig's requestMatchers list.
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(isAnnotatedWithAnyOf(POST_MAPPING, PUT_MAPPING, DELETE_MAPPING, PATCH_MAPPING))
        .should(haveMethodOrClassLevelPreAuthorize())
        .because(
            "Every state-changing HTTP endpoint must carry an explicit @PreAuthorize "
                + "(either method-level or class-level). For deliberately public endpoints "
                + "use @PreAuthorize(\"permitAll()\") so the auth contract stays visible "
                + "next to the handler instead of buried in SecurityConfig.")
        .check(CLASSES);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private static String allowedClassNamesRegex() {
    return SECURITY_CONTEXT_HOLDER_EXCEPTIONS.stream()
        .map(java.util.regex.Pattern::quote)
        .reduce((a, b) -> a + "|" + b)
        .orElseThrow();
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
    // JobOrderHandoverService is intentionally excluded — its access gate lives on the parent
    // order's controller endpoint (@ownerScopeService.canEditJobOrder), and it injects
    // AuthHelperService anyway for the audit stamp on the handover record, verified by its own unit
    // tests rather than by this rule. The list/detail visibility scoping that JobOrderService wired
    // for Phase 3 (#343) moved into JobOrderQueryService (audit Thema 7, #14): it pushes
    // OwnerScopeService into every read (SK-public vs squadron-private), so it is whitelisted
    // below.
    // JobOrderService itself stays whitelisted via AuthHelperService, which its writes still wire.
    Set<String> staffelScopedServiceNames =
        Set.of(
            "MissionService",
            "InventoryItemService",
            // L2 split (#921): the org-unit scoping the facade used to carry moved into these two
            // extracted services — InventoryAggregationService runs every scoped read
            // (currentScopePredicate on the aggregated / grouped / flat / drilldown views) and
            // InventoryCheckoutService the scoped global wipe. The facade now only wires
            // OwnerScopeService for the create-time stamp, so both must be whitelisted here or a
            // maintainer dropping the dependency would silently un-scope every squadron-wide
            // inventory read across org units without failing the build.
            "InventoryAggregationService",
            "InventoryCheckoutService",
            "RefineryOrderService",
            "HangarService",
            "OperationService",
            "JobOrderService",
            // Read/write split (#14): the list/detail visibility scoping moved into the query half.
            "JobOrderQueryService",
            // Phase 4 (#344): material claims gate on the claiming squadron's scope
            // (AuthHelperService.canEditOrgUnit) + the responsible-SK authority
            // (OwnerScopeService.hasRoleInOrgUnit), so the service must wire both.
            "MaterialClaimService",
            // #364: the blueprint availability overview filters the aggregate to the caller's
            // oversight org units via OwnerScopeService.currentOversightScope().
            "PersonalBlueprintOverviewService",
            // Epic #692 Phase 6 (REQ-BANK-027): the org-unit-aware bank seam scopes the F1
            // balance view + F2 booking requests through OwnerScopeService
            // (currentOversightScope / currentOwnLevelOversightScope). Whitelisted so a
            // maintainer who drops that dependency — silently un-scoping the bank seam — fails
            // the build. It is also the sole sanctioned OwnerScope↔bank bridge, pinned by
            // orgUnitAwareBankSeamIsContainedToOneClass.
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
            // R6.a — SPEZIALKOMMANDO_PLAN.md §8.1 extension.
            // SpecialCommandController is fully ADMIN-gated (R5.a). Adding it to the whitelist
            // makes the audit's reach explicit and would catch a future maintainer who relaxes
            // any endpoint to a non-admin gate without wiring the owner-scope check.
            "SpecialCommandController",
            // SpecialCommandMembershipController is admin-or-Lead-gated via
            // @specialCommandSecurityService.canManageMembers (R5.b); the Lead-toggle endpoint
            // is admin-only. The accepted-gate set below widens to recognise that bean.
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
                // POST is included alongside PUT/PATCH/DELETE because POST /{id}/<action> can
                // mutate a specific resource (e.g. /inventory/{id}/book-out,
                // /refinery-orders/{id}/store, /missions/{id}/join) — without a squadron gate a
                // Logistician of squadron A could trigger the action on a squadron-B resource.
                // The inner check filters out POSTs whose path does not target a primary resource
                // id so create / bulk / administrative endpoints are not falsely flagged.
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
                // Skip endpoints that do not target a specific resource id in their path. The
                // condition is two-fold:
                //   * the method must accept a UUID @PathVariable (a primary-resource id), AND
                //   * the request mapping path must literally contain "{id}" (the canonical
                //     placeholder for the aggregate root's id; avoids false positives on
                //     administrative endpoints like POST /users/{userId}/ships where the path
                //     variable is a related user, not the aggregate id being mutated).
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
                // Accepted gate references (R3 narrowed the set back to one canonical name after
                // the shim was deleted; R6.a widened it to also accept the Spezialkommando
                // membership gate):
                //   - @ownerScopeService.canSee*/canEdit*/canSeeOrgUnit/canEditOrgUnit — the
                //     plan-aligned org-unit-scope check introduced in R2.c and the only accepted
                //     scope-resolver since the SquadronScopeService shim was deleted in R3;
                //   - @missionSecurityService.canManage*/canAccessParticipant/canChangeOwner —
                //     mission-aggregate gate that itself folds in canEditMission() for elevated
                //     authorities (see MissionSecurityService — squadron-scope-aware as of the
                //     Phase 6 follow-up);
                //   - @specialCommandSecurityService.canManageMembers — Spezialkommando
                //     membership gate (R5.b / SPEZIALKOMMANDO_PLAN.md §6.1): admin-or-Lead of
                //     the exact SK whose id sits in the path. A Lead of a different SK does
                //     not carry over, so the gate is per-aggregate-row just like the
                //     ownerScopeService.canEdit* family;
                //   - hasRole('ADMIN') alone — admin always passes the squadron filter, no extra
                //     scope check needed (MULTI_SQUADRON_PLAN.md section 1).
                boolean hasOwnerScope = value.contains("ownerScopeService");
                boolean hasMissionSecurity = value.contains("missionSecurityService");
                boolean hasSpecialCommandSecurity = value.contains("specialCommandSecurityService");
                // Epic #800 (REQ-ROLE-004): the delegated appointment authoriser is a per-org-unit
                // gate (it keys the verdict on the caller's rank on the exact unit in the path), so
                // it is an accepted scope gate exactly like the specialCommandSecurity bean.
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
   * Audit finding C-1 guard (2026-05-20 security audit): mission endpoints gated only by
   * {@code @PreAuthorize("@ownerScopeService.canSeeMission(#id)")} (without an additional {@code
   * isAuthenticated()} / {@code hasRole(...)} / {@code hasAuthority(...)} clause) are reachable by
   * anonymous callers for non-internal missions — {@link
   * de.greluc.krt.profit.basetool.backend.config.SecurityConfig} declares the matching paths as
   * {@code permitAll}. Any such endpoint that returns a {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.MissionDto}, a {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto} or a generic collection
   * of either MUST invoke one of the guest-redaction helpers ({@code cleanupMissionForGuest} /
   * {@code cleanupParticipantForGuest}) somewhere in its body; otherwise full participant PII
   * (email, real name, roles, permissions) is shipped to guests.
   *
   * <p>The rule fired on the original C-1 regression in {@code
   * MissionController.addParticipantPublic} and {@code MissionController.addParticipantSlim}, both
   * of which had the {@code canSeeMission} gate but skipped the redaction pass that {@code
   * getMissionById} / {@code getNextMission} already applied. Without this guard a future endpoint
   * added with the same gate would silently re-introduce the same leak.
   *
   * <p>The check is structural: it asserts the helper is referenced in the bytecode, NOT that the
   * call is conditional on {@code jwt == null}. The conditional branching is verified by the
   * per-endpoint unit tests. The intent of the ArchUnit rule is to catch the "I forgot the
   * redaction entirely" regression, which is the actual C-1 root cause.
   */
  @Test
  void anonymousReadableMissionEndpointsMustRedactGuestPii() {
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..backend.controller..")
        .and()
        .arePublic()
        .and(hasGuestVisibleCanSeeMissionPreAuthorize())
        .and(returnsMissionDtoOrMissionParticipantDtoOrCollection())
        .should(callOneOfTheGuestRedactionHelpers())
        .because(
            "Mission endpoints reachable by anonymous callers must apply cleanupMissionForGuest "
                + "or cleanupParticipantForGuest before returning — audit finding C-1: "
                + "addParticipantPublic / addParticipantSlim previously leaked full participant "
                + "emails and real names to anonymous callers because the redaction pass that "
                + "getMissionById / getNextMission already used was skipped on the write paths.")
        .check(CLASSES);
  }

  /**
   * Mission DTOs whose participant nesting carries PII (email, first/last name, roles). Used by
   * {@link #anonymousReadableMissionEndpointsMustRedactGuestPii} to recognise return shapes that
   * must go through guest-redaction before reaching an anonymous caller. {@code
   * MissionFinanceEntryDto} is included because it embeds {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto} directly — the audit
   * found this transitive leak (C-2) in {@code MissionFinanceEntryController.createFinanceEntry}.
   */
  private static final Set<String> MISSION_PII_CARRYING_DTOS =
      Set.of(
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto",
          "de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto");

  /**
   * Naming convention for helper methods that strip participant PII for anonymous / guest callers:
   * {@code cleanup<EntityName>ForGuest}. Examples in the codebase (the redaction helpers now live
   * in {@code MissionGuestRedactor}, called by the controllers): {@code
   * MissionGuestRedactor#cleanupMissionForGuest} (member-peer level), {@code
   * MissionGuestRedactor#cleanupOutsiderMissionForGuest} (strict outsider level), {@code
   * …#cleanupParticipantForGuest}. The ArchUnit rule recognises any call to a method matching this
   * pattern as a valid redaction call — so adding a new guest-reachable controller with its own
   * entity-specific redactor (named accordingly) does not require updating this test.
   *
   * @param name candidate method name
   * @return {@code true} iff {@code name} matches the {@code cleanup…ForGuest} convention
   */
  private static boolean isGuestRedactionHelperName(String name) {
    return name.startsWith("cleanup") && name.endsWith("ForGuest");
  }

  private static DescribedPredicate<JavaMethod> hasGuestVisibleCanSeeMissionPreAuthorize() {
    return new DescribedPredicate<JavaMethod>(
        "annotated with @PreAuthorize that gates on canSeeMission or canAccessParticipant"
            + " without an isAuthenticated/hasRole/hasAuthority clause") {
      @Override
      public boolean test(JavaMethod method) {
        if (!method.isAnnotatedWith(PRE_AUTHORIZE)) {
          return false;
        }
        JavaAnnotation<?> ann = method.getAnnotationOfType(PRE_AUTHORIZE);
        String value =
            ann.tryGetExplicitlyDeclaredProperty("value").map(Object::toString).orElse("");
        // {@code canAccessParticipant} returns true for any guest participant ({@code
        // p.getUser() == null}) — so an anonymous caller can reach the endpoint when the target
        // is a guest. The legacy participant endpoints (PUT /participants/{id}, check-in / -out,
        // payout-preference, DELETE /participants/{id}) all carry this gate and have shipped the
        // full MissionDto without redaction before the 2026-05-20 audit fix.
        if (!value.contains("canSeeMission") && !value.contains("canAccessParticipant")) {
          return false;
        }
        // Any of the following would force the caller to be authenticated, making jwt non-null
        // at runtime and the guest-redaction pass moot.
        return !value.contains("isAuthenticated()")
            && !value.contains("hasRole(")
            && !value.contains("hasAnyRole(")
            && !value.contains("hasAuthority(")
            && !value.contains("hasAnyAuthority(");
      }
    };
  }

  private static DescribedPredicate<JavaMethod>
      returnsMissionDtoOrMissionParticipantDtoOrCollection() {
    return new DescribedPredicate<JavaMethod>(
        "returns MissionDto / MissionParticipantDto, or a known generic wrapper of either") {
      @Override
      public boolean test(JavaMethod method) {
        JavaClass rawReturnType = method.getRawReturnType();
        if (MISSION_PII_CARRYING_DTOS.contains(rawReturnType.getFullName())) {
          return true;
        }
        JavaType returnType = method.getReturnType();
        if (!(returnType instanceof JavaParameterizedType parameterized)) {
          return false;
        }
        if (!ENTITY_GENERIC_WRAPPERS.contains(parameterized.toErasure().getFullName())) {
          return false;
        }
        for (JavaType arg : parameterized.getActualTypeArguments()) {
          if (MISSION_PII_CARRYING_DTOS.contains(arg.toErasure().getFullName())) {
            return true;
          }
        }
        return false;
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
        "call a cleanup…ForGuest redaction helper from its own body") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        boolean callsHelper =
            method.getMethodCallsFromSelf().stream()
                .map(call -> call.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        if (callsHelper) {
          return;
        }
        // Method references (e.g. `stream.map(this::cleanupParticipantForGuest)`) are compiled
        // into a synthetic invokedynamic call site whose target is reachable via the bootstrap.
        // ArchUnit exposes that as a separate access kind — fall back to the broader call set so
        // the rule does not false-positive on the slim endpoint's stream pattern.
        boolean referencesHelper =
            method.getAccessesFromSelf().stream()
                .map(access -> access.getTarget().getName())
                .anyMatch(ArchitectureTest::isGuestRedactionHelperName);
        if (referencesHelper) {
          return;
        }
        events.add(
            SimpleConditionEvent.violated(
                method,
                method.getFullName()
                    + " — anonymous callers reach this endpoint (PreAuthorize gates on"
                    + " canSeeMission without forcing authentication) and the return type carries"
                    + " participant PII, but the method body does not invoke any cleanup…ForGuest"
                    + " redaction helper. Full participant emails / real names / roles will leak to"
                    + " guests — see audit findings C-1 / C-2."));
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
          // Identity / global version — set by the persistence layer.
          "id",
          "version",
          "coreVersion",
          "scheduleVersion",
          "flagsVersion",
          // Owner — stamped from the authenticated principal in createMission.
          "owner",
          "ownerId",
          // Managers — managed via dedicated /missions/{id}/managers endpoints.
          "managers",
          // Owning squadron — derived from owner.squadron / scope (createMission) or parent
          // (addSubMission); never the body.
          "owningSquadron",
          "owningSquadronId",
          "squadronId",
          "squadron",
          // Owning OrgUnit — object-form references blocked (R6.a /
          // SPEZIALKOMMANDO_PLAN.md §8.3). The plain UUID variant {@code owningOrgUnitId} is
          // intentionally allowed as the picker output (R5.d.d); only the JPA-entity-form
          // references are server-managed and must never be bound from the request body.
          "owningOrgUnit",
          "creatingOrgUnit",
          "requestingOrgUnit",
          // Parent — for sub-missions, taken from the path variable; never the body.
          "parent",
          "parentId",
          // Sub-aggregate collections have their own write endpoints.
          "participants",
          "assignedUnits",
          "frequencies",
          "subMissions",
          "inventoryEntries",
          "refineryOrders",
          // Computed-on-response projections.
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
            // `version` is the optimistic-lock token on UpdateMissionRequest — legitimate there.
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

  // ---------------------------------------------------------------------------------
  // Multi-user signup concurrency guards
  // ---------------------------------------------------------------------------------

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
                // Skip nested helper types (e.g. a private result record inside a sync service):
                // the rule targets the top-level sync beans that actually talk to the Wiki, not
                // their inner value holders.
                if (javaClass.getEnclosingClass().isPresent()) {
                  return;
                }
                // Skip curated pure-helper beans that encode SC-Wiki domain knowledge but make no
                // HTTP call (see SCWIKI_CLIENT_INJECTION_EXEMPT_SIMPLE_NAMES) — they have no client
                // dependency to inject and belong beside the sync services that consume them.
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
        // R1 has ScWikiClient + ScWikiScheduler in the package; the latter satisfies the rule.
        // Allowing empty here keeps the guard intact when R9 / hypothetical refactors move all
        // sync services out of the package, leaving only the client behind.
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
    noMethods()
        .that()
        .areDeclaredInClassesThat(
            new DescribedPredicate<>("the bank ledger repositories") {
              @Override
              public boolean test(JavaClass input) {
                return ledgerRepositories.contains(input.getFullName());
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
