package com.hotel.system.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ROOM_TYPE")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomType {

    @Id
    @Column(name = "ROOM_TYPE_ID", length = 10)
    private String id;

    @Column(name = "ROOM_TYPE_Name", nullable = false, length = 50)
    private String name;

    @Column(name = "ROOM_TYPE_MaxCustomers", nullable = false)
    private Integer maxCustomers;

    @Column(name = "ROOM_TYPE_Area", nullable = false, precision = 6, scale = 2)
    private BigDecimal area;

    @Column(name = "ROOM_TYPE_BasePrice", nullable = false)
    private Double basePrice;

    @Column(name = "ROOM_TYPE_DepositPercent", nullable = false)
    private Double depositPercent;

    @Column(name = "ROOM_TYPE_Description", length = 255)
    private String description;

    @Column(name = "ROOM_TYPE_CreateDate", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "ROOM_TYPE_UpdateDate")
    private LocalDateTime updateDate;
}