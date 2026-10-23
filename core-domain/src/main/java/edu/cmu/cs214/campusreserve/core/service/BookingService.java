package edu.cmu.cs214.campusreserve.core.service;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingOutcome;
import edu.cmu.cs214.campusreserve.core.domain.BookingRequest;
import edu.cmu.cs214.campusreserve.core.domain.CancellationResult;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.Room;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.events.BookingCancelledEvent;
import edu.cmu.cs214.campusreserve.core.events.BookingCreatedEvent;
import edu.cmu.cs214.campusreserve.core.events.DomainEvent;
import edu.cmu.cs214.campusreserve.core.events.WaitlistPromotedEvent;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.policy.BookingRules;
import edu.cmu.cs214.campusreserve.core.policy.ConflictChecker;
import edu.cmu.cs214.campusreserve.core.ports.BookingStore;
import edu.cmu.cs214.campusreserve.core.ports.EventPublisher;
import edu.cmu.cs214.campusreserve.core.ports.IdGenerator;
import edu.cmu.cs214.campusreserve.core.ports.RoomStore;
import edu.cmu.cs214.campusreserve.core.ports.UserStore;
import edu.cmu.cs214.campusreserve.core.ports.WaitlistStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The booking flow: request a room, modify or cancel a booking, and let the
 * waitlist move when time frees up.
 *
 * <p>This class is the only writer of waitlist entries in the platform. Anything
 * that needs a waitlist change calls a method here rather than reaching for
 * {@link WaitlistStore} itself, which keeps first in, first out promotion in one
 * place along with the conflict re-check that goes with it.
 */
public final class BookingService {

    private static final String BOOKING_ID_PREFIX = "bk";
    private static final String WAITLIST_ID_PREFIX = "wl";

    private final BookingStore bookings;
    private final RoomStore rooms;
    private final UserStore users;
    private final WaitlistStore waitlist;
    private final EventPublisher events;
    private final IdGenerator ids;
    private final BookingRules rules;
    private final ConflictChecker conflictChecker;
    private final Clock clock;

    public BookingService(
        BookingStore bookings,
        RoomStore rooms,
        UserStore users,
        WaitlistStore waitlist,
        EventPublisher events,
        IdGenerator ids,
        BookingPolicy policy,
        Clock clock) {
        this.bookings = Objects.requireNonNull(bookings, "bookings");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.users = Objects.requireNonNull(users, "users");
        this.waitlist = Objects.requireNonNull(waitlist, "waitlist");
        this.events = Objects.requireNonNull(events, "events");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rules = new BookingRules(Objects.requireNonNull(policy, "policy"), clock);
        this.conflictChecker = new ConflictChecker();
    }

    /** The limits this service enforces. */
    public BookingPolicy policy() {
        return rules.policy();
    }

    /**
     * Asks for a room.
     *
     * <p>The order is fixed: the room and the user have to exist, the request has
     * to satisfy the booking rules, the asker has to be under their own limits,
     * and only then does the room's calendar decide. A taken slot sends the asker
     * to the waitlist when the policy allows one, and is refused otherwise.
     */
    public BookingOutcome requestBooking(BookingRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Room> room = rooms.findById(request.roomId());
        if (room.isEmpty()) {
            return BookingOutcome.rejected(RejectionReason.ROOM_NOT_FOUND);
        }
        if (!users.exists(request.userId())) {
            return BookingOutcome.rejected(RejectionReason.USER_NOT_FOUND);
        }

        Optional<RejectionReason> ruleFailure = rules.checkRequest(request, room.get());
        if (ruleFailure.isPresent()) {
            return BookingOutcome.rejected(ruleFailure.get());
        }

        Optional<RejectionReason> askerFailure = checkAskerLimits(request);
        if (askerFailure.isPresent()) {
            return BookingOutcome.rejected(askerFailure.get());
        }

        List<Booking> roomBookings = bookings.findActiveByRoom(request.roomId());
        if (conflictChecker.hasConflict(roomBookings, request.interval())) {
            return joinWaitlist(request);
        }

        Booking booking = Booking.confirmed(
            ids.nextId(BOOKING_ID_PREFIX),
            request.roomId(),
            request.userId(),
            request.interval(),
            request.attendees(),
            now());
        bookings.save(booking);
        publish(BookingCreatedEvent.of(booking, now()));
        return new BookingOutcome.Confirmed(booking);
    }

    /**
     * Cancels a booking on behalf of its owner, then walks the room's waitlist in
     * arrival order and promotes every entry that now fits.
     */
    public CancellationResult cancelBooking(String bookingId, String requesterId) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(requesterId, "requesterId");

        Optional<Booking> stored = bookings.findById(bookingId);
        if (stored.isEmpty()) {
            return CancellationResult.rejected(RejectionReason.BOOKING_NOT_FOUND);
        }
        Booking booking = stored.get();

        Optional<RejectionReason> failure = rules.checkCancellable(booking, requesterId);
        if (failure.isPresent()) {
            return CancellationResult.rejected(failure.get());
        }

        Booking cancelled = booking.cancelled();
        bookings.save(cancelled);
        publish(BookingCancelledEvent.of(cancelled, now()));

        List<CancellationResult.Promotion> promotions = promoteWaitlist(cancelled.roomId());
        return new CancellationResult.Cancelled(cancelled, promotions);
    }

    /**
     * Changes the interval, the party size, or both, on an upcoming booking, on
     * behalf of its owner. The room stays the same.
     *
     * <p>The order is fixed: the booking has to exist; the asker has to own it and
     * it has to be confirmed and not yet started, which are the checks a
     * cancellation makes; a modification that changes nothing succeeds there and
     * records nothing; the new values have to satisfy the booking rules; and then
     * the asker's own calendar and the room's calendar decide, both without the
     * booking being modified, because a booking never collides with itself. The
     * cap on bookings held does not apply, since this booking already counts
     * toward it. A taken slot is refused rather than waitlisted, and every refusal
     * leaves the booking as it was.
     *
     * <p>When the interval changed, the room's waitlist is then walked exactly as
     * after a cancellation. A change to the party size alone frees no time and
     * promotes nobody.
     *
     * @param newInterval the interval to hold instead, or empty to keep the current one
     * @param newAttendees the party size to hold instead, or empty to keep the current one
     * @throws IllegalArgumentException if {@code newAttendees} is present and not positive
     */
    public ModificationResult modifyBooking(
        String bookingId, String requesterId, Optional<TimeInterval> newInterval, OptionalInt newAttendees) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(newInterval, "newInterval");
        Objects.requireNonNull(newAttendees, "newAttendees");
        if (newAttendees.isPresent() && newAttendees.getAsInt() <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + newAttendees.getAsInt());
        }

        Optional<Booking> stored = bookings.findById(bookingId);
        if (stored.isEmpty()) {
            return ModificationResult.rejected(RejectionReason.BOOKING_NOT_FOUND);
        }
        Booking booking = stored.get();

        Optional<RejectionReason> failure = rules.checkCancellable(booking, requesterId);
        if (failure.isPresent()) {
            return ModificationResult.rejected(failure.get());
        }

        TimeInterval interval = newInterval.orElse(booking.interval());
        int attendees = newAttendees.orElse(booking.attendees());
        if (interval.equals(booking.interval()) && attendees == booking.attendees()) {
            return new ModificationResult.Modified(booking, booking, List.of());
        }

        Optional<Room> room = rooms.findById(booking.roomId());
        if (room.isEmpty()) {
            return ModificationResult.rejected(RejectionReason.ROOM_NOT_FOUND);
        }
        BookingRequest request = BookingRequest.of(booking.roomId(), booking.userId(), interval, attendees);
        Optional<RejectionReason> ruleFailure = rules.checkRequest(request, room.get());
        if (ruleFailure.isPresent()) {
            return ModificationResult.rejected(ruleFailure.get());
        }

        List<Booking> ownOthers = otherThan(booking, bookings.findActiveByUser(booking.userId()));
        if (conflictChecker.hasConflict(ownOthers, interval)) {
            return ModificationResult.rejected(RejectionReason.USER_DOUBLE_BOOKED);
        }
        List<Booking> roomOthers = otherThan(booking, bookings.findActiveByRoom(booking.roomId()));
        if (conflictChecker.hasConflict(roomOthers, interval)) {
            return ModificationResult.rejected(RejectionReason.ROOM_UNAVAILABLE);
        }

        Booking modified = booking.modified(interval, attendees, now());
        bookings.save(modified);

        List<CancellationResult.Promotion> promotions = interval.equals(booking.interval())
            ? List.of()
            : promoteWaitlist(booking.roomId());
        return new ModificationResult.Modified(modified, booking, promotions);
    }

    /**
     * Drops the asker's own waitlist entry. This is the only other place waitlist
     * rows are removed, and it lives here for the same reason promotion does.
     */
    public Optional<RejectionReason> leaveWaitlist(String entryId, String requesterId) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(requesterId, "requesterId");
        Optional<WaitlistEntry> entry = waitlist.findById(entryId);
        if (entry.isEmpty()) {
            return Optional.of(RejectionReason.WAITLIST_ENTRY_NOT_FOUND);
        }
        if (!entry.get().userId().equals(requesterId)) {
            return Optional.of(RejectionReason.NOT_BOOKING_OWNER);
        }
        waitlist.remove(entryId);
        return Optional.empty();
    }

    /** The booking with that id, if there is one. */
    public Optional<Booking> findBooking(String bookingId) {
        return bookings.findById(bookingId);
    }

    /** Every booking for the room, cancelled ones included. */
    public List<Booking> bookingsForRoom(String roomId) {
        return bookings.findByRoom(roomId);
    }

    /** Every booking for the user, cancelled ones included. */
    public List<Booking> bookingsForUser(String userId) {
        return bookings.findByUser(userId);
    }

    /** The bookings the user currently holds. */
    public List<Booking> activeBookingsForUser(String userId) {
        return bookings.findActiveByUser(userId);
    }

    /** The room's waitlist in arrival order. */
    public List<WaitlistEntry> waitlistForRoom(String roomId) {
        return waitlist.findByRoom(roomId);
    }

    /** The user's waitlist entries in arrival order. */
    public List<WaitlistEntry> waitlistForUser(String userId) {
        return waitlist.findByUser(userId);
    }

    /**
     * Walks the room's waitlist in arrival order and promotes every entry whose
     * interval is now free.
     *
     * <p>Each candidate is re-checked against the room's current bookings and
     * against the waiter's own calendar, so a promotion can never create a double
     * booking. Entries that no longer make sense (the slot has passed, the room
     * has left service) are dropped rather than promoted.
     */
    private List<CancellationResult.Promotion> promoteWaitlist(String roomId) {
        Optional<Room> room = rooms.findById(roomId);
        if (room.isEmpty()) {
            return List.of();
        }

        List<CancellationResult.Promotion> promotions = new ArrayList<>();
        for (WaitlistEntry entry : waitlist.findByRoom(roomId)) {
            if (!rules.isPromotable(entry.interval(), room.get())) {
                waitlist.remove(entry.id());
                continue;
            }
            if (conflictChecker.hasConflict(bookings.findActiveByRoom(roomId), entry.interval())) {
                continue;
            }
            if (hasOwnOverlap(entry.userId(), entry.interval())) {
                continue;
            }
            if (bookings.findActiveByUser(entry.userId()).size()
                >= rules.policy().maxActiveBookingsPerUser()) {
                continue;
            }

            Booking promoted = Booking.confirmed(
                ids.nextId(BOOKING_ID_PREFIX),
                entry.roomId(),
                entry.userId(),
                entry.interval(),
                entry.attendees(),
                now());
            bookings.save(promoted);
            waitlist.remove(entry.id());
            publish(WaitlistPromotedEvent.of(entry, promoted, now()));
            promotions.add(new CancellationResult.Promotion(entry, promoted));
        }
        return List.copyOf(promotions);
    }

    /** Adds the asker to the room's waitlist unless they are already on it. */
    private BookingOutcome joinWaitlist(BookingRequest request) {
        if (!rules.policy().waitlistEnabled()) {
            return BookingOutcome.rejected(RejectionReason.ROOM_UNAVAILABLE);
        }
        boolean alreadyWaiting = waitlist.findByRoom(request.roomId()).stream()
            .anyMatch(entry -> entry.matches(request));
        if (alreadyWaiting) {
            return BookingOutcome.rejected(RejectionReason.ALREADY_ON_WAITLIST);
        }
        WaitlistEntry entry = new WaitlistEntry(
            ids.nextId(WAITLIST_ID_PREFIX),
            request.roomId(),
            request.userId(),
            request.interval(),
            request.attendees(),
            waitlist.nextSequence(),
            now());
        waitlist.save(entry);
        return new BookingOutcome.Waitlisted(entry);
    }

    /** The asker's own caps: how many bookings they hold, and their calendar. */
    private Optional<RejectionReason> checkAskerLimits(BookingRequest request) {
        List<Booking> held = bookings.findActiveByUser(request.userId());
        if (held.size() >= rules.policy().maxActiveBookingsPerUser()) {
            return Optional.of(RejectionReason.USER_BOOKING_LIMIT_REACHED);
        }
        if (conflictChecker.hasConflict(held, request.interval())) {
            return Optional.of(RejectionReason.USER_DOUBLE_BOOKED);
        }
        return Optional.empty();
    }

    /** The bookings in {@code held} other than {@code booking} itself, matched by id. */
    private static List<Booking> otherThan(Booking booking, List<Booking> held) {
        return held.stream().filter(other -> !other.id().equals(booking.id())).toList();
    }

    /** Does the user already hold a booking over this interval, in any room? */
    private boolean hasOwnOverlap(String userId, TimeInterval interval) {
        return conflictChecker.hasConflict(bookings.findActiveByUser(userId), interval);
    }

    /** Hands the event on. A failing subscriber must not undo a stored booking. */
    private void publish(DomainEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException failure) {
            // Events are fire and forget. The booking has already been stored.
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MINUTES);
    }
}
