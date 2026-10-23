package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One modification of a booking: what it held before, what it holds after, and
 * when the change was made.
 *
 * <p>A modification that changes nothing is not recorded, so every entry changes
 * the interval, the party size, or both.
 */
public record BookingChange(
    Instant modifiedAt,
    TimeInterval previousInterval,
    int previousAttendees,
    TimeInterval newInterval,
    int newAttendees) {

    public BookingChange {
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        if (previousAttendees <= 0 || newAttendees <= 0) {
            throw new IllegalArgumentException(
                "Attendees must be positive: " + previousAttendees + " to " + newAttendees);
        }
        if (previousInterval.equals(newInterval) && previousAttendees == newAttendees) {
            throw new IllegalArgumentException("A recorded change has to change something");
        }
    }

    /** Did this change move or resize the booked interval? */
    public boolean changedInterval() {
        return !previousInterval.equals(newInterval);
    }

    /** Did this change alter the party size? */
    public boolean changedAttendees() {
        return previousAttendees != newAttendees;
    }
}
