# Infrastructure & Codebase Map

This document connects the Java package structure to the architectural responsibilities of the Smart Attendance System. Use it as a “you are here” guide when navigating the codebase.

---

## `config`

| File | Responsibility |
|------|----------------|
| `PasswordConfig.java` | Exposes a BCrypt `PasswordEncoder` bean so every component uses the same hashing settings. |
| `SecurityConfig.java` | Spring Security filter chain configuration: HTTP Basic auth, invite-only signup pages, static resource allow‑list, and stateless session policy. |
| `WebConfig.java` | Global MVC tweaks (CORS, static resource handling, message converters). |

---

## `controller`

| File | Responsibility |
|------|----------------|
| `CourseController.java` | CRUD for courses plus server-side search and permission filtering by user role. |
| `RecognitionController.java` | Live attendance APIs: SSE event stream, start/stop scanner, frame ingestion, and review resolution. |
| `SectionController.java` | CRUD for sections including staff assignments and capacity settings. |
| `SectionEnrolmentsController.java` | Admin-only endpoints to manage student enrolments per section (one section per course guardrails enforced in the service). |
| `SessionController.java` | Everything about sessions: listing, creation, opening/closing, roster retrieval, manual attendance patching, and CSV/XLSX/PDF exports. |
| `SignupController.java` | `/api/auth/*` endpoints covering invite-based signup, login, logout, profile lookup, username/email availability, and health checks. |
| `StudentController.java` | Student CRUD, training photo upload/removal (runs mask detection + variant bookkeeping), manual mask-status override endpoint, lookup helpers, and service health probe. |
| `SystemSettingsController.java` | `/api/settings/recognition` GET/PUT for recognition threshold and attendance log path. |
| `UserController.java` | Role-filtered user directory used when populating dropdowns for professors/TAs. |

---

## `entity`

| Entity | Description |
|--------|-------------|
| `AttendanceRecord` | Stores attendance outcome per student per session (status, marking method, confidence, notes). |
| `Course` | High-level module definition (code, name, description). |
| `Section` | Course section/class group including assigned staff and max capacity. |
| `SectionEnrolments` | Join table linking students to sections with lifecycle status. |
| `Session` | Scheduled teaching slots (date, start/end, status, creator). |
| `SessionRoster` | Snapshot of students expected in a session (pre-populated from section enrolments). |
| `Student` | Student profile (ID, name, contact info, enrolment date). |
| `SystemSetting` | Key/value store for editable operational settings. |
| `TrainingData` | Binary store for uploaded training photos (raw blob, mime type, checksum, variant flags). |
| `User` | Authenticated staff profiles (admins, professors, teaching assistants). |

Every entity maps directly to tables defined in `smart_attendance_system.sql`.

---

## `repository`

| Repository | Description |
|------------|-------------|
| `AttendanceRecordRepository` | CRUD + summary helpers for attendance entries. |
| `CourseRepository` | Course lookups and uniqueness checks on `course_code`. |
| `SectionRepository` | Section fetchers plus convenience queries for visibility checks. |
| `SectionEnrolmentsRepository` | Handles enrolment uniqueness, status filtering, and cascading delete helpers. |
| `SessionRepository` | Session retrievals, overlap detection, expired-session scans, and professor/course collision queries. |
| `SessionRosterRepository` | Access to roster rows when rendering tables or exporting attendance. |
| `StudentRepository` | ID/email availability checks and search helpers. |
| `SystemSettingRepository` | Simple key/value accessor for recognition settings. |
| `TrainingDataRepository` | Binary data access and checksum lookups for deduplication. |
| `UserRepository` | Username/email lookups, role-filtered listings, last-login updates. |

All repositories extend Spring Data JPA, so higher layers can compose queries with method names or custom `@Query` definitions.

---

## `security`

| File | Description |
|------|-------------|
| `SecurityUtils.java` | Utility helpers to pull the current `UserPrincipal` from the security context and enforce role checks inside controllers/services. |

---

## `service`

Core services live in `com.smartattendance.service` plus two sub-packages (`auth`, `recognition`). Highlights:

| Service | Description |
|---------|-------------|
| `AttendanceAuditLogger` | Writes attendance actions (auto and manual) to the log file configured in system settings. |
| `CourseService` | Business logic for course CRUD, including visibility filtering for non-admin roles. |
| `SectionService` | Section-level validation, staff assignment lookups, and helper methods used by enrolments/sessions. |
| `SectionEnrolmentsService` | Enrolment workflows: capacity checks, per-course duplicate prevention, status transitions, and removals. |
| `SessionService` | Central scheduler—validates time windows (future start/end, >=60 minutes, professor overlap guard), populates rosters, and manages state transitions. |
| `SessionExportService` | Builds CSV/XLSX/PDF exports for attendance summaries. |
| `StudentService` | Student CRUD, photo management helpers, and downstream cascade coordination. |
| `SystemSettingsService` | Typed accessors for `system_settings` (recognition threshold, attendance log path). |
| `UserService` | User CRUD plus availability checks, invite-code validation, and last-login updates. |
| `MaskAugmentationService` / `MaskDetectionService` / `TrainingPhotoValidator` | Validate uploads, score mask presence (ONNX MobileNet + HSV heuristics), and create synthetic mask variants so recognition has masked/unmasked embeddings. |
| `auth.CustomUserDetailsService` | Loads `UserPrincipal` for Spring Security authentication. |
| `auth.UserPrincipal` | Wrapper implementing `UserDetails` so Spring Security can store role/username info. |
| `recognition.AutomaticAttendanceService` | Orchestrates face recognition pipeline, auto-marking, and review queue creation. |
| `recognition.FaceRecognitionService` | Runs the actual recognition model (YuNet/SFace via JavaCPP). |
| `recognition.PendingRecognitionStore` | In-memory store for low-confidence detections awaiting manual review. |
| `recognition.RecognitionEventService` | Publishes server-sent events to connected clients. |
| `recognition.tracking.*` | Kalman-style trackers that smooth face detections across frames (single/multi face). |

These services are wired into controllers via constructor injection and are responsible for enforcing the domain rules described in `API.md` and `Database.md`.

---

## Mask-Aware Training Flow

1. `StudentController` accepts uploads through `/api/students` and `/api/students/{studentId}/photos`, handing each file to `StudentService.saveTrainingData`.
2. `TrainingPhotoValidator` enforces mime/size/quality constraints before anything is persisted.
3. `MaskDetectionService` evaluates the image (`models/mask_detector.onnx` with a heuristic fallback) and returns `masked` + `maskConfidence`. The result is stored in `TrainingData.hasRealMask` and surfaced to the UI as `autoMaskSuggestion`/`maskConfidence`.
4. If a real mask is absent, `MaskAugmentationService` synthesises a masked variant linked via `source_training_id`/`is_mask_variant`, giving the recognition pipeline both masked and unmasked embeddings.
5. Staff can correct edge cases through `PATCH /api/students/{studentId}/photos/{photoId}/mask-status`, which toggles `hasRealMask`, regenerates/deletes variants as needed, and forces a recognition profile refresh.

---

## Primary Application Entry Point

`SmartAttendanceSystemApplication.java` lives at the root of the package tree and boots the Spring context (auto-scanning the packages listed above). It is the class invoked by `mvn spring-boot:run`.

---

For schema details refer to `Database.md`; for endpoint contracts see `API.md`. Together the three documents provide a full map from REST surface → services → persistence.***
