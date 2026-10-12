import re

T = "D:/Coding/krt/wt-scope/backend/src/test/java/de/greluc/krt/profit/basetool/backend/"
HDR = open("D:/Coding/krt/wt-scope/backend/src/main/java/de/greluc/krt/profit/basetool/backend/scope/api/OwnerScopeService.java", encoding="utf-8").read().split("package ")[0]


def rd(p):
    return open(T + p, encoding="utf-8", newline="").read()


def wr(p, s):
    open(T + p, "w", encoding="utf-8", newline="").write(s)


def add_import(s, imp):
    line = "import %s;\n" % imp
    if line in s:
        return s
    m = re.search(r"^import [^\n]*\n", s, flags=re.M)
    return s[: m.start()] + line + s[m.start():]


def rep(s, a, b):
    assert s.count(a) == 1, a
    return s.replace(a, b)


s = rd("service/UserServiceTest.java")
i = s.index("  /**\n   * Pins that {@code updateUserDefaultPayoutPreference} returns the user")
s = s[:i].rstrip() + "\n}\n"
s = rep(
    s,
    "{@code updateUserDescription}, {@code\n * updateUserDefaultPayoutPreference}, {@code updateUserShareBlueprintsGlobally}) persist with\n * {@code saveAndFlush}",
    "{@code updateUserDescription}) persist with\n * {@code saveAndFlush}",
)
wr("service/UserServiceTest.java", s)

s = rd("service/UserServiceSyncTest.java")
i = s.index("  @Nested\n  class UpdateUserDefaultPayoutPreferenceTests {")
j = s.index("  @Nested\n  class FindByIdTests {")
s = s[:i] + s[j:]
s = rep(s, " *   <li>{@link UserService#updateUserDefaultPayoutPreference}.\n", "")
wr("service/UserServiceSyncTest.java", s)

s = rd("privacy/web/UserControllerTest.java")
i = s.index("  @Test\n  void getMyPayoutPreference_resolvesIdFromJwt_andReturnsPreferenceAndVersion() {")
j = s.index("  @Test\n  void updateReadAnnouncement_resolvesIdFromJwt_andForwardsAnnouncementId() {")
new = """  @Test
  void getMyPayoutPreference_resolvesIdFromJwt_andReturnsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 4L));

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(4L, result.version());
  }

  @Test
  void getMyPayoutPreference_returnsNullPreference_whenUserNeverChose() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(null, 0L));

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertNull(
        result.defaultPayoutPreference(), "no explicit choice surfaces as a null preference");
    assertEquals(0L, result.version());
  }

  @Test
  void updateMyPayoutPreference_resolvesIdFromJwt_andForwardsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyPayoutPreferenceRequest req =
        new UserController.MyPayoutPreferenceRequest(PayoutPreference.DONATE, 2L);
    when(memberPayoutSettings.update(CALLER_ID, PayoutPreference.DONATE, 2L))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 3L));

    UserController.MyPayoutPreferenceResponse result =
        controller.updateMyPayoutPreference(jwt, req);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(3L, result.version());
    verify(memberPayoutSettings).update(CALLER_ID, PayoutPreference.DONATE, 2L);
  }

  @Test
  void getMyBlueprintSharing_resolvesIdFromJwt_andReturnsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberBlueprintSharings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 4L));

    UserController.MyBlueprintSharingResponse result = controller.getMyBlueprintSharing(jwt);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(4L, result.version());
  }

  @Test
  void updateMyBlueprintSharing_resolvesIdFromJwt_andForwardsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyBlueprintSharingRequest req =
        new UserController.MyBlueprintSharingRequest(true, 2L);
    when(memberBlueprintSharings.update(CALLER_ID, true, 2L))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 3L));

    UserController.MyBlueprintSharingResponse result =
        controller.updateMyBlueprintSharing(jwt, req);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(3L, result.version());
    verify(memberBlueprintSharings).update(CALLER_ID, true, 2L);
  }

"""
s = s[:i] + new + s[j:]
s = rep(s, "  @Mock private UserSyncService userSyncService;\n", "  @Mock private UserSyncService userSyncService;\n  @Mock private MemberPayoutSettings memberPayoutSettings;\n  @Mock private MemberBlueprintSharings memberBlueprintSharings;\n")
s = add_import(s, "de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings")
s = add_import(s, "de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings")
wr("privacy/web/UserControllerTest.java", s)

s = rd("MissionServiceTest.java")
s = rep(s, "    user.setDefaultPayoutPreference(PayoutPreference.DONATE);\n", "")
s = rep(s, "    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));\n    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());\n",
        "    when(userRepository.findPlainById(userId)).thenReturn(Optional.of(user));\n    when(memberPayoutSettings.defaultFor(userId)).thenReturn(Optional.of(PayoutPreference.DONATE));\n    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());\n")
wr("MissionServiceTest.java", s)

s = rd("blueprint/internal/PersonalBlueprintOverviewServiceTest.java")
s = rep(s, "  @Mock private UserRepository userRepository;\n", "  @Mock private UserRepository userRepository;\n  @Mock private MemberBlueprintSharings memberBlueprintSharings;\n")
s = rep(s, "            userRepository,\n            new BlueprintVariantFamilyResolver(", "            userRepository,\n            memberBlueprintSharings,\n            new BlueprintVariantFamilyResolver(")
s = s.replace("userRepository.findIdsBySharingBlueprintsGlobally()", "memberBlueprintSharings.userIdsSharingGlobally()")
s = add_import(s, "de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings")
wr("blueprint/internal/PersonalBlueprintOverviewServiceTest.java", s)

s = rd("joborder/internal/JobOrderItemBlueprintOwnersServiceTest.java")
s = rep(s, "  @Mock private UserRepository userRepository;\n", "  @Mock private UserRepository userRepository;\n  @Mock private MemberBlueprintSharings memberBlueprintSharings;\n")
s = rep(s, "            userRepository,\n            new BlueprintVariantFamilyResolver(", "            userRepository,\n            memberBlueprintSharings,\n            new BlueprintVariantFamilyResolver(")
s = s.replace("userRepository.findIdsBySharingBlueprintsGlobally()", "memberBlueprintSharings.userIdsSharingGlobally()")
s = add_import(s, "de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings")
wr("joborder/internal/JobOrderItemBlueprintOwnersServiceTest.java", s)
