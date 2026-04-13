package com.kemini.kiosk_backend.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kemini.kiosk_backend.domain.entity.MenuStatistics;

public interface MenuStatisticsRepository extends JpaRepository<MenuStatistics, Long>{
    
}
