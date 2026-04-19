package com.sensex.optiontrader.model.entity;

import com.sensex.optiontrader.model.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity @Table(name="users") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, unique=true) private String email;
    @Column(nullable=false) private String password;
    @Column(nullable=false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false) @Builder.Default private UserRole role = UserRole.USER;
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
}