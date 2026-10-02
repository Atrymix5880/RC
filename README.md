# RC – Remote Control

Android host app for controlling and viewing the Android screen from an iPad in Safari over the local network.

## Flow
1. Install RC on Android.
2. Enable the RC accessibility service in Android settings.
3. Start the server and approve MediaProjection.
4. Connect the iPad to the same local Wi-Fi/hotspot.
5. Scan the displayed QR code or open the shown URL in Safari.

The transport uses WebRTC for the screen/audio stream and a WebRTC data channel for local input commands. No cloud relay is configured.

Android system restrictions still apply: MediaProjection requires explicit approval and accessibility control must be explicitly enabled by the user.
