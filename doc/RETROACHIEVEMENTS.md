# RetroAchievements Integration for Mupen64Plus-AE

## Overview

This document describes the comprehensive RetroAchievements integration for Mupen64Plus-AE, an Android N64 emulator. The integration provides first-class support for achievements, leaderboards, rich presence, and hardcore mode.

## Architecture

### 1. Native Layer (C/JNI)

**Module**: `rcheevos/`
- **Library**: rcheevos C library from RetroAchievements
- **Source**: `rcheevos/src/main/cpp/` (include/ and src/)
- **JNI Bridge**: `rcheevos/src/main/jni/rcheevos_jni.c`
- **Build**: Android NDK (ndk-build) via `rcheevos/Android.mk`

**Key Components**:
- `rc_client_t`: Main rcheevos client structure
- `rc_hash`: Game identification via RetroAchievements hash
- Memory peek callbacks: Read N64 emulator memory
- Server call callbacks: HTTP request hooks

**Compilation**:
```
LOCAL_CFLAGS := $(COMMON_CFLAGS) -DRC_DISABLE_LUA
```
Compiles for: armeabi-v7a, arm64-v8a, x86, x86_64

### 2. Java Service Layer

**Package**: `paulscode.android.mupen64plusae.retroachievements`

**Core Classes**:

1. **RCheevosNative.java**
   - JNI wrapper for native rcheevos library
   - Provides Java interface to C functions
   - Callback interface for memory reads and HTTP requests

2. **RetroAchievementsManager.java**
   - Singleton manager for all RA functionality
   - Implements RCheevosCallback interface
   - Manages native client lifecycle
   - Coordinates memory reads with emulator core
   - Dispatches HTTP requests via HTTP client

3. **RetroAchievementsHttpClient.java**
   - Asynchronous HTTP client for RA API
   - Uses ExecutorService for background requests
   - Supports GET and POST methods
   - Delivers responses back to native callbacks

### 3. Data Persistence

**Class**: `AppData.java`

**SharedPreferences Keys**:
- `retroachievementsUsername`: RA username
- `retroachievementsToken`: RA authentication token
- `retroachievementsEnabled`: Global enable/disable flag
- `retroachievementsHardcore`: Hardcore mode flag

**Methods**:
- `getRetroAchievementsUsername()` / `setRetroAchievementsUsername(String)`
- `getRetroAchievementsToken()` / `setRetroAchievementsToken(String)`
- `isRetroAchievementsEnabled()` / `setRetroAchievementsEnabled(boolean)`
- `isRetroAchievementsHardcore()` / `setRetroAchievementsHardcore(boolean)`
- `clearRetroAchievementsCredentials()`

## Integration Points

### Required Integrations (TODO)

#### 1. CoreService Integration
**File**: `app/src/main/java/paulscode/android/mupen64plusae/jni/CoreService.java`

**Required Changes**:
- Initialize RetroAchievementsManager in `onCreate()`
- Start RA session in `onCoreStart()` with ROM hash
- Call `doFrame()` every emulation frame
- Cleanup in `onDestroy()`
- Implement memory read callback

**Example**:
```java
private RetroAchievementsManager mRAManager;

@Override
public void onCreate() {
    super.onCreate();
    mRAManager = RetroAchievementsManager.getInstance(this);
    mRAManager.initialize();
}

private void onCoreStart() {
    // Generate ROM hash and load game session
    String hash = mRAManager.generateHash(romPath);
    // TODO: Call rc_client_begin_identify_and_load_game
}

// In emulation loop
private void runEmulationFrame() {
    // ... existing emulation code ...
    mRAManager.doFrame();
}
```

#### 2. Memory Read Hook
**File**: `app/src/main/java/paulscode/android/mupen64plusae/jni/CoreInterface.java`

**Required Changes**:
- Add JNI method to read N64 memory
- Implement callback from RetroAchievementsManager

**Example**:
```java
public static byte[] readMemory(int address, int numBytes) {
    // Call native CoreInterface method to read memory
    return nativeReadMemory(address, numBytes);
}

private native byte[] nativeReadMemory(int address, int numBytes);
```

#### 3. Hardcore Mode Enforcement
**Files**: Various service and UI files

**Required Changes**:
- Disable save state loading when hardcore enabled
- Disable cheats when hardcore enabled
- Disable fast forward/slow motion when hardcore enabled
- Show UI indicators for hardcore mode

#### 4. Settings UI
**New File**: `RetroAchievementsPrefsActivity.java`

**Features**:
- Login/logout functionality
- Username and password input
- Hardcore mode toggle
- Enable/disable toggle
- Show user stats and profile

#### 5. Achievement Notifications
**New File**: `RetroAchievementsNotifications.java`

**Features**:
- Achievement unlock popups
- Leaderboard submission notifications
- Game placard on load
- Toast notifications
- Notification sounds

## Features

### Core Features
- [x] Native rcheevos library integration
- [x] JNI bridge with callbacks
- [x] HTTP client for API communication
- [x] Manager singleton with lifecycle
- [x] Data persistence (credentials, settings)
- [ ] User authentication (login/logout)
- [ ] Game identification via hash
- [ ] Achievement tracking
- [ ] Leaderboard submission
- [ ] Rich presence

### UI Features
- [ ] Settings/preferences page
- [ ] Login screen
- [ ] Achievement list view
- [ ] Leaderboard view
- [ ] User profile view
- [ ] Achievement unlock notifications
- [ ] Game placard
- [ ] Hardcore mode indicator

### Advanced Features
- [ ] Offline achievement tracking
- [ ] Achievement progress indicators
- [ ] Badge/icon downloads and caching
- [ ] Mastery badge display
- [ ] Achievement rarity
- [ ] Session statistics
- [ ] Vibration on unlock
- [ ] Notification sounds

## API Communication

### RetroAchievements API Endpoints

The rcheevos library handles API URL construction. The app provides HTTP transport:

1. **Login**: POST to `/login`
2. **Game Info**: GET `/gameinfo`
3. **Achievement Unlock**: POST `/awardachievement`
4. **Leaderboard Submit**: POST `/submitlbentry`
5. **Rich Presence**: POST `/ping`

### User-Agent Format
```
Mupen64PlusAE/<version> (Android <version>) rcheevos/<version>
```

## Security Considerations

1. **Credentials**: Stored in SharedPreferences (consider encryption)
2. **Token-based auth**: Never store passwords, use tokens
3. **HTTPS**: All API calls must use HTTPS
4. **Hardcore validation**: Server-side validation required
5. **Anti-cheat**: Memory integrity checks

## Building

### Requirements
- Android NDK
- Gradle 8.14.3+
- Android SDK API 24+

### Build Commands
```bash
./gradlew :rcheevos:build      # Build rcheevos module
./gradlew :app:assembleDebug   # Build app with RA support
```

### Debugging
- Native logs: `adb logcat | grep RCheevosJNI`
- Java logs: `adb logcat | grep RAManager`
- HTTP logs: `adb logcat | grep RAHttpClient`

## Testing

### Test Plan
1. **Native layer**: Verify library loads and callbacks work
2. **HTTP client**: Test API communication
3. **Memory reads**: Verify emulator memory accessible
4. **Game loading**: Test hash generation and game identification
5. **Achievements**: Trigger and unlock test achievements
6. **Hardcore mode**: Verify restrictions enforced
7. **Offline mode**: Test offline queue and sync

### Test ROMs
Use RetroAchievements test ROM for N64 when available.

## References

- **rcheevos**: https://github.com/RetroAchievements/rcheevos
- **RA Docs**: https://docs.retroachievements.org/
- **RA API**: https://api-docs.retroachievements.org/
- **rc_client Integration**: https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration

## Roadmap

### Phase 1: Foundation (Completed)
- ✅ Native library integration
- ✅ JNI bridge
- ✅ HTTP client
- ✅ Manager class
- ✅ Data persistence

### Phase 2: Core Functionality (Next)
- User login/authentication
- Game session management
- Memory read integration
- Achievement processing

### Phase 3: UI (Future)
- Settings page
- Achievement list
- Notifications
- Game placard

### Phase 4: Advanced Features (Future)
- Rich presence
- Leaderboards
- Offline support
- Statistics

## License

This integration uses the rcheevos library which is MIT licensed.
The integration code follows Mupen64Plus-AE's GPL license.

## Contributors

- Mupen64Plus-AE Team
- RetroAchievements Community
- rcheevos Library Authors
