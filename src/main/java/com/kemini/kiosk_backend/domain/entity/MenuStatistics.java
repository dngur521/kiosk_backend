package com.kemini.kiosk_backend.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter @Setter
@NoArgsConstructor
public class MenuStatistics {
    @Id
    private Long menuId; // Primary Key

    @OneToOne
    @MapsId
    @JoinColumn(name = "menu_id")
    private Menu menu;

    private Long orderCount = 0L;

    // 서비스에서 사용할 생성자
    public MenuStatistics(Menu menu) {
        this.menu = menu;
        this.orderCount = 0L;
    }

    public void increment(int quantity) {
        this.orderCount += quantity;
    }
}
