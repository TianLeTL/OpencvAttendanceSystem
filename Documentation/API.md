# API Reference

All APIs are hosted by the Spring Boot server (default base URL `http://localhost:8080`). Unless noted, every response follows the envelope:

```json
{
  "success": true,
  "message": "Human readable status",
  "data": { ... }
}
```

On validation or server errors `success` becomes `false` and `message` carries the explanation. All endpoints expect/return JSON unless otherwise specified.

---

## Authentication & Invite Signup (`/api/auth`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `POST` | `/api/auth/signup` | Register a user with role + invite code. | `{ "username", "password", "email", "fullName", "role", "inviteCode" }` | Public (code required) |
| `POST` | `/api/auth/login` | Login with username or email. | `{ "usernameOrEmail", "password" }` | Public |
| `POST` | `/api/auth/logout` | Placeholder logout hook (session cleanup on client). | – | Logged-in |
| `GET` | `/api/auth/me` | Return current authenticated profile. | – | Logged-in |
| `GET` | `/api/auth/check-username` | Check username availability. | `?username=alice` | Public |
| `GET` | `/api/auth/check-email` | Check email availability. | `?email=alice@school.edu` | Public |
| `GET` | `/api/auth/health` | Lightweight health probe. | – | Public |

Invite codes are configured in `application.properties` (`signup.invite.admin`, `signup.invite.professor`, `signup.invite.ta`) and surfaced via the dedicated signup pages.

---

## User Directory (`/api/users`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/users` | List users filtered by role (plus optional search). | `?role=PROFESSOR&search=john` | Admin |

Returns sorted metadata (`userId`, `username`, `email`, `fullName`, `role`, timestamps) so the UI can populate select menus (assigning professors/TAs, etc.).

---

## Students (`/api/students`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/students` | List students (optional `?search=`). | Query `search` performs case-insensitive match on ID/name/email. | Admin |
| `GET` | `/api/students/{studentId}` | Fetch a single student. | – | Admin |
| `GET` | `/api/students/{studentId}/detail` | Student plus training-image metadata. | – | Admin |
| `GET` | `/api/students/{studentId}/photos` | Lightweight metadata for all photos. | – | Admin |
| `POST` | `/api/students` | Create student (multipart). | `studentId`, `fullName`, `email`, `phoneNumber?`, `photos[]` | Admin |
| `POST` | `/api/students/with-photos` | Alternate multipart endpoint (identical payload). | – | Admin |
| `PUT` | `/api/students/{studentId}` | Update profile. | JSON `{ "studentId?", "fullName", "email", "phoneNumber" }` | Admin |
| `DELETE` | `/api/students/{studentId}` | Delete student (cascades to attendance/training data). | – | Admin |
| `POST` | `/api/students/{studentId}/photos` | Append training photos (multipart). | `photos[]` | Admin |
| `DELETE` | `/api/students/{studentId}/photos/{photoId}` | Remove a specific training photo. | – | Admin |
| `GET` | `/api/students/check-student-id` | Validate student ID uniqueness. | `?studentId=01234567` | Admin |
| `GET` | `/api/students/check-email` | Validate student email uniqueness. | `?email=foo@school.edu` | Admin |
| `GET` | `/api/students/health` | Health probe. | – | Admin |

---

## Courses (`/api/courses`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/courses` | List/search courses (admins see all, faculty limited to assigned ones). | Optional `?search=` & `?searchBy=code|name`. | Admin/Professor/TA |
| `GET` | `/api/courses/{courseId}` | Fetch single course with metadata. | – | Admin/Professor/TA (must have access). |
| `POST` | `/api/courses` | Create course. | `{ "courseCode", "courseName", "description?" }` | Admin |
| `PUT` | `/api/courses/{courseId}` | Update course. | Same payload as create. | Admin |
| `DELETE` | `/api/courses/{courseId}` | Delete course (cascades sections + sessions). | – | Admin |
| `GET` | `/api/courses/check-code` | Validate uniqueness of a course code. | `?code=CS102` | Admin |

---

## Sections (`/api/sections`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/sections` | List all sections (primarily for admins). | – | Admin |
| `GET` | `/api/sections/course/{courseId}` | Sections belonging to a course. | – | Admin/Professor/TA (if assigned). |
| `GET` | `/api/sections/{sectionId}` | Fetch a section (with course info + assigned staff). | – | Admin/Professor/TA (if assigned). |
| `POST` | `/api/sections` | Create section. | `{ "courseId", "sectionCode", "instructorName?", "maxCapacity?", "professorUserId?", "teachingAssistant1UserId?", "teachingAssistant2UserId?" }` | Admin |
| `PUT` | `/api/sections/{sectionId}` | Update section. | Same payload as create. | Admin |
| `DELETE` | `/api/sections/{sectionId}` | Delete section (cascades sessions + enrolments). | – | Admin |

---

## Section Enrolments (`/api/section-enrolments`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/section/{sectionId}` | List all enrolments for a section. | – | Admin |
| `GET` | `/student/{studentId}` | List sections a student belongs to. | – | Admin |
| `GET` | `/{enrolmentId}` | Fetch enrolment by primary key. | – | Admin |
| `GET` | `/section/{sectionId}/student/{studentId}` | Fetch enrolment via composite key. | – | Admin |
| `POST` | `/section/{sectionId}/student/{studentId}` | Enrol student (capacity + per-course conflict checks). | Optional `{ "status": "ENROLLED" }` | Admin |
| `PUT` | `/section/{sectionId}/student/{studentId}` | Update enrolment status. | `{ "status": "DROPPED" | "COMPLETED" | "ENROLLED" }` | Admin |
| `DELETE` | `/section/{sectionId}/student/{studentId}` | Remove student from section. | – | Admin |

---

## Sessions (`/api/sessions`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/sessions` | Paginated list with bucket filters (`bucket=today|this-week|finished`, `status=`, `search=`). | Query params optional. | Admin/Professor/TA (filtered by visibility). |
| `POST` | `/api/sessions` | Create session (enforces min 60 mins, future start/end, professor overlap rules). | `{ "sectionId", "sessionDate", "startTime", "endTime", "location?", "lateThresholdMinutes?", "createdByUserId?" }` | Admin/Professor |
| `GET` | `/api/sessions/{sessionId}` | Fetch session summary incl. attendance aggregates. | – | Admin/Professor/TA (must have access). |
| `POST` | `/api/sessions/{sessionId}/start` | Open session (populate roster, start window validation). | Optional `{ "userId": 123 }` | Admin/Professor |
| `POST` | `/api/sessions/{sessionId}/close` | Close session (auto-mark absentees). | Optional `{ "userId": 123 }` | Admin/Professor |
| `GET` | `/api/sessions/{sessionId}/roster` | Fetch roster with attendance status + remarks. | – | Admin/Professor/TA (if assigned). |
| `PATCH` | `/api/sessions/{sessionId}/attendance` | Manual attendance updates. | `{ "markedByUserId": 123, "updates": [{ "studentId", "status", "notes?" }] }` | Admin/Professor/TA (session perms enforced). |
| `GET` | `/api/sessions/{sessionId}/export` | Download attendance sheet (`format=csv|xlsx|pdf`). | `?format=csv` (default csv) | Admin/Professor |

---

## Recognition Runtime (`/api/recognition`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/recognition/events` | Subscribe to SSE event stream (auto-mark, review queue). | `?sessionId=42` (required) | Admin/Professor/TA (attendance staff). |
| `GET` | `/api/recognition/sessions/open` | Sessions currently OPEN (for scanner dropdown). | – | Admin/Professor/TA |
| `POST` | `/api/recognition/start` | Prepare recognition pipeline for session. | `{ "sessionId": 42 }` | Admin/Professor/TA |
| `POST` | `/api/recognition/stop` | Stop pipeline + flush caches. | `{ "sessionId": 42 }` | Admin/Professor/TA |
| `POST` | `/api/recognition/frame` | Submit base64 frame from camera. | `{ "sessionId": 42, "imageData": "data:image/jpeg;base64,..." }` | Admin/Professor/TA |
| `POST` | `/api/recognition/review/{token}` | Resolve manual review decision. | `{ "accepted": true|false, "markedByUserId": 123 }` | Admin/Professor/TA |

Responses stream server-sent events with labels `auto-mark`, `review`, `log`, etc., so the browser can update the UI in real time.

---

## Recognition Settings (`/api/settings/recognition`)

| Method | Path | Description | Body / Query | Auth |
|--------|------|-------------|--------------|------|
| `GET` | `/api/settings/recognition` | Snapshot of recognition-related settings (`recognition_threshold`, `attendance_log_path`). | – | Admin |
| `PUT` | `/api/settings/recognition` | Update settings. | `{ "recognitionThreshold": 85.0, "attendanceLogPath": "logs/attendance.log" }` | Admin |

---

## Health & Utility Endpoints

- `GET /api/students/health`
- `GET /api/auth/health`

Both return `{"success": true, "message": "..."}` and are safe for monitoring probes.

---

## Conventions & Notes

1. **Authentication**: The SPA stores the auth token in `sessionStorage` and sends it via the `Authorization: Basic <token>` header for protected endpoints.
2. **Validation**: Server-side validation mirrors the rich client-side checks (student ID format, session time windows, invite codes, etc.). Error messages are meaningful and ready to display directly in the UI.
3. **File Uploads**: All photo/training uploads use `multipart/form-data` with repeated `photos[]` fields; SheetJS is used client-side for CSV/XLSX parsing before hitting the REST endpoints.
4. **Streams**: Recognition SSE responses use `text/event-stream`; the caller should implement reconnect/backoff logic if the connection drops.
5. **Exports**: `/export` downloads return `Content-Disposition: attachment; filename="...csv"` (or xlsx/pdf) so the browser prompts to save.

For deeper architectural details (controllers, services, repositories) see `Infrastructure.md`. The MySQL schema backing these endpoints is documented in `Database.md`.
