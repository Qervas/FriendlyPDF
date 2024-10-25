# Friendly PDF Reader

An accessible PDF reader focused on readability and text-to-speech capabilities. This application is designed to make PDF reading more user-friendly and includes a bookshelf feature for better document management.

## Features

### Core Features

- **PDF Viewing**: Smooth PDF document rendering with page navigation
- **Text-to-Speech**: Built-in reader that converts text to speech
- **Sleep Timer**: 
  - Preset options for quick setup
  - Custom timer with HH:MM:SS format
  - Persistent notification showing remaining time
- **Multi-language Support**:
  - English
  - German (Deutsch)
  - French (Français)
  - Chinese (简体中文)
  - Swedish (Svenska)

### Reading Assistance

- **Progress Tracking**: Shows reading progress and estimated time remaining
- **Media Controls**:
  - Play/Pause
  - Next/Previous sentence navigation
  - Speed adjustment
  - Progress bar navigation
  - Sleep timer control

### User Interface

- **Dark/Light Theme**:
  - System theme detection
  - Manual theme toggle
  - Persistent theme settings
- **Floating Action Menu**: Quick access to core functions, including bookshelf
- **Last Read Position**: Remembers the last opened PDF and position
- **System Media Controls**: Integration with system notification media controls

### Bookshelf Feature

- **Document Management**: Organize and access your PDFs from a central bookshelf
- **Thumbnails**: Visual representation of PDF documents
- **Reading Progress**: Tracks and displays the last read page for each document
- **Quick Access**: Open PDFs directly from the bookshelf

## Current Improvements

- Enhanced sleep timer with custom time input
- System media controls integration
- Persistent timer notification
- Improved media playback controls

## Future Improvements

- Enhanced PDF rendering
- More language support
- Better text extraction
- Improved theme handling
- Bookmark functionality
- Annotation support

## Technical Notes

- Built for Android 14 or newer
- Uses PDFBox for text extraction
- Implements Android's TextToSpeech
- Requires Android API level 34
- Utilizes Room database for bookshelf management
- Integrates with Android MediaSession for system-wide media controls

## Feedback

While this application has seen significant improvements, it's still under active development. Feedback and suggestions are welcome for future enhancements.

---

*Note: This is a development version and may contain bugs or incomplete features.*
