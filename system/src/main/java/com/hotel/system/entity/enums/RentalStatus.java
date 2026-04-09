package com.hotel.system.entity.enums;

import lombok.Getter;

@Getter
public enum RentalStatus {
    PENDING("Chờ xác nhận"),
    CONFIRMED("Đã xác nhận"),
    CHECKED_IN("Đã nhận phòng"),
    OVERDUE("Đã quá hạn"),
    CHECKED_OUT("Đã trả phòng"),
    CANCELLED("Đã hủy");

    private final String displayName;

    RentalStatus(String displayName) {
        this.displayName = displayName;
    }
}