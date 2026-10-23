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

**The evidence.** Name the task from Milestone 1. Paste the result line of its
gate command (run it without `-q` so the line is in the output) and the result
line of one `mvn -B clean test` run after the task was finished. The module
gate cannot see a cross-module compile break on a warm tree. A cold build can,
and so can CI.

**The diff.** The files the agent changed, and how each one maps to that task's
declared scope (plain `git diff` leaves out new files, but
`git add -N . && git diff --stat` includes them). Note anything it touched that
you did not expect, including any shipped test. Then quote the one line you
read hardest and say what you were checking for.

**The refuter.** What you gave it and what you asked it to do (paste the
brief). Then the one finding you settled, and how (the new failing test with
its red run, or the evidence that dismissed it). If nothing it reported held
up, say how you checked its strongest claim.
