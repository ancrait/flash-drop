package com.sorokaandriy.inventory_service.exception;

public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(String message) {
        super(message);
    }
}
