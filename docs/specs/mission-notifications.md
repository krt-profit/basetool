> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-10.
> **Owner area:** MISSION · **Related ADRs:** [ADR-0245](../adr/0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)

# Mission and operation notifications

## Context & goal

Issue #2414 extends the notification engine (`notifications.md`, REQ-NOTIF-024…028) to the
Einsätze and Operationen. Every notice tells somebody **other than the actor** about something that
affects them or that they now have to act on, and that they used to find only by opening the page
again. Each is published after the commit (REQ-NOTIF-002), carries no user free text beyond the
mission's or operation's own name and its meeting point, has a seeded, admin-editable rule, and links
to the mission or operation (`NotificationPageController#targetOf`; a deleted mission has no link).
Each can be muted by the member (REQ-NOTIF-027).

## Requirements

### REQ-MISSION-021 — A moved, cancelled or deleted Einsatz tells its participants

`MissionService#updateScheduleSection` and `#updateMission` publish `MISSION_RESCHEDULED` when the
meeting time or the planned start **actually changes** (old → new time of the one that moved);
`#updateCoreSection` and `#updateMission` publish `MISSION_CANCELLED` on the transition to
`CANCELLED`; `#deleteMission` publishes `MISSION_DELETED`, listing the participants captured before
the delete (`EVENT_RECIPIENTS`), because the mission is gone. The default rules notify the mission's
participants (`MISSION_PARTICIPANTS`) and exclude the actor. Only the latest change stays: a reschedule
clears the earlier reschedule and reminder notices, a cancellation and a deletion clear every open
notice of the mission. A reschedule resets both reminder markers.

### REQ-MISSION-022 — Reminders 24 hours and one hour before the Einsatz

`MissionReminderNoticeProducer` (a `TimedNoticeProducer`, REQ-NOTIF-026) raises `MISSION_REMINDER_DUE`
for every registered participant of a `PLANNED` mission whose meeting time (planned start without one)
lies in the hour after the 24-hour mark, and again in the last hour before it. The mission's
`reminder_24h_sent_at` / `reminder_1h_sent_at` marker is set in the same transaction that publishes the
events, so each fires at most once; editing the time resets both. The notice names the mission, the
lead (`24 h` / `1 h`), the time, the Treffpunkt and the participant's planned Funktion, so it is
published per participant to `EVENT_RECIPIENT`. A mission planned for the same day gets no 24-hour
reminder that would misstate its lead.

### REQ-MISSION-023 — Check-in open

The transition to `ACTIVE` publishes `MISSION_STARTED`; the default rule notifies the participants who
have not checked in yet (`MISSION_PARTICIPANTS`, narrowed by the event), and the notice says that a late
check-in lowers the payout share. The participant's own check-in publishes `MISSION_CHECKED_IN`, which
clears **that participant's** `MISSION_CHECKIN_OPEN` notice only (REQ-NOTIF-025).

### REQ-MISSION-024 — Added to or removed from an Einsatz by somebody else

`MissionParticipantService#addParticipant` publishes `MISSION_PARTICIPANT_ADDED` and `#removeParticipant`
publishes `MISSION_PARTICIPANT_REMOVED` when the acting member is not the participant (a guest has no
inbox). The add notice names the mission, its start, the actor and the payout choice stamped on the
member; removal clears that member's add, reminder and check-in notices, an add clears a removal notice.

### REQ-MISSION-025 — An Einsatz that was never ended

`MissionNotificationPublisher#statusChanged` publishes `MISSION_NEVER_ENDED` the moment a mission becomes
`COMPLETED` without an `actualEndTime`; `MissionNeverEndedNoticeProducer` does the same for `ACTIVE` and
`COMPLETED` missions whose planned end is more than `app.missions.notices.overdue-after` (default `PT6H`)
past without one. The default rule notifies the mission's owner and co-managers (`MISSION_LEADERSHIP`);
`never_ended_notified_at` makes it fire once. Recording the end publishes `MISSION_END_RECORDED`, which
clears the notice and the open check-in notices; moving the planned end re-arms the marker.

### REQ-MISSION-026 — Responsibility handed to a member

`MISSION_RESPONSIBILITY_ASSIGNED` notifies the member who became the owner (`updateMissionOwner`), a
co-manager (`addManager`), the party lead (`setPartyLead` with a user) or the responsible user of a unit
(`addUnitToMission`, `updateMissionUnit`), naming the role through the coded parameter `roleCode`
(REQ-NOTIF-028). Nothing is sent when the member made the change themselves or the value did not change.

### REQ-MISSION-027 — A participant dropped out

`#removeParticipant` publishes `MISSION_PARTICIPANT_LEFT` when the acting member is the participant and
they held a crew slot or a planned Funktion, or the start is less than 24 hours away. The default rule
notifies the mission leadership and names the freed slot or Funktion. The leaver's own notices are cleared.

### REQ-MISSION-028 — A payout was paid out

`OperationPayoutService#setPayoutStatusWithinTransaction` publishes `OPERATION_PAYOUT_MARKED` on a real
change to paid out and `OPERATION_PAYOUT_UNMARKED` on a real change back. The default rule notifies the
participant (`EVENT_RECIPIENT`, a guest or a deleted account has nobody to tell) with the operation, the
payout amount, the donation, the fee and the actor; taking the mark back clears that notice. The last
open payout also clears the managers' completion notice (REQ-MISSION-029).

### REQ-MISSION-029 — An operation is complete and its payouts are due

`OperationService#updateOperation` to `COMPLETED` publishes `OPERATION_COMPLETED` — with the owning unit
as `RESPONSIBLE`, notifying its Einsatzmanager and officers — or `OPERATION_COMPLETED_UNOWNED`, notifying
every officer (the fallback when there is no unit). The notice names the operation, the total and the
open amount, how many payouts are paid out, and how many missions are unfinished.

**Acceptance (REQ-MISSION-021…029)**

- [x] Each event reaches the recipients its seeded rule names and never the actor.
- [x] A reschedule, cancellation, deletion, check-in, removal, end, payout mark and its reversal clear the
  notices the requirement names, for the right members.
- [x] A reminder, a never-ended notice fires once and again after its time is edited; a mission outside
  the windows, cancelled or already started gets none.
- [x] A change that does not change anything, or that the member made themselves, notifies nobody.
- [x] The default rules exist, are enabled, and use the intended selector kinds.

**Enforced by:** `MissionNotificationPublisherTest`, `MissionNoticeIntegrationTest`,
`OperationPayoutServiceTest` (`SetPayoutStatusTests`), `SeededNotificationRulesIntegrationTest`,
`NotificationPageControllerTest` (`targetOf_…`) · **Code:** `mission/api/events/MissionNotices`,
`operation/api/events/OperationNotices`, `mission/internal/MissionNotificationPublisher`,
`mission/internal/MissionReminderNoticeProducer`, `mission/internal/MissionNeverEndedNoticeProducer`,
`operation/internal/OperationPayoutService`, `operation/internal/OperationService`,
`V272__add_mission_notification_markers.sql`, `V273__seed_mission_and_operation_notification_rules.sql`
· **Issues:** #2414
