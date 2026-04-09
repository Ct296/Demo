package com.hotel.system.entity;

import com.hotel.system.entity.enums.RentalStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "RENTAL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Rental {

    @Id
    @Column(name = "RENTAL_ID", length = 10)
    private String id;

    @Column(name = "RENTAL_CheckinDate", nullable = false)
    private LocalDateTime checkinDate;

    @Column(name = "RENTAL_RentDate", nullable = false)
    private LocalDateTime rentDate;

    @Column(name = "RENTAL_LengthOfStay", nullable = false)
    private Integer lengthOfStay;

    @Column(name = "RENTAL_GuestCount", nullable = false)
    private Integer guestCount;

    @Column(name = "RENTAL_RoomUnitPrice", nullable = false)
    private Double roomUnitPrice;

    @Column(name = "RENTAL_Note", length = 255)
    private String note;

    @Column(name = "RENTAL_IsBooking", nullable = false)
    private Boolean isBooking;

    @Enumerated(EnumType.STRING)
    @Column(name = "RENTAL_Status", nullable = false, length = 20)
    private RentalStatus status;

    @ManyToOne(optional = false)
    @JoinColumn(name = "USER_ID", nullable = false)
    private Customer customer;

    @ManyToOne(optional = false)
    @JoinColumn(name = "ROOM_ID", nullable = false)
    private Room room;
}