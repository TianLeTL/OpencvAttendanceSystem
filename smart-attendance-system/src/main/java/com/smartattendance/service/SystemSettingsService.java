package com.smartattendance.service;

import com.smartattendance.entity.SystemSetting;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import com.smartattendance.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SystemSettingsService {

    private final SystemSettingRepository systemSettingRepository;
    private final Map<SettingKey, String> cachedValues = new ConcurrentHashMap<>();

    public SystemSettingsService(SystemSettingRepository systemSettingRepository) {
        this.systemSettingRepository = systemSettingRepository;
    }

    @PostConstruct
    public void preloadDefaults() {
        for (SettingKey key : SettingKey.values()) {
            loadOrCreate(key);
        }
        refreshAttendanceLogAppender(resolveValue(SettingKey.ATTENDANCE_LOG_PATH));
    }

    @Transactional(readOnly = true)
    public BigDecimal getRecognitionThreshold() {
        String value = resolveValue(SettingKey.RECOGNITION_THRESHOLD);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return new BigDecimal(SettingKey.RECOGNITION_THRESHOLD.defaultValue());
        }
    }

    @Transactional
    public void updateRecognitionThreshold(BigDecimal threshold) {
        upsertValue(SettingKey.RECOGNITION_THRESHOLD, threshold.toPlainString());
    }

    public Map<String, String> getSettingsSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put(SettingKey.RECOGNITION_THRESHOLD.key(), resolveValue(SettingKey.RECOGNITION_THRESHOLD));
        snapshot.put(SettingKey.ATTENDANCE_LOG_PATH.key(), resolveValue(SettingKey.ATTENDANCE_LOG_PATH));
        return snapshot;
    }

    @Transactional(readOnly = true)
    public String getAttendanceLogPath() {
        return resolveValue(SettingKey.ATTENDANCE_LOG_PATH);
    }

    @Transactional
    public void updateAttendanceLogPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Attendance log path cannot be blank");
        }
        String trimmed = path.trim();
        upsertValue(SettingKey.ATTENDANCE_LOG_PATH, trimmed);
        refreshAttendanceLogAppender(trimmed);
    }

    private void upsertValue(SettingKey key, String newValue) {
        SystemSetting setting = systemSettingRepository
                .findBySettingKey(key.key())
                .orElseGet(() -> createSettingEntity(key));
        setting.setSettingValue(newValue);
        setting.setDataType(key.dataType());
        setting.setDescription(key.description());
        systemSettingRepository.save(setting);
        cachedValues.put(key, newValue);
    }

    private String resolveValue(SettingKey key) {
        return cachedValues.computeIfAbsent(key, this::loadOrCreate);
    }

    private String loadOrCreate(SettingKey key) {
        return systemSettingRepository
                .findBySettingKey(key.key())
                .map(SystemSetting::getSettingValue)
                .orElseGet(() -> {
                    SystemSetting created = createSettingEntity(key);
                    systemSettingRepository.save(created);
                    return created.getSettingValue();
                });
    }

    private SystemSetting createSettingEntity(SettingKey key) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key.key());
        setting.setSettingValue(key.defaultValue());
        setting.setDataType(key.dataType());
        setting.setDescription(key.description());
        return setting;
    }

    private void refreshAttendanceLogAppender(String filePath) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        if (context == null) {
            return;
        }
        Logger auditLogger = context.getLogger("attendance.audit");
        if (auditLogger == null) {
            return;
        }
        Appender<ILoggingEvent> appender = auditLogger.getAppender("ATTENDANCE_LOG");
        if (appender instanceof RollingFileAppender<?> rollingAppender) {
            synchronized (rollingAppender) {
                rollingAppender.stop();
                rollingAppender.setFile(filePath);
                if (rollingAppender.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) {
                    policy.stop();
                    policy.setFileNamePattern(buildRollingPattern(filePath));
                    policy.start();
                }
                rollingAppender.start();
            }
        }
    }

    private String buildRollingPattern(String filePath) {
        String trimmed = filePath.trim();
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot > trimmed.lastIndexOf('/')) {
            String base = trimmed.substring(0, lastDot);
            String ext = trimmed.substring(lastDot);
            return base + "-%d{yyyy-MM-dd}" + ext;
        }
        return trimmed + "-%d{yyyy-MM-dd}.log";
    }

    enum SettingKey {
        RECOGNITION_THRESHOLD("recognition_threshold", "85.00", "DECIMAL", "Minimum confidence for auto-marking"),
        ATTENDANCE_LOG_PATH("attendance_log_path", "logs/attendance.log", "STRING", "Filesystem path to attendance audit log file");

        private final String key;
        private final String defaultValue;
        private final String dataType;
        private final String description;

        SettingKey(String key, String defaultValue, String dataType, String description) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.dataType = dataType;
            this.description = description;
        }

        public String key() {
            return key;
        }

        public String defaultValue() {
            return defaultValue;
        }

        public String dataType() {
            return dataType;
        }

        public String description() {
            return description;
        }
    }
}
