package com.hrms.backend.repositories;

import com.hrms.backend.models.Event;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Database access for events. Spring Data builds the queries from the method NAMES, so there is
 * no SQL/Mongo query to write. NOTE that every method takes the userId: there is deliberately no
 * "find by id only" method used by our code, so one user can never load another user's event.
 */
@Repository
public interface EventRepository extends MongoRepository<Event, String> {

    /** Loads an event only if it belongs to this user (otherwise empty, as if it did not exist). */
    Optional<Event> findByIdAndUserId(String id, String userId);

    /**
     * Events of this user that OVERLAP the window [rangeStart, rangeEnd):
     * event.startTime &lt; rangeEnd AND event.endTime &gt; rangeStart.
     * (Parameter order follows the order of the conditions in the method name.)
     */
    List<Event> findByUserIdAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(
            String userId, Instant rangeEnd, Instant rangeStart);

    long countByUserId(String userId);

    /** Returns how many documents were deleted (0 when the id does not belong to this user). */
    long deleteByIdAndUserId(String id, String userId);
}
