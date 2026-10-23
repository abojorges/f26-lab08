# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Written before any task was run. Baseline before any change: `mvn -B test`
green with 418 tests, `pytest` 137 passed. Java paths below are under
`<module>/src/main/java/edu/cmu/cs214/campusreserve/`.

### The tasks, in dependency order

#### T1. Modify a booking in the domain (CR clauses 1–4, history data for 5)

- **Behavior.** `BookingService.modifyBooking` lets the owner change the
  interval, the attendee count, or both, on an upcoming confirmed booking. The
  room stays the same.
  - Every `BookingRules.checkRequest` rule runs on the new values (clause 3).
  - Ownership, not cancelled, not started: the same three checks as
    `checkCancellable` (clause 1).
  - The room and the owner's own calendar are re-checked through
    `ConflictChecker`, with the booking itself filtered out by id, so a
    one-slot nudge is allowed. The per-user booking cap is not applied
    (clause 2).
  - A rejected modification leaves the booking, its history, and the waitlist
    unchanged. A modification that changes nothing succeeds and records
    nothing.
  - An interval change runs the existing FIFO `promoteWaitlist`. An
    attendees-only change runs no promotion (clause 4).
  - Each real change appends one history entry (when, old values, new values)
    to `Booking` (clause 5). No event yet (that is T2).
- **Modules.** `core-domain` only: `core/domain/Booking.java`,
  `core/service/BookingService.java`, new `core/domain/BookingChange.java`, new
  `core/domain/ModificationResult.java`, new test class
  `core/service/BookingServiceModifyTest.java`. The `BookingService`
  constructor and `Booking.confirmed(...)` keep their signatures (the CLI,
  `CampusReservePlatform`, and shipped tests call them).
- **Depends on.** Nothing.
- **Checkpoint.** `mvn -B -pl core-domain -am test`. Convinced when:
  - the core-domain block reads `Tests run: 167+N, Failures: 0, Errors: 0`,
    with N new tests named after CR lines, including: a booker at the cap can
    still modify; a clash with the booker's own booking in another room is
    refused; an attendees-only change promotes nobody; a rejected change leaves
    history empty;
  - `mvn -q -DskipTests compile` on the whole reactor succeeds, so `billing`,
    `cli`, and `http-api` still compile against the new `Booking`;
  - the rule 3 and rule 4 greps below come back clean.
- **Green but wrong.** The agent reuses `checkAskerLimits`
  (`BookingService.java:272`) for the own-calendar check. That method also
  applies the 5-booking cap, so if no test puts the booker at the cap, the gate
  stays green while clause 2 ("the cap … does not come into it") is broken.

#### T2. The `booking-modified` event and the owner's notification (CR 5 no-op part, 6, first half of 8)

- **Behavior.**
  - A real modification publishes `BookingModifiedEvent`: the core fields hold
    the booking as it is now, new fields hold the previous interval and
    attendee count. A no-op or a rejection publishes nothing.
  - The log line adds `previousStart`, `previousEnd`, `previousAttendees`, and
    `EventLogFormat.eventTypes()` lists `booking-modified`.
  - The owner gets a message naming both the old and the new details. Anyone
    promoted gets the promotion notice that already exists.
- **Modules.**
  - `core-domain`: new `core/events/BookingModifiedEvent.java`, the `permits`
    list in `DomainEvent`, one publish call in `modifyBooking`.
  - `storage`: `storage/EventLogFormat.java`.
  - `notifications`: `NotificationKind`, `templates/Placeholders`, new
    `templates/BookingModifiedTemplate.java`, `TemplateRegistry.standard()`,
    `NotificationService.valuesFor`.
  - New test classes in `storage` and `notifications`.
- **Depends on.** T1.
- **Why one task across three modules.** `DomainEvent` is sealed, and
  `NotificationKind.forEvent` and `NotificationService.valuesFor` switch over
  it exhaustively. Declaring the event breaks `notifications` at compile time,
  so splitting would leave the build red between tasks.
- **Checkpoint.** `mvn -B -pl storage,notifications -am test`. Convinced when:
  - the core-domain, storage, and notifications blocks all show `Failures: 0`
    with higher counts;
  - a notifications test asserts the old time and the new time both appear in
    the message and differ;
  - a storage test asserts the exact line: `start`/`end` hold the new
    interval, `previous*` the old one;
  - a closing `mvn -B clean test` is green.
- **Green but wrong.** `valuesFor` fills both the old-time and the new-time
  placeholders from `event.interval()`. The strict template renderer is
  satisfied, and a test that only checks the new time appears stays green while
  the owner never sees the old details.

#### T3. Analytics reads `booking-modified` (CR second half of 8)

- **Behavior.**
  - `events.py` accepts `booking-modified`.
  - `build_ledger` updates the existing record's `start`, `end`, and
    `attendees` in place, keeping `created_at`, `from_waitlist`, and
    `waitlist_entry_id`.
  - A modification for a booking the ledger never opened is collected the way
    orphan cancellations are, not crashed on.
  - Utilization counts a modified booking at its current length only.
- **Modules.** `analytics/campusreserve_analytics/events.py` and a new
  `analytics/tests/test_modification.py`. `utilization.py` and `revenue.py` are
  not touched.
- **Depends on.** T2 (the field names it parses).
- **Checkpoint.** From `analytics/`, `pytest`. Convinced when:
  - it reports `137+N passed`;
  - a booking created 10:00–12:00 and modified to 10:00–11:00 counts 60
    minutes, not 120 or 180;
  - a modified booking that came off the waitlist still counts as a promotion;
  - a booking moved to the next day appears under the new day only;
  - `git diff --stat` shows `revenue.py` and `utilization.py` unchanged.
- **Green but wrong.** The agent adds `booking-modified` to
  `HOLDING_EVENT_TYPES`, so the ledger builds a fresh record for it. Minutes
  come out right and the utilization test passes, but `from_waitlist` resets,
  so a modified promoted booking quietly stops counting as a promotion.

#### T4. `PATCH /bookings/{bookingId}` and history on the read surface (CR 5 read part, 7)

- **Behavior.**
  - `PATCH` takes an optional `start` with `end` or `minutes`, and/or an
    optional `attendees`. Owner only: an administrator gets 403, as on
    `DELETE`.
  - 200 returns the booking, its previous details, and any promotions.
    Refusals go through `ErrorMapping` (404, 403, 409, 400). Neither field
    given is a 400, and so is a `roomId` that differs from the booking's room.
  - Every booking view, `GET /bookings/{bookingId}` included, carries a
    `history` array.
- **Modules.** `http-api` only: `Router` (a `patch` method), `BookingsHandler`,
  `RequestContext` (an optional interval), `JsonViews`. The new test class uses
  the existing `ApiTestServer.method("PATCH", …)` helper, so no shipped test
  file is edited.
- **Depends on.** T1, and T2 for checking the owner's notification end to end.
- **Checkpoint.** `mvn -B -pl http-api -am test`. Convinced when:
  - the http-api block reads `Tests run: 78+N, Failures: 0`;
  - the owner shortens a booking and `GET` shows the new times and one history
    entry;
  - a modification into a taken slot returns 409 and `GET` still shows the
    original with empty history;
  - an administrator's `PATCH` on a member's booking returns 403;
  - a closing `mvn -B clean test` is green.
- **Green but wrong.** The handler copies `show`'s `auth.requireActingFor`
  (`BookingsHandler.java:77`), which lets administrators through, so an
  administrator can modify anyone's booking. Tests with only the owner and a
  stranger stay green, because the stranger gets 403 either way.

**CR clause 9, by hand after T2 and T4.** I edit the conventions files myself,
since they are what the agent is judged against: the event-type list in
`AGENTS.md` rule 6, the event-log paragraph in `ARCHITECTURE.md`, and a `PATCH`
row in the `ARCHITECTURE.md` HTTP table.

### AGENTS.md rules 3 and 4

T1's checkpoint catches both, with two greps run after its gate:
`git diff -U0 -- core-domain/src/main | grep '^+' | grep -E 'isBefore|isAfter|compareTo|overlaps'`
must come back empty (rule 3), and
`grep -rnE 'waitlist\.(save|remove)' --include='*.java' */src/main` must name
only `BookingService.java` (rule 4). T4's checkpoint runs the same two greps
over `http-api/src/main`.

### Out of scope, with reasons

- **`billing/`.** CR: "The price does not change … Billing is not part of this
  change request. Do not re-price, re-issue, or void and re-invoice."
- **`accounts/`.** CR: "Nothing in account management. Modification uses the
  token and ownership checks that already exist. Accounts does not change."
- **`cli/`.** CR: "Nothing in the CLI. The administrative commands render
  fields that already exist and do not need a new command."
- **`analytics/campusreserve_analytics/revenue.py`.** CR: "The revenue report
  is not part of this change … do not fork the reading … both reports go on
  reading the same fold of it."
- **Every module's `pom.xml`.** AGENTS.md rule 1: no new dependency edges.
- **Shipped tests.** AGENTS.md rule 7: new tests go in new files only.

---

## Milestone 2: The guardrail

### The recipe

Recipe A, the permission boundary. `.claude/settings.json` at the repository
root was generated from the `permissions` block of
`guardrails/settings-snippet.json`, with the placeholders replaced by the three
out-of-scope modules from Milestone 1. The agent's session runs with
`f26-lab08/` as its working directory, so the leading `/` anchors there.

- `Edit(/billing/**)`, `Edit(/accounts/**)`, `Edit(/cli/**)`: the out-of-scope
  modules.
- `Edit(/AGENTS.md)`, `Edit(/CLAUDE.md)`, `Edit(/CHANGE-REQUEST.md)`: the
  conventions the agent is judged against.
- `Edit(/.claude/**)`, `Edit(/guardrails/**)`: the agent cannot rewrite its own
  boundary with its file tools.
- `Bash(git push:*)`, `Bash(rm *)`, `Bash(sudo *)`.

**What it cannot catch.** The deny rules cover the agent's file tools, not
the shell: a `sed -i`, `cp`, or script run through Bash can still write under
`billing/`, and an in-bounds edit in `core-domain` (for example to `Booking`)
can change what `billing` charges without touching a denied file.

### Recipe C was tried first and did not bind

I first configured recipe C (`protected-trees.txt` = `billing`, `accounts`,
`cli`) and ran `lockdown.sh` at about 22:11:52 on Oct 22. Right after, the
`src/` directories were read-only and a shell `touch billing/src/probe.txt`
failed with "Permission denied". Within 2 to 5 seconds (directory change times
22:11:55–58), something on this machine outside the agent set every directory
in the three `src/` trees back to `drwx------`. The files stayed
`-r--r--r--`, but the Edit tool writes a new copy and renames it into place,
which needs only directory write permission: the probe edit to `Money.java`
went through at 22:13:35, and the file kept its read-only mode. Locking again
at 22:16:02 was undone again by 22:16:04–05. I restored `Money.java` from git,
removed the probe file, ran `unlock.sh`, restored `protected-trees.txt`, and
switched to recipe A, which the agent tool enforces itself.

### The captured block

Claude Code, Manual mode, session working directory `f26-lab08/`. A screenshot
of the same turn is kept for recitation.

```
User:  Use your Edit tool to add the comment line // guardrail probe at the top
       of billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java.

Agent: Read  billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java
       -> 1  package edu.cmu.cs214.campusreserve.billing;

Agent: Edit  billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java
       old:  package edu.cmu.cs214.campusreserve.billing;
       new:  // guardrail probe
             package edu.cmu.cs214.campusreserve.billing;
       -> Error: File is in a directory that is denied by your permission settings.

Agent: Bash  git status --short && git diff --stat -- billing && head -1 .../Money.java
       -> ?? .claude/
          ?? LAB08-PLAN.md
          package edu.cmu.cs214.campusreserve.billing;
```

The deny rule fired before Manual mode's approval prompt, so no prompt
appeared. `git status` shows nothing under `billing/`: the file is unchanged.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The task.** T1, modify a booking in the domain. It is the only task in
Milestone 1 with no unfinished dependencies.

### The takeover point

Written and committed before the run starts. I stop the agent and take over
the moment any one of these happens:

1. `git add -N . && git diff --stat` lists a file outside `core-domain/` (other
   than `PLAN.md`), or any existing file under `core-domain/src/test/`.
2. The diff adds `isBefore`, `isAfter`, `compareTo`, or `overlaps` on any line
   under `core-domain/src/main` (the rule 3 grep from Milestone 1 is not
   empty).
3. The agent proposes changing the `BookingService` constructor or the
   signature of `Booking.confirmed(...)`.
4. The core-domain gate is still red after the agent's second attempt at the
   same failure.

What I do then: interrupt the run, `git restore` the offending files (or
`git stash -u` the whole attempt), and write that piece myself. For trigger 2,
that means the self-exclusion as an id filter on `findActiveByRoom` and
`findActiveByUser`, handed to `ConflictChecker.hasConflict`. For trigger 4, I
read the first failing assertion and fix the code myself instead of prompting
again.

### The evidence

Task T1, run in Claude Code under the Milestone 2 permission rules. The gate
was green on the first attempt and no takeover trigger fired.

Gate, `mvn -B -pl core-domain -am test` (167 shipped tests plus 37 new):

```
[INFO] Tests run: 204, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Closing `mvn -B clean test`, after the task was finished:

```
[INFO] Tests run: 204, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The rest of T1's checkpoint: `mvn -q -DskipTests test-compile` over the whole
reactor printed nothing (billing, cli, and http-api still compile against the
new `Booking`); the rule 3 grep came back empty; the rule 4 grep names only
`BookingService.java`, with the same four call sites as shipped. `pytest` from
`analytics/`: `137 passed`, unchanged.

After settling the refuter's finding (two more tests, both probe files
deleted), a final `mvn -B clean test` was green again: core-domain
`Tests run: 206, Failures: 0, Errors: 0, Skipped: 0`, every other module as
above, `BUILD SUCCESS`; `pytest` `137 passed`.

### The diff

`git add -N . && git diff --stat`: 5 files changed, 677 insertions, 7
deletions, all under `core-domain/src/`.

| File | In T1's declared scope | What changed |
|---|---|---|
| `main/.../core/domain/Booking.java` (+23 −5) | Yes | New `history` component (`List<BookingChange>`, copied unmodifiable). `confirmed(...)` keeps its signature and starts with an empty history; `cancelled()` keeps the history; new `modified(newInterval, newAttendees, at)` returns a copy with one more history entry and the same id, room, owner, status, and creation time. |
| `main/.../core/domain/BookingChange.java` (new, 42) | Yes | One history entry: when, previous interval and attendees, new interval and attendees. Refuses to record a change that changes nothing. |
| `main/.../core/domain/ModificationResult.java` (new, 65) | Yes | Sealed result shaped like `CancellationResult`: `Modified(booking, previous, promotions)` with `changed()`, and `Rejected(reason, detail)` using existing `RejectionReason` values only. |
| `main/.../core/service/BookingService.java` (+86 −2) | Yes | New `modifyBooking(bookingId, requesterId, Optional<TimeInterval>, OptionalInt)` and a private `otherThan` helper. Constructor unchanged. Order: exists, `checkCancellable` (clause 1), no-op returns before any write (clause 5), room exists and `checkRequest` (clause 3), own calendar then room via `ConflictChecker` without the booking itself and with no cap check (clause 2), save, `promoteWaitlist` only if the interval changed (clause 4). |
| `test/.../core/service/BookingServiceModifyTest.java` (new, 486) | Yes | 37 tests from the run in one nested group per clause, using only the shipped `BookingServiceTestBase` and fixtures, plus 2 clause 4 tests added afterwards as evidence when settling the refuter's finding (below). |

Nothing outside `core-domain/` changed, no shipped test or test-support file
was touched, and neither the `BookingService` constructor nor
`Booking.confirmed(...)` changed signature. The one thing worth flagging:
`Booking`'s canonical constructor gained the `history` parameter, as the
history design calls for; its only caller is `Booking.java` itself, which the
whole-reactor compile confirms.

**The line I read hardest**, in `BookingService.java`:

```java
return held.stream().filter(other -> !other.id().equals(booking.id())).toList();
```

What I was checking for: that the booking being modified is dropped by its id,
not by comparing times; that this filtered list, and not a hand-written
comparison, goes into `conflictChecker.hasConflict`, so `TimeInterval.overlaps`
stays the only overlap test (rule 3); and that it is applied to both the
owner's calendar and the room, since missing either one makes a one-slot nudge
collide with itself (`nudgingByOneSlotIsAllowed` covers that).

### The refuter

A fresh Claude Code session in `f26-lab08/`, given `CHANGE-REQUEST.md`, the
T1 diff saved with `git add -N . && git diff`, and the scope line in the brief,
and nothing else from the run. The brief:

> You are reviewing one change to a Java room-booking platform. Your job is to
> break it, not to fix it.
>
> Use only these three inputs: `CHANGE-REQUEST.md`; `refuter/t1.diff`, the
> change under review; and this one-line scope: "T1: `BookingService.modifyBooking`
> lets the owner change the interval and/or attendee count of an upcoming
> booking in the same room, under every booking-request rule, re-checking the
> room and the owner's own calendar while ignoring the booking itself, with the
> per-user booking cap waived; a rejected modification changes nothing, a
> modification that changes nothing records nothing, and an interval change
> runs the existing first-in-first-out waitlist promotion. core-domain only; no
> event, notification, HTTP, or analytics yet." You may read the source under
> `core-domain/`. Do not open `PLAN.md`, `LAB08-PLAN.md`, or any other file in
> `refuter/`.
>
> Find inputs or call sequences where this change does the wrong thing against
> CHANGE-REQUEST.md clauses 1 to 5. Events, notifications, HTTP, analytics, and
> billing are outside this change. Write each suspected problem as a JUnit 5
> test in one new file, `RefuterProbeTest.java` in
> `core-domain/src/test/java/edu/cmu/cs214/campusreserve/core/service/`,
> extending `BookingServiceTestBase`, asserting what CHANGE-REQUEST.md says
> should happen; run `mvn -B -pl core-domain test -Dtest=RefuterProbeTest`; a
> finding counts only if its test fails. Places to start: a booker at the
> booking cap; a rejected modification leaving any trace; an attendee-only
> change or a lengthening when someone on the waitlist could already be
> promoted; boundary times; the booker's own bookings in other rooms;
> modifications that change nothing, written in different ways. Do not edit or
> delete any existing file, do not fix the code, do not commit. Report each
> finding with its test, the failing line, the CHANGE-REQUEST.md sentence it
> violates, and your confidence; list the claims that did not fail; end with
> the single strongest finding.

### The finding settled

The refuter wrote 30 probes in `RefuterProbeTest.java`: 26 passed and 4
failed, all for one reason, which was its strongest finding. **A modification
that only lengthens a booking still promotes a waiter whose slot was already
free.** Its setup: u-2 waits for 10:00–11:00 in r-1; that slot frees while
u-2 holds 10:00–11:00 in r-2, so the pass skips u-2; u-2 then cancels in r-2,
which runs only r-2's pass, leaving u-2 waiting on a free slot in r-1; then u-3
lengthens 16:00–17:00 to 16:00–18:00 in r-1, and the pass seats u-2.

**Reproduced** as our own new test,
`LengtheningFindingTest.aLengtheningPromotesNobodyEvenWithAWaiterLeftBehind`,
which asserts the refuter's reading of clause 4 ("Running the pass and
promoting nobody is the right outcome there"). Red run,
`mvn -B -pl core-domain test -Dtest=LengtheningFindingTest`:

```
[ERROR]   LengtheningFindingTest.aLengtheningPromotesNobodyEvenWithAWaiterLeftBehind:35 expected: <[]> but was: <[u-2]>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

**Dismissed** with evidence:

1. Clause 4 fixes the mechanism: "Time that a successful modification frees up
   triggers the same first in, first out waitlist promotion that a
   cancellation triggers today", and "The promotion pass runs when a
   successful modification changed the interval." A lengthening changes the
   interval, so the pass runs, and it is the cancellation's pass, which walks
   the room's whole waitlist. "Promoting nobody" is what that pass does when
   nobody is waiting on a slot that is already free, which is the case the
   clause describes ("frees nothing").
2. A run shows the shipped cancellation does exactly the same. From the same
   setup, `aCancellationInTheSameRoomSeatsTheSameWaiter` cancels u-3's
   unrelated booking in r-1 and u-2 is seated in 10:00–11:00, just as
   `aLengtheningRunsTheSamePassAndCanSeatAWaiterLeftBehind` shows for the
   lengthening. `mvn -B -pl core-domain test -Dtest='BookingServiceModifyTest*'`:
   `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0`.
3. The promotion keeps both rule 4 properties: first in, first out, and
   re-checked against the room and the waiter's own calendar, so it cannot
   double-book.

The stranded waiter comes from shipped behavior that predates T1: cancelling a
booking runs only that room's pass, so a waiter skipped in r-1 is not
reconsidered when their own blocker in r-2 goes away. That is outside this
change request. `LengtheningFindingTest.java` and the refuter's
`RefuterProbeTest.java` were deleted after their runs; neither is committed.
