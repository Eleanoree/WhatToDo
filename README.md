# WhatToDo

WhatToDo is a native Android to-do app focused on helping you plan the day, stay in rhythm, and review progress over time. The app combines task management, schedule views, focus sessions, analytics, and optional Firebase cloud sync in a single Kotlin-based project.

## Features

- Task management with create, edit, delete, search, and swipe actions
- Category and tag management for better organization
- Schedule view with month navigation and daily task details
- Focus timer with preset and custom focus cycles
- Focus session history tracking
- Data dashboard for completion trends and focus activity
- Optional Google account sign-in and Firebase / Firestore cloud sync
- Local persistence with Room database

## Tech Stack

- Kotlin + Android Views
- Material Design components
- Room for local storage
- Kotlin Coroutines and Flow
- Firebase Authentication
- Firebase Firestore

## Project Structure

```text
app/src/main/java/com/example/whattodo/
|- ShellActivity.kt          # Bottom navigation host
|- MainActivity.kt           # Home tab / task management fragment
|- BrandTabFragment.kt       # Schedule, data, and settings tabs
|- FocusFragment.kt          # Focus timer experience
|- TaskRepository.kt         # Data access, analytics, and sync orchestration
|- FirebaseAccountManager.kt
|- FirebaseCloudSyncManager.kt
```

## Requirements

- Android Studio with Android SDK installed
- JDK 11
- Minimum Android SDK 24
- Target Android SDK 36

## Getting Started

1. Clone the repository.
2. Open the project in Android Studio.
3. Let Gradle sync finish.
4. Run the `app` configuration on an emulator or Android device.

## Firebase Setup

Firebase is optional for local development, but required if you want Google sign-in and cloud sync.

1. Create a Firebase project.
2. Add an Android app with package name `com.example.whattodo`.
3. Enable Google Sign-In in Firebase Authentication.
4. Enable Cloud Firestore.
5. Download `google-services.json`.
6. Place the file at `app/google-services.json` locally.
7. Add your SHA-1 fingerprint in the Firebase project settings.

The `google-services.json` file is intentionally ignored by git and should not be committed to a public repository.

## Notes

- The project seeds some demo or preview data to support the in-app experience during development.
- If Firebase is not configured, local task management and focus features still work, but account binding and cloud sync will not.

## Future Improvements

- Add screenshots or a short demo GIF
- Add unit tests and UI tests for critical flows
- Add CI for linting and build verification
- Split large UI classes into smaller feature modules

## License

No license has been added yet. If you plan to make this repository public, consider adding a license file that matches how you want others to use the project.
