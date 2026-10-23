package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a request to modify a booking. A successful modification reports
 * the booking as it now stands, the booking as it stood before, and the
 * bookings promoted off the waitlist into the time it freed.
 */
public sealed interface ModificationResult {

    /**
     * The modification went through. {@code promotions} may be empty. When the
     * modification changed nothing, {@code booking} and {@code previous} are the
     * same booking and nothing was recorded.
     */
    record Modified(Booking booking, Booking previous, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(booking, "booking");
            Objects.requireNonNull(previous, "previous");
            promotions = List.copyOf(promotions);
        }

        /** Did the modification change anything? */
        public boolean changed() {
            return !booking.equals(previous);
        }
    }

    /** The modification was refused. The booking stands unchanged. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the modification go through? */
    default boolean isModified() {
        return this instanceof Modified;
    }

    /** The booking as it now stands, when the modification went through. */
    default Optional<Booking> modifiedBooking() {
        return this instanceof Modified modified ? Optional.of(modified.booking()) : Optional.empty();
    }

    /** Promotions triggered by the modification, empty when it was refused. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
