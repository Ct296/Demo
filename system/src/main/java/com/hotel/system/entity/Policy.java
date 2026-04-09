package com.hotel.system.entity;

import com.hotel.system.entity.enums.PolicySubject;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "POLICY")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Policy {

    @Id
    @Column(name = "POLICY_Number", length = 10)
    private String policyNumber;

    @Column(name = "POLICY_Name", nullable = false, length = 100)
    private String name;

    @Column(name = "POLICY_Content", columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "POLICY_Subject", nullable = false, length = 50)
    private PolicySubject subject;

    @Column(name = "POLICY_CreateDate", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "POLICY_UpdateDate")
    private LocalDateTime updateDate;

    @ManyToOne(optional = false)
    @JoinColumn(name = "admin_id", nullable = false)
    private Admin admin;
}