# Task Plan: 4 Companion Tool Apps for LLM Intentions

## Goal
Build 4 Android tool apps that extend `ToolAppService` and are discoverable by LLM Intentions Hub via the broadcast Intent protocol. These are the launch apps for the Play Store.

---

## Phase 1: Device Tool App (`tool-device`)
**Namespace:** `device`
**Permissions:** None required
**Priority:** Build first — zero friction, immediate value

### Tools (15):
| Tool | API | Silent |
|------|-----|--------|
| `battery_status` | BatteryManager sticky broadcast | Yes |
| `device_info` | Build.MODEL, VERSION, etc. | Yes |
| `storage_info` | StatFs for free/total storage | Yes |
| `memory_info` | ActivityManager.MemoryInfo | Yes |
| `clipboard_read` | ClipboardManager.getPrimaryClip() | Yes (toast on 12+) |
| `clipboard_write` | ClipboardManager.setPrimaryClip() | Yes |
| `flashlight_on` | CameraManager.setTorchMode(true) | Yes |
| `flashlight_off` | CameraManager.setTorchMode(false) | Yes |
| `vibrate` | Vibrator.vibrate(VibrationEffect) | Yes |
| `volume_get` | AudioManager.getStreamVolume() | Yes |
| `volume_set` | AudioManager.setStreamVolume() | Yes |
| `ringer_mode` | AudioManager.getRingerMode/setRingerMode | Yes |
| `screen_brightness` | Settings.System.SCREEN_BRIGHTNESS | Yes (read) / needs WRITE_SETTINGS (set) |
| `tts_speak` | TextToSpeech.speak() | Yes (plays audio) |
| `sensor_read` | SensorManager — accel, gyro, compass, light, pressure, proximity | Yes |

### Files to create:
- `tool-device/build.gradle.kts`
- `tool-device/src/main/AndroidManifest.xml`
- `tool-device/src/main/kotlin/com/llmintentions/device/DeviceToolService.kt`
- `tool-device/src/main/kotlin/com/llmintentions/device/DeviceActivity.kt` (minimal launcher)

### Status: [ ] Not started

---

## Phase 2: Contacts + Calendar Tool App (`tool-people`)
**Namespace:** `people`
**Permissions:** READ_CONTACTS, WRITE_CONTACTS, READ_CALENDAR, WRITE_CALENDAR
**Priority:** Second — universal appeal, standard permissions

### Tools (12):
| Tool | API | Silent |
|------|-----|--------|
| `contacts_search` | ContactsContract query by name/number | Yes |
| `contacts_list` | ContactsContract.Contacts query (paginated) | Yes |
| `contact_details` | ContactsContract.Data query by ID | Yes |
| `contact_add` | ContentResolver.insert(RawContacts + Data) | Yes |
| `contact_delete` | ContentResolver.delete(contactUri) | Yes |
| `calendar_events` | CalendarContract.Events query (date range) | Yes |
| `calendar_today` | CalendarContract.Events query (today) | Yes |
| `event_create` | ContentResolver.insert(Events) | Yes |
| `event_update` | ContentResolver.update(eventUri) | Yes |
| `event_delete` | ContentResolver.delete(eventUri) | Yes |
| `calendars_list` | CalendarContract.Calendars query | Yes |
| `set_alarm` | AlarmClock.ACTION_SET_ALARM + SKIP_UI | Yes |

### Files to create:
- `tool-people/build.gradle.kts`
- `tool-people/src/main/AndroidManifest.xml`
- `tool-people/src/main/kotlin/com/llmintentions/people/PeopleToolService.kt`
- `tool-people/src/main/kotlin/com/llmintentions/people/PeopleActivity.kt` (permission request UI)

### Status: [ ] Not started

---

## Phase 3: Files Tool App (`tool-files`)
**Namespace:** `files`
**Permissions:** READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO (Android 13+) or READ_EXTERNAL_STORAGE (older)
**Priority:** Third — Claude needs file access

### Tools (10):
| Tool | API | Silent |
|------|-----|--------|
| `app_files_list` | context.filesDir listing | Yes |
| `app_file_read` | Read from filesDir/cacheDir | Yes |
| `app_file_write` | Write to filesDir | Yes |
| `app_file_delete` | Delete from filesDir | Yes |
| `media_images` | MediaStore.Images query (recent, by name) | Yes |
| `media_videos` | MediaStore.Video query | Yes |
| `media_audio` | MediaStore.Audio query | Yes |
| `downloads_list` | MediaStore.Downloads query (Android 10+) | Yes |
| `download_file` | DownloadManager.enqueue() | Yes |
| `file_info` | ContentResolver metadata for any content:// URI | Yes |

### Files to create:
- `tool-files/build.gradle.kts`
- `tool-files/src/main/AndroidManifest.xml`
- `tool-files/src/main/kotlin/com/llmintentions/files/FilesToolService.kt`
- `tool-files/src/main/kotlin/com/llmintentions/files/FilesActivity.kt` (permission request UI)

### Status: [ ] Not started

---

## Phase 4: Notifications Tool App (`tool-notify`)
**Namespace:** `notify`
**Permissions:** NotificationListenerService (special Settings toggle)
**Priority:** Fourth — premium feature, killer capability

### Tools (8):
| Tool | API | Silent |
|------|-----|--------|
| `notifications_list` | NotificationListenerService.getActiveNotifications() | Yes |
| `notification_details` | Parse notification key → extras, text, actions | Yes |
| `notification_dismiss` | cancelNotification(key) | Yes |
| `notification_dismiss_all` | cancelAllNotifications() | Yes |
| `notification_reply` | Extract RemoteInput from action, send reply | Yes |
| `notification_history` | In-memory log of recent notifications (last 100) | Yes |
| `notification_filter` | Search notifications by app/text/time | Yes |
| `notification_watch` | Toggle persistent monitoring, store to history | Yes |

### Files to create:
- `tool-notify/build.gradle.kts`
- `tool-notify/src/main/AndroidManifest.xml`
- `tool-notify/src/main/kotlin/com/llmintentions/notify/NotifyToolService.kt`
- `tool-notify/src/main/kotlin/com/llmintentions/notify/NotifyListenerService.kt`
- `tool-notify/src/main/kotlin/com/llmintentions/notify/NotifyActivity.kt` (Settings toggle guide)
- `tool-notify/src/main/res/xml/notification_listener.xml` (service config)

### Status: [ ] Not started

---

## Phase 5: Integration + Launch Prep
- [ ] Add all 4 modules to settings.gradle.kts
- [ ] Verify Hub discovers all apps simultaneously
- [ ] Test tool calls through Hub → Claude Code for each app
- [ ] Build release APKs
- [ ] Play Store listings (screenshots, descriptions)

---

## Decisions
- **textTool helper:** Move from Taichi's companion object into `mcp-intent-api` as a public extension so all tool apps can use it
- **Package naming:** `com.llmintentions.device`, `com.llmintentions.people`, `com.llmintentions.files`, `com.llmintentions.notify`
- **Activity pattern:** Each app gets a minimal Activity that requests permissions (if needed) and shows status. No Activity needed for tool-device since it has zero permissions.
- **Sensor reads:** Use a one-shot pattern — register listener, get one reading, unregister. Don't leave sensors running.
