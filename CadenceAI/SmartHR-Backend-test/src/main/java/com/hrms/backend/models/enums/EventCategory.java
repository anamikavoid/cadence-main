package com.hrms.backend.models.enums;

/** The kind of schedule entry. Stored as text in MongoDB (e.g. "CLASS"). */
public enum EventCategory {
    CLASS, MEETING, PERSONAL, WORK, ASSIGNMENT, OTHER
}
