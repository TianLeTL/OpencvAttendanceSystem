package com.smartattendance.repository;

import com.smartattendance.entity.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, Integer> {

    Optional<SystemSetting> findBySettingKey(String settingKey);
}

