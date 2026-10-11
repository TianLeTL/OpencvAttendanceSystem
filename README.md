# Smart Attendance System


## 📋 Table of Contents

- [Project Overview](#project-overview)
- [Technologies Used](#technologies-used)
- [Prerequisites](#prerequisites)
- [Installation & Setup](#installation--setup)
- [Configuration](#configuration)
- [Current Features](#current-features)
- [How to Run](#how-to-run)
- [Documentation](#documentation)
- [Edge Cases & Guardrails](#️-edge-cases--guardrails)

## 🚀 Project Overview

The Smart Attendance System is an intelligent web-based platform that combines traditional student management with cutting-edge face recognition technology. Built using Spring Boot for robust backend services and modern HTML/CSS/JavaScript for an intuitive user interface, the system provides comprehensive tools for educational institutions to manage students and implement automated attendance tracking.

## 🛠️ Technologies Used

### Backend
- Java 17, Spring Boot 3.1.5
- Spring Security, Spring Data JPA/Hibernate
- MySQL 8.x + HikariCP, OpenCV (YuNet/SFace via Bytedeco)
- Maven build tooling

### Frontend
- Static HTML5 + Tailwind CSS
- Vanilla JavaScript (ES6), Fetch API, SheetJS (XLSX) for imports
- Session Storage for auth state

### Infrastructure & Ops
- Invite-code driven access, audit logging (Logback), SSE for recognition events
- Docker/MySQL friendly configuration via `.properties`
- Recognition assets stored in MySQL `training_data` blobs for portability

## 📋 Prerequisites

Before running this application, ensure you have the following installed:

- **Java 17** or higher
- **Maven 3.6** or higher
- **MySQL 8.0** or higher
- **MAMP (macOS) / WAMP (Windows)** 
- **MySQL Workbench** 
- **Git** (for cloning the repository)

## 🛠️ Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/JericToh1/CS102-Smart-Attendance-System.git
   cd CS102-Smart-Attendance-System
   ```

2. **Maven installation:**   
    [Download Maven](https://maven.apache.org/download.cgi)
3. **Download MySQL Workbench**  
    [Download MySQL Workbench](https://www.mysql.com/products/workbench/)

4. **Set up the Database:**
   - Ensure MAMP (macOS) / WAMP (Windows) is running on your system to start the MySQL server
   - Create the database by importing the database schema found in `smart_attendance_system.sql`

## ⚙️ Configuration

### Database Configuration

**Configure Database Connection:**
- Update `src/main/resources/application.properties` with your MySQL credentials
- Default configuration connects to MySQL on localhost:3306

**File:** `src/main/resources/application.properties`

```properties
# Database Configuration - MySQL
spring.datasource.url=jdbc:mysql://localhost:3306/smart_attendance_system?useSSL=false&serverTimezone=Asia/Singapore&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA/Hibernate Properties
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect

# Server Configuration
server.port=8080

# Logging
logging.level.com.smartattendance=DEBUG
```

### Important Configuration Details

| Setting | Value | Description |
|---------|-------|-------------|
| **Server Port** | `8080` | Spring Boot application port |
| **Database Port** | `3306` | MySQL server port |
| **Database Name** | `smart_attendance_system` | MySQL database name |
| **Database Username** | `root` | MySQL username |
| **Database Password** | `password` | MySQL password |
| **Default Admin User** | `admin` | Pre-created admin account |
| **Default Admin Password** | `password` | Admin login password |
| **Invite Codes** | `signup.invite.{role}` | Role-specific strings required by the signup pages (`signup-admin.html`, etc.). Example:<br>`signup.invite.admin=ADMIN-INVITE-7842` |

## 🚀 How to Run

1. **Launch MAMP (macOS) or WAMP (Windows)**

2. **Open MySQL Workbench**


3. **Import the Database Schema found in `smart_attendance_system.sql`** 

4. **Run the Application:**
   ```bash
   cd ./smart-attendance-system
   mvn spring-boot:run
   ```

5. **Access the Application:**
    - **Login:** http://localhost:8080/index.html
    - **Invite-only Signup:**  
      - Admin: `http://localhost:8080/signup-admin.html`  
      - Professor: `http://localhost:8080/signup-professor.html`  
      - Teaching Assistant: `http://localhost:8080/signup-ta.html`
      ***Invite Codes (Under application.properties)***
      - signup.invite.admin=ADMIN-INVITE-7842
      - signup.invite.professor=PROF-INVITE-3519
      - signup.invite.ta=TA-INVITE-6294

    - **Dashboard:** http://localhost:8080/home.html
    - **Students:** http://localhost:8080/students.html
    - **Student Detail:** http://localhost:8080/student-view.html?id={studentId}
    - **Sessions:** http://localhost:8080/sessions.html
    - **Mark Attendance (live recognition):** http://localhost:8080/mark-attendance.html
    - **Settings:** http://localhost:8080/settings.html

---

## 🎯 Current Features

### 1. User Authentication & Authorization
- **Location:** `http://localhost:8080/`
- **Functionality:**
  - User registration with comprehensive validation
  - Secure login with username/email and password
  - Password hashing using BCrypt encryption
  - Session management with browser storage
  - Protected routes and authentication state validation
  - Secure logout with session clearing

### 2. Complete Student Management System
- **Location:** `http://localhost:8080/students.html`
- **Functionality:**
  - **CRUD Operations:** Create, read, update, delete students
  - **Advanced Search:** Real-time search by student ID or name
  - **Data Validation:** Student ID format validation (S + digits)
  - **Email Validation:** Comprehensive email format checking
  - **Enrollment Tracking:** Automatic enrollment date management
  - **Sortable Lists:** Click column headers to sort by ID, name, email
  - **Bulk Operations:** Import students via CSV (UI ready)

### 3. Course & Section Management
- **Location:** `http://localhost:8080/courses.html`
- **Functionality:**
  - **Course CRUD:** Create modules, update descriptions, retire obsolete offerings
  - **Section Builder:** Define section codes (G1, LAB02), instructor names, and capacity caps
  - **Staff Assignment:** Attach professors and up to two teaching assistants per section; enforcement prevents duplicate TA assignments
  - **Visibility Filtering:** Professors/TAs only see sections they own; admins see the entire catalog
  - **Bulk Enrolment View:** Launch the “Manage Students” modal to review enrolments per section

### 4. Session Lifecycle & Attendance Management
- **Locations:** `sessions.html`, `session-view.html`, `mark-attendance.html`
- **Functionality:**
  - **Session Scheduler:** Create upcoming sessions per section with location, time slot, and late thresholds
  - **Roster Automation:** Starting a session auto-populates roster entries from section enrolments
  - **Attendance Toolkit:** Live recognition dashboard, manual overrides, review queue, and CSV/XLSX/PDF exports
  - **Auto-Close Logic:** Closing a session marks any remaining unmarked students as `ABSENT`
  - **Professor Guardrails:** Professors can’t schedule overlapping sessions for the same course across different sections

### 5. Face Recognition Training Data Management

- **Location:** `http://localhost:8080/student-view.html?id={studentId}`
- **Functionality:**
  - **Multiple Image Upload:** Support for JPG/PNG face images
  - **Batch Processing:** Upload multiple photos simultaneously
  - **Image Preview:** Real-time thumbnail generation
  - **Training Data Storage:** Secure database storage with metadata
  - **Photo Management:** Add/remove individual training images
  - **Base64 Encoding:** Optimized for frontend display
  - **File Validation:** Type/size checks, face detection to ensure the subject is centered, and automatic synthetic mask generation when uploads lack a mask (so recognition works in masked environments)

### 6. Modern Web Interface

- **Design:** Beautiful dark theme with gradient backgrounds
- **Responsive Layout:** Mobile-first design that adapts to all screen sizes
- **Interactive Elements:** Hover effects, smooth transitions, loading states
- **Navigation:** Intuitive menu system between different modules
- **Modal Forms:** Clean, accessible forms for data entry
- **Real-time Feedback:** Live search results, validation messages
- **Error Handling:** User-friendly error messages and recovery options

### 7. RESTful API Architecture

- **Comprehensive Endpoints:** Full CRUD operations for all entities
- **Search & Filter:** Advanced querying capabilities
- **File Upload:** Multipart form data handling for images
- **Validation:** Real-time availability checking for IDs and emails
- **Health Monitoring:** System health check endpoints
- **Error Handling:** Structured error responses with meaningful messages

### 8. Session Scheduling Safeguards
- **Minimum Duration:** Every session must be at least 60 minutes long with a future start/end time.
- **Start Window Enforcement:** Sessions can only be opened within 15 minutes of the scheduled start.
- **Per-Course Professor Guardrails:** When a professor teaches multiple sections of the *same* course, the system blocks overlapping sessions—new start times must be later than any existing session’s end time for that course.
- **Section-Level Overlap Checks:** Sections cannot host two sessions that collide on the same date/time window.

### 9. Role-Based Controls & Auto Session Policies
- **Invite-only Access:** Every role (admin, professor, TA) requires a role-specific invite code before an account can be created—no public signups.
- **Professor Scope:** Professors may only manage sessions/sections assigned to them; they cannot access other instructors’ rosters.
- **Teaching Assistant Permissions:** TAs can assist with attendance for their sections but cannot create/close sessions or edit course metadata.
- **Automatic Ownership Checks:** Every session action (create/start/close, roster export, recognition access) verifies the requester’s role + assignment, preventing privilege escalation.

## 📚 Documentation

- [Documentation/ProjectStructure.md](Documentation/ProjectStructure.md) — Java package walkthrough, responsibilities, and architecture diagram.
- [Documentation/API.md](Documentation/API.md) — Complete list of REST endpoints with payloads and usage notes.
- [Documentation/Features.md](Documentation/Features.md) — High-level capabilities and UX overview.
- [Documentation/Infrastructure.md](Documentation/Infrastructure.md) — Mapping of every package (config/controller/entity/repository/security/service) to the concrete classes.
- [Documentation/Database.md](Documentation/Database.md) — Table specs, views, and stored procedures pulled from `smart_attendance_system.sql`.
- [Documentation/Relationship.md](Documentation/Relationship.md) — Mermaid diagrams summarizing package-to-package dependencies.
- [Documentation/FacialAlgorithms.md](Documentation/FacialAlgorithms.md) — Face recognition algorithm deep dive (HTML → JS → Spring → ONNX) covering live recognition, photo validation, and mask prompting flows.
- [Documentation/Java.md](Documentation/Java.md) — How plain Java concepts (collections, records, DI, concurrency) show up across controllers, services, and repositories.

## ⚠️ Edge Cases & Guardrails

- **Invite-only signup:** Accounts can only be created with the correct role-specific invitation code—no open registration.
- **Unique student identity:** Student IDs and emails are globally unique; duplicates are rejected at both the UI and API layers.
- **Course uniqueness:** Course codes and course names must be unique across the catalog, preventing look-alike modules.
- **No backdating:** Sessions cannot be created for dates in the past, and “today” sessions must use one of the defined slots that end by 10:15 PM.
- **Start-window enforcement:** A session can only be started within the 15-minute window leading up to its scheduled start time.
- **Auto-close behavior:** Closing a session automatically marks any unrecorded students as `ABSENT`.
- **Recognition resilience:** Low-confidence detections keep retrying; unresolved faces fall back to the review queue for manual action.
- **Mask-aware training:** Every uploaded image is validated (face present, correct framing, human subject). If no real mask is detected, masked variants are generated automatically for training coverage.
- **Section exclusivity:** A student cannot be enrolled in more than one section of the same course at the same time.
- **Roster refresh after enrolment:** Adding students to a section after a session is created automatically syncs those enrolments into the session roster, so attendance counts remain accurate.
- **Professor scheduling guardrails:** A professor cannot be double-booked; overlapping sessions (regardless of course or section) are blocked with details about the conflicting slot.
- **Scoped access:** Professors and teaching assistants only see sections/sessions to which they are explicitly assigned; admins see everything.

## 🔒 Security Features

- BCrypt password hashing with complexity validation
- Invite codes per role + Basic auth headers for APIs
- Server-side validation for student/session data and uploads
- Attendance audit log (`logs/attendance.log`) for traceability

## Video Demonstration 
- https://youtu.be/HF2XzEuIUFc
