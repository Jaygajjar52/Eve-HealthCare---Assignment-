package com.eve.diagnostics.enums;

/**
 * What a user is allowed to do. Stored as a string in the users.role column,
 * which has a matching CHECK constraint in V1__init.sql.
 */
public enum Role {
    USER,
    ADMIN
}