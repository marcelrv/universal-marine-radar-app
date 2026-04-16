

#  SPECIFICATION: Marine Radar Android App 

## 1. Project Overview
A native Android application designed to visualize and control marine radars via the Rust-based `mayara-server` https://github.com/MarineYachtRadar/mayara-server. 
The app features a **Dual-Mode Architecture** supporting both standalone direct-to-hardware connections (via an embedded Rust server) and network connections (SignalK/remote `mayara-server`).

## 2. System Architecture & Hardware Synchronization
*   **Backend Integration**: The `mayara-server` is compiled as a shared library (`libradar.so`) and executed directly within the Android app’s memory space using JNI. This ensures secure, leak-free lifecycle management without zombie processes.
*   **Dynamic Hardware Capabilities**: The frontend **must not hardcode** ranges or available controls. It must act as a dynamic client to the hardware.
    *   *Handshake Phase*: Upon connection, the app makes an initial API call (or parses the SignalK schema) to fetch the specific radar's supported features and range array.
    *   *Feature Toggling*: If a radar does not support a specific feature (e.g., Rain Clutter), the corresponding UI element must be strictly hidden or grayed out with an "Unsupported" visual indicator.
*   **Connection Logic**: On startup, the app scans for both network servers (mDNS) and local hardware. If multiple options exist, a modal prompts the user to select the connection type, featuring a "Remember my choice" checkbox to bypass the prompt in future sessions.

## 3. Detailed UI/UX Specification

### 3.1. Visual Look & Feel
*   **Design Paradigm**: Modern marine chartplotter aesthetic.
*   **Color Scheme**: Deep dark mode by default to preserve night vision.
    *   *Background*: Deep charcoal/black (`#0B0C10`).
    *   *Accents*: High-visibility marine green or amber for active elements.
    *   *Typography*: Clean, sans-serif fonts (Roboto/Inter). Monospace fonts are strictly used for changing numbers (coordinates, ranges) to prevent layout jitter.
*   **Night Mode Toggle**: Supports a pure "Red/Black" color palette mode for zero-light preservation.

### 3.2. Main Screen Layout & Canvas (OpenGL ES)
*   **Radar Canvas**: Occupies 100% of the screen background. Driven by **OpenGL ES** via `GLSurfaceView` to handle the high throughput of radar sweep/spoke data (thousands of bytes per second) without dropping frames or draining the battery.
*   **Gestures**: 
    *   *Pan*: One-finger drag to offset the radar center (Look-ahead mode).
    *   *Reset*: Double-tap resets the radar sweep to the center of the screen.
    *   *Zoom constraint*: **Pinch-to-zoom is strictly disabled.** For safety-critical marine scaling, zooming is restricted exclusively to the `+` and `-` UI buttons to guarantee the visual scale perfectly matches the hardware's active Pulse Repetition Frequency (PRF) and range.

### 3.3. Primary Controls (Always Visible Overlays)
These controls sit directly on top of the OpenGL canvas with semi-transparent backgrounds.
*   **Power/State Control (Top Right)**: A pill-shaped toggle displaying the current state: `OFF` -> `WARMUP` (with countdown timer) -> `STANDBY` -> `TRANSMIT`. 
*   **Range Controls (Bottom Right)**: 
    *   Large `+` (Zoom In) and `-` (Zoom Out) floating action buttons.
    *   Between the buttons, a clear text display of the current active range (e.g., "1.5 NM").
    *   Pressing `+` or `-` steps *only* through the specific range values provided by the hardware handshake.
*   **HUD Overlay (Top Left)**: Heading, Speed Over Ground (SOG), and Course Over Ground (COG) if NMEA/SignalK data is present.

### 3.4. Advanced Radar Controls (The "Quick Action" Bottom Sheet)
*Purpose: Fast access to tweak the radar image without leaving the radar view.*
*   **Access**: Swiping up from the bottom edge or tapping a "Radar Settings" slider icon opens a semi-transparent Bottom Sheet overlay.
*   **Content** (Populated based on hardware capabilities):
    *   **Gain**: Horizontal slider. Adjacent toggle button for [Auto / Man]. *When "Auto" is selected, the manual slider remains visible but is grayed out/disabled.*
    *   **Sea Clutter (STC)**: Horizontal slider. Adjacent toggle for [Auto / Man]. *When "Auto" is selected, the manual slider is grayed out/disabled.*
    *   **Rain Clutter (FTC)**: Horizontal slider. 
    *   **Interference Rejection**: Dropdown/Segmented button (Off, Low, Medium, High).
    *   **Visual Presentation**:
        *   *Palette Selector*: Green, Yellow, Multi-color (Echo strength), Night-Vision Red.
        *   *Orientation*: Head-Up (HU), North-Up (NU) (requires heading data), Course-Up (CU).

### 3.5. App Config & Server Settings (Distinct System Menu)
*Purpose: Hard configuration of the application and connection. Kept strictly separate from active radar manipulation.*
*   **Access**: A standard "Hamburger" menu or "Gear" icon in the top-left corner opens a traditional full-screen Android Settings Activity.
*   **Content**:
    *   **Connection Manager**:
        *   Active Connection Status.
        *   Button to "Switch Connection" (re-triggers the Network vs. Standalone prompt).
        *   Manual IP/Port override for network connections.
    *   **Embedded Server Status**:
        *   View internal `mayara-server` logs (for hardware debugging).
        *   Hardware interface selection (e.g., specifically binding to an Ethernet-to-USB adapter IP).
    *   **Units & Formats**: Distance units (NM, KM, SM), bearing types (True vs Magnetic).
    *   **App Info**: Version, license, radar firmware version (if retrievable).

## 4. Technical Stack Guidelines (For Coding Agent)
*   **Language**: Kotlin (Frontend), Rust (Backend/Embedded via JNI).
*   **UI Framework**: Jetpack Compose for all overlays, buttons, bottom sheets, and the Settings Activity.
*   **Rendering Core**: OpenGL ES (Custom `GLRenderer` in Kotlin) mapping WebSocket byte streams to 2D textures.
*   **Networking**: `OkHttp3` for HTTP REST (Capabilities API, Control commands) and WebSockets (Real-time Spoke data).
*   **Service Discovery**: `android.net.nsd.NsdManager` for mDNS detection of `_signalk-ws._tcp` or `mayara` equivalent.
*   **Concurrency**: Kotlin Coroutines (`StateFlow`) to safely map radar hardware state and capabilities to the Compose UI State.
