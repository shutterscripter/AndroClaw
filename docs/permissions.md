# Permissions

AndroClaw requests permissions as needed for its tools. All permissions are optional — features degrade gracefully if denied.

## Android Manifest Permissions

### Network
| Permission | Used By |
|-----------|---------|
| `INTERNET` | All LLM API calls, web search, web fetch |
| `ACCESS_NETWORK_STATE` | Device info (network status) |

### Communication
| Permission | Used By |
|-----------|---------|
| `SEND_SMS` | send_sms tool |
| `READ_SMS` | read_sms tool |
| `CALL_PHONE` | make_phone_call tool |
| `READ_CALL_LOG` | call_log tool |
| `READ_CONTACTS` | get_contacts, contact resolution |
| `WRITE_CONTACTS` | Contact creation (if needed) |
| `READ_PHONE_STATE` | Device info |

### Location
| Permission | Used By |
|-----------|---------|
| `ACCESS_FINE_LOCATION` | get_location tool (high accuracy) |
| `ACCESS_COARSE_LOCATION` | get_location tool (low accuracy) |

### Connectivity
| Permission | Used By |
|-----------|---------|
| `ACCESS_WIFI_STATE` | Device info, toggle_setting |
| `CHANGE_WIFI_STATE` | toggle_setting (wifi) |
| `BLUETOOTH` | toggle_setting (bluetooth) |
| `BLUETOOTH_CONNECT` | toggle_setting (bluetooth) |

### Calendar
| Permission | Used By |
|-----------|---------|
| `READ_CALENDAR` | create_calendar_event tool |
| `WRITE_CALENDAR` | create_calendar_event tool |

### Storage
| Permission | Used By |
|-----------|---------|
| `READ_MEDIA_IMAGES` | file_manager tool (Android 13+) |
| `READ_MEDIA_VIDEO` | file_manager tool (Android 13+) |
| `READ_MEDIA_AUDIO` | file_manager tool (Android 13+) |
| `READ_EXTERNAL_STORAGE` | file_manager tool (Android 12 and below) |
| `MANAGE_EXTERNAL_STORAGE` | file_manager tool (broad access) |

### System
| Permission | Used By |
|-----------|---------|
| `SYSTEM_ALERT_WINDOW` | Floating button overlay |
| `FOREGROUND_SERVICE` | Floating button service |
| `RECEIVE_BOOT_COMPLETED` | Auto-start floating button on boot |
| `VIBRATE` | Notifications |
| `POST_NOTIFICATIONS` | Schedule results, reminders |
| `SET_ALARM` | set_alarm tool |
| `MODIFY_AUDIO_SETTINGS` | media_control (volume) |

### Media
| Permission | Used By |
|-----------|---------|
| `RECORD_AUDIO` | Voice input (speech-to-text) |
| `CAMERA` | Reserved for future camera tools |

### Analytics
| Permission | Used By |
|-----------|---------|
| `PACKAGE_USAGE_STATS` | screen_time tool |
| `QUERY_ALL_PACKAGES` | list_apps, open_app (app discovery) |

## Special Permissions (Manual Grant)

These require the user to navigate to Android Settings:

| Permission | Setting Path | Used By |
|-----------|-------------|---------|
| Accessibility Service | Settings > Accessibility > AndroClaw | control_app_ui, take_screenshot, auto_scroll_feed |
| Notification Access | Settings > Notification Access > AndroClaw | notifications (read action) |
| Usage Access | Settings > Usage Access > AndroClaw | screen_time tool |
| Display Over Other Apps | Settings > Apps > Special Access > Display Over Other Apps | Floating button |

## Google Play Restrictions

The `play` build flavor excludes tools that require restricted permissions:
- `read_sms` — requires SMS permission (Google Play restricted)
- `call_log` — requires Call Log permission (Google Play restricted)

These tools are fully available in the `thirdParty` build flavor for sideloaded APKs.
