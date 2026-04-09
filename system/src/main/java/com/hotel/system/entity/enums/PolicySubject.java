package com.hotel.system.entity.enums;

import lombok.Getter;

@Getter
public enum PolicySubject {
    CUSTOMER("Khách hàng"),
    STAFF("Nhân viên"),
    ALL("Tất cả đối tượng");

    private final String displayName;

    PolicySubject(String displayName) {
        this.displayName = displayName;
    }
}