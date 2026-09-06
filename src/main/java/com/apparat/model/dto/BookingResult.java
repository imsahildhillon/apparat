package com.apparat.model.dto;

import com.apparat.model.enums.BookingStatus;

public class BookingResult {
    private final long bookingId;
    private final BookingStatus status;

    private BookingResult(long bookingId, BookingStatus status) {
        this.bookingId = bookingId;
        this.status = status;
    }

    public static BookingResult of(long bookingId, BookingStatus status) {
        return new BookingResult(bookingId, status);
    }

    public long getBookingId() { return bookingId; }
    public BookingStatus getStatus() { return status; }
}
