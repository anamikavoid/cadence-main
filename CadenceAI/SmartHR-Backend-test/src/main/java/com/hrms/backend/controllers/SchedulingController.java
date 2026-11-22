package com.hrms.backend.controllers;

import com.hrms.backend.dtos.entityDtos.Event.EventResponseDto;
import com.hrms.backend.services.scheduling.SchedulingEngine;
import com.hrms.backend.services.scheduling.SchedulingEngine.*;
import com.hrms.backend.services.eventService.EventServiceInterface;
import com.hrms.backend.utils.CurrentUser;
import com.hrms.backend.exceptions.EventConflictException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/scheduling")
public class SchedulingController {
    private final SchedulingEngine engine;
    private final EventServiceInterface events;

    public SchedulingController(SchedulingEngine engine, EventServiceInterface events) {
        this.engine = engine;
        this.events = events;
    }

    @PostMapping("/proposals")
    public Proposal propose(Authentication auth, @RequestBody Request request) {
        return engine.propose(CurrentUser.id(auth), request);
    }

    public record Confirmation(Request request, Move move) {}

    /** Confirm one reviewed move, then request an updated plan before applying further changes. */
    @PostMapping("/confirm-move")
    public EventResponseDto confirm(Authentication auth, @RequestBody Confirmation confirmation) {
        String owner = CurrentUser.id(auth);
        Proposal latest = engine.propose(owner, confirmation.request());
        Move move = confirmation.move();
        if (latest.movesToConfirm().isEmpty() || !latest.movesToConfirm().getFirst().equals(move)) {
            throw new EventConflictException("Request a fresh scheduling proposal.");
        }
        return EventResponseDto.from(events.confirmMove(owner, move.eventId(), move.previous().start(),
                move.previous().end(), move.proposed().start()));
    }
}
