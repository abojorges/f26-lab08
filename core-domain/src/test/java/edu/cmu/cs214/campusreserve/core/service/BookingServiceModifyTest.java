package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
import edu.cmu.cs214.campusreserve.core.domain.CancellationResult;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.policy.ConflictChecker;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Booking modification, one nested group per clause of CHANGE-REQUEST.md. */
class BookingServiceModifyTest extends BookingServiceTestBase {

    private static final TimeInterval SLOT = Fixtures.interval("10:00", "11:00");
    private static final TimeInterval AFTERNOON = Fixtures.interval("13:00", "14:00");

    private ModificationResult move(String bookingId, String requesterId, TimeInterval to) {
        return service.modifyBooking(bookingId, requesterId, Optional.of(to), OptionalInt.empty());
    }

    private ModificationResult resize(String bookingId, String requesterId, int attendees) {
        return service.modifyBooking(bookingId, requesterId, Optional.empty(), OptionalInt.of(attendees));
    }

    private Booking stored(String bookingId) {
        return service.findBooking(bookingId).orElseThrow();
    }

    private List<String> waiting(String roomId) {
        return service.waitlistForRoom(roomId).stream().map(WaitlistEntry::userId).toList();
    }

    private void assertRefusedAndUnchanged(RejectionReason expected, ModificationResult result, Booking before) {
        assertEquals(Optional.of(expected), result.rejectionReason());
        assertEquals(before, stored(before.id()));
    }

    @Nested
    @DisplayName("clause 1: only the booker, only while upcoming")
    class OwnershipAndTiming {

        @Test
        void theOwnerMovesAnUpcomingBookingWithinTheSameRoom() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            Booking before = stored(id);

            ModificationResult result = move(id, "u-1", AFTERNOON);

            assertTrue(result.isModified());
            Booking after = stored(id);
            assertEquals(AFTERNOON, after.interval());
            assertEquals("r-1", after.roomId());
            assertEquals("u-1", after.userId());
            assertEquals(BookingStatus.CONFIRMED, after.status());
            assertEquals(before.createdAt(), after.createdAt());
            assertEquals(1, bookings.count());
        }

        @Test
        void theOwnerChangesOnlyTheAttendeeCount() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            assertTrue(resize(id, "u-1", 8).isModified());
            assertEquals(8, stored(id).attendees());
            assertEquals(SLOT, stored(id).interval());
        }

        @Test
        void theOwnerChangesBothAtOnce() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            ModificationResult result =
                service.modifyBooking(id, "u-1", Optional.of(AFTERNOON), OptionalInt.of(6));
            assertTrue(result.isModified());
            assertEquals(AFTERNOON, stored(id).interval());
            assertEquals(6, stored(id).attendees());
        }

        @Test
        void someoneElseCannotModifyIt() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            Booking before = stored(id);
            assertRefusedAndUnchanged(RejectionReason.NOT_BOOKING_OWNER, move(id, "u-2", AFTERNOON), before);
        }

        @Test
        void aCancelledBookingCannotBeModified() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            service.cancelBooking(id, "u-1");
            Booking before = stored(id);
            assertRefusedAndUnchanged(
                RejectionReason.BOOKING_ALREADY_CANCELLED, move(id, "u-1", AFTERNOON), before);
        }

        @Test
        void aBookingThatHasStartedCannotBeModified() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            Booking before = stored(id);
            clock.setTo("2026-10-20T10:30:00Z");
            assertRefusedAndUnchanged(
                RejectionReason.BOOKING_ALREADY_STARTED, move(id, "u-1", AFTERNOON), before);
        }

        @Test
        void aBookingStartingThisMinuteHasStarted() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            Booking before = stored(id);
            clock.setTo("2026-10-20T10:00:00Z");
            assertRefusedAndUnchanged(
                RejectionReason.BOOKING_ALREADY_STARTED, resize(id, "u-1", 5), before);
        }

        @Test
        void anUnknownBookingIsRefused() {
            assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND),
                move("bk-missing", "u-1", AFTERNOON).rejectionReason());
        }
    }

    @Nested
    @DisplayName("clause 2: the conflict re-check ignores the booking itself")
    class ConflictRecheck {

        @Test
        void nudgingByOneSlotIsAllowed() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            TimeInterval nudged = Fixtures.interval("10:15", "11:15");
            assertTrue(move(id, "u-1", nudged).isModified());
            assertEquals(nudged, stored(id).interval());
        }

        @Test
        void aTakenSlotIsRefusedAndTheOriginalStands() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            bookAndGetId("r-1", "u-2", AFTERNOON);
            Booking before = stored(id);

            ModificationResult result = move(id, "u-1", Fixtures.interval("13:30", "14:30"));

            assertRefusedAndUnchanged(RejectionReason.ROOM_UNAVAILABLE, result, before);
            assertTrue(stored(id).history().isEmpty());
            assertEquals(0, waitlist.count());
        }

        @Test
        void aRejectedModificationChangesNothingAtAll() {
            String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
            book("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
            bookAndGetId("r-1", "u-3", AFTERNOON);
            List<Booking> bookingsBefore = bookings.findAll();
            List<WaitlistEntry> waitlistBefore = waitlist.findAll();

            ModificationResult result = move(id, "u-1", Fixtures.interval("11:00", "13:30"));

            assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
            assertEquals(bookingsBefore, bookings.findAll());
            assertEquals(waitlistBefore, waitlist.findAll());
            assertTrue(result.promotions().isEmpty());
        }

        @Test
        void backToBackWithOtherBookingsIsNotACollision() {
            bookAndGetId("r-1", "u-2", Fixtures.interval("09:00", "10:00"));
            bookAndGetId("r-1", "u-3", Fixtures.interval("11:00", "12:00"));
            String id = bookAndGetId("r-1", "u-1", AFTERNOON);
            assertTrue(move(id, "u-1", SLOT).isModified());
        }

        @Test
        void theBookersOwnCalendarIsCheckedInEveryRoom() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            bookAndGetId("r-2", "u-1", AFTERNOON);
            Booking before = stored(id);
            assertRefusedAndUnchanged(RejectionReason.USER_DOUBLE_BOOKED, move(id, "u-1", AFTERNOON), before);
        }

        @Test
        void aBookerAtTheCapCanStillModify() {
            build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(2));
            String id = bookAndGetId("r-1", "u-1", SLOT);
            bookAndGetId("r-2", "u-1", AFTERNOON);
            assertEquals(Optional.of(RejectionReason.USER_BOOKING_LIMIT_REACHED),
                book("r-1", "u-1", Fixtures.interval("15:00", "16:00")).rejectionReason());

            ModificationResult result = move(id, "u-1", Fixtures.interval("11:00", "12:00"));

            assertTrue(result.isModified());
            assertEquals(Fixtures.interval("11:00", "12:00"), stored(id).interval());
        }
    }

    @Nested
    @DisplayName("clause 3: every booking rule still applies")
    class RulesStillApply {

        private String id;
        private Booking before;

        private void bookTheSlot() {
            id = bookAndGetId("r-1", "u-1", SLOT);
            before = stored(id);
        }

        @Test
        void theMinimumDuration() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.INTERVAL_TOO_SHORT,
                move(id, "u-1", Fixtures.interval("10:00", "10:15")), before);
        }

        @Test
        void theMaximumDurationForTheRoom() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.INTERVAL_TOO_LONG,
                move(id, "u-1", Fixtures.interval("10:00", "13:15")), before);
        }

        @Test
        void slotAlignment() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.INTERVAL_NOT_ALIGNED,
                move(id, "u-1", Fixtures.interval("10:05", "11:05")), before);
        }

        @Test
        void openingHours() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.OUTSIDE_OPENING_HOURS,
                move(id, "u-1", Fixtures.interval("05:00", "06:00")), before);
        }

        @Test
        void stayingInsideOneUtcDay() {
            bookTheSlot();
            TimeInterval acrossMidnight = TimeInterval.parse("2026-10-20T23:00:00Z", "2026-10-21T00:30:00Z");
            assertRefusedAndUnchanged(RejectionReason.INTERVAL_SPANS_DAYS,
                move(id, "u-1", acrossMidnight), before);
        }

        @Test
        void theRoomsCapacityAgainstTheNewAttendeeCount() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.ATTENDEES_EXCEED_CAPACITY, resize(id, "u-1", 11), before);
        }

        @Test
        void theAdvanceWindow() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.TOO_FAR_IN_ADVANCE,
                move(id, "u-1", Fixtures.intervalOn("2026-11-10", "10:00", "11:00")), before);
        }

        @Test
        void startingInTheFuture() {
            bookTheSlot();
            assertRefusedAndUnchanged(RejectionReason.INTERVAL_IN_PAST,
                move(id, "u-1", Fixtures.intervalOn("2026-10-19", "08:00", "09:00")), before);
        }

        @Test
        void theRoomStillBeingInService() {
            bookTheSlot();
            addRoom(Fixtures.studyRoom("r-1", 10).withActive(false));
            assertRefusedAndUnchanged(RejectionReason.ROOM_INACTIVE, resize(id, "u-1", 5), before);
        }

        @Test
        void aNonPositiveAttendeeCountIsACallerMistake() {
            bookTheSlot();
            assertThrows(IllegalArgumentException.class, () -> resize(id, "u-1", 0));
            assertEquals(before, stored(id));
        }
    }

    @Nested
    @DisplayName("clause 4: freed time goes to the waitlist")
    class FreedTime {

        @Test
        void movingAwayPromotesTheFirstWaiterForTheFreedSlot() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            book("r-1", "u-2", SLOT);
            book("r-1", "u-3", SLOT);

            ModificationResult result = move(id, "u-1", AFTERNOON);

            assertEquals(1, result.promotions().size());
            CancellationResult.Promotion promotion = result.promotions().get(0);
            assertEquals("u-2", promotion.booking().userId());
            assertEquals(SLOT, promotion.booking().interval());
            assertEquals(List.of("u-3"), waiting("r-1"));
            assertFalse(new ConflictChecker().containsMutualConflict(service.bookingsForRoom("r-1")));
        }

        @Test
        void shorteningFreesTheTailForAWaiter() {
            String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
            book("r-1", "u-2", Fixtures.interval("11:00", "12:00"));

            ModificationResult result = move(id, "u-1", SLOT);

            assertEquals(1, result.promotions().size());
            assertEquals(Fixtures.interval("11:00", "12:00"), result.promotions().get(0).booking().interval());
            assertTrue(waiting("r-1").isEmpty());
        }

        @Test
        void lengtheningFreesNothingAndPromotesNobody() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            book("r-1", "u-2", SLOT);

            ModificationResult result = move(id, "u-1", Fixtures.interval("10:00", "12:00"));

            assertTrue(result.isModified());
            assertTrue(result.promotions().isEmpty());
            assertEquals(List.of("u-2"), waiting("r-1"));
        }

        /**
         * Leaves u-2 waiting for a slot in r-1 that is free, with nothing having
         * walked the waitlist since it freed up, and returns a booking u-3 holds
         * later the same day in r-1.
         */
        private String leaveAPromotableWaiterBehind() {
            build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
            String held = bookAndGetId("r-1", "u-1", SLOT);
            assertTrue(book("r-1", "u-2", SLOT).isWaitlisted());
            String elsewhere = bookAndGetId("r-2", "u-2", Fixtures.interval("14:00", "15:00"));
            assertTrue(service.cancelBooking(held, "u-1").promotions().isEmpty());
            service.cancelBooking(elsewhere, "u-2");
            assertEquals(List.of("u-2"), waiting("r-1"));
            return bookAndGetId("r-1", "u-3", Fixtures.interval("16:00", "17:00"));
        }

        @Test
        void anAttendeeOnlyChangeStartsNoPromotion() {
            String id = leaveAPromotableWaiterBehind();

            ModificationResult result = resize(id, "u-3", 6);

            assertTrue(result.isModified());
            assertTrue(result.promotions().isEmpty());
            assertEquals(List.of("u-2"), waiting("r-1"));
        }

        @Test
        void anIntervalChangeRunsThePromotionPass() {
            String id = leaveAPromotableWaiterBehind();

            ModificationResult result = move(id, "u-3", Fixtures.interval("16:15", "17:15"));

            assertEquals(1, result.promotions().size());
            assertEquals("u-2", result.promotions().get(0).booking().userId());
            assertTrue(waiting("r-1").isEmpty());
        }

        /**
         * The pass runs whenever the interval changed, and it is the same pass a
         * cancellation runs, so a lengthening seats a waiter left behind earlier.
         * The next test shows a cancellation in the same room does the same.
         */
        @Test
        void aLengtheningRunsTheSamePassAndCanSeatAWaiterLeftBehind() {
            String id = leaveAPromotableWaiterBehind();

            ModificationResult result = move(id, "u-3", Fixtures.interval("16:00", "18:00"));

            assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
            assertEquals(SLOT, result.promotions().get(0).booking().interval());
        }

        @Test
        void aCancellationInTheSameRoomSeatsTheSameWaiter() {
            String id = leaveAPromotableWaiterBehind();

            CancellationResult result = service.cancelBooking(id, "u-3");

            assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
            assertEquals(SLOT, result.promotions().get(0).booking().interval());
        }
    }

    @Nested
    @DisplayName("clause 5: the booking keeps its modification history")
    class History {

        @Test
        void aNewBookingHasNoHistory() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            assertTrue(stored(id).history().isEmpty());
        }

        @Test
        void eachRealChangeRecordsWhatChangedAndWhen() {
            String id = bookAndGetId("r-1", "u-1", SLOT);

            move(id, "u-1", AFTERNOON);

            List<BookingChange> history = stored(id).history();
            assertEquals(1, history.size());
            BookingChange change = history.get(0);
            assertEquals(Instant.parse(Fixtures.NOW), change.modifiedAt());
            assertEquals(SLOT, change.previousInterval());
            assertEquals(4, change.previousAttendees());
            assertEquals(AFTERNOON, change.newInterval());
            assertEquals(4, change.newAttendees());
            assertTrue(change.changedInterval());
            assertFalse(change.changedAttendees());
        }

        @Test
        void theHistoryKeepsEveryChangeInOrder() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            move(id, "u-1", AFTERNOON);
            clock.advanceMinutes(30);
            resize(id, "u-1", 6);

            List<BookingChange> history = stored(id).history();
            assertEquals(2, history.size());
            assertEquals(SLOT, history.get(0).previousInterval());
            assertEquals(AFTERNOON, history.get(1).previousInterval());
            assertEquals(4, history.get(1).previousAttendees());
            assertEquals(6, history.get(1).newAttendees());
            assertEquals(Instant.parse("2026-10-19T09:30:00Z"), history.get(1).modifiedAt());
        }

        @Test
        void aModificationThatChangesNothingSucceedsAndRecordsNothing() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            Booking before = stored(id);

            ModificationResult result = service.modifyBooking(id, "u-1", Optional.of(SLOT), OptionalInt.of(4));

            assertTrue(result.isModified());
            assertFalse(((ModificationResult.Modified) result).changed());
            assertEquals(before, stored(id));
            assertTrue(stored(id).history().isEmpty());
        }

        @Test
        void theSameIntervalSpelledDifferentlyIsStillNoChange() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            TimeInterval sameSlot = TimeInterval.ofMinutes(SLOT.start(), 60);

            ModificationResult result = move(id, "u-1", sameSlot);

            assertFalse(((ModificationResult.Modified) result).changed());
            assertTrue(stored(id).history().isEmpty());
        }

        @Test
        void aChangeThatChangesNothingStillNeedsTheOwner() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), move(id, "u-2", SLOT).rejectionReason());
        }

        @Test
        void cancellingAModifiedBookingKeepsItsHistory() {
            String id = bookAndGetId("r-1", "u-1", SLOT);
            move(id, "u-1", AFTERNOON);
            service.cancelBooking(id, "u-1");
            assertEquals(BookingStatus.CANCELLED, stored(id).status());
            assertEquals(1, stored(id).history().size());
        }

        @Test
        void aRecordedChangeHasToChangeSomething() {
            Instant at = Instant.parse(Fixtures.NOW);
            assertThrows(IllegalArgumentException.class, () -> new BookingChange(at, SLOT, 4, SLOT, 4));
        }
    }
}
