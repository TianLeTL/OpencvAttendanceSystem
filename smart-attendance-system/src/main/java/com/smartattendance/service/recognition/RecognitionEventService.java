package com.smartattendance.service.recognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple SSE broker scoped per session. Allows multiple browser tabs/devices to receive recognition updates.
 */
@Service
public class RecognitionEventService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionEventService.class);

    private final Map<Integer, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Integer sessionId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout – client controls lifecycle
        emitters.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(ex -> removeEmitter(sessionId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "sessionId", sessionId,
                            "timestamp", Instant.now().toString()
                    )));
        } catch (IOException e) {
            log.debug("Unable to send initial SSE handshake: {}", e.getMessage());
        }

        return emitter;
    }

    public void publish(Integer sessionId, String type, Object payload) {
        List<SseEmitter> sessionEmitters = emitters.get(sessionId);
        if (sessionEmitters == null || sessionEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().name(type).data(payload));
            } catch (IOException ex) {
                removeEmitter(sessionId, emitter);
            }
        }
    }

    public void closeSession(Integer sessionId) {
        List<SseEmitter> sessionEmitters = emitters.remove(sessionId);
        if (sessionEmitters == null) {
            return;
        }
        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().name("session-closed").data(Map.of(
                        "sessionId", sessionId,
                        "timestamp", Instant.now().toString()
                )));
            } catch (IOException ignored) {
                // ignore errors when signalling closure
            } finally {
                emitter.complete();
            }
        }
    }

    private void removeEmitter(Integer sessionId, SseEmitter emitter) {
        List<SseEmitter> sessionEmitters = emitters.get(sessionId);
        if (sessionEmitters == null) {
            return;
        }
        sessionEmitters.remove(emitter);
    }
}
