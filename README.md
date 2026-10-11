# Digital Explorer

Android overlay bot for the Digimon Up grid mini-game. It screenshots the grid (MediaProjection), classifies each
cell by colour (orange = item, purple pyramid = block, else ground), sweeps column by column (left-most first, nearest first) with a BFS and taps the
adjacent cell (via an Accessibility Service) so the Digimon walks into it. With nothing visible it steps right to scroll.

## Build
Open in Android Studio, or push — GitHub Actions builds `app-debug.apk` (artifact `digital-explorer-debug-apk`). Needs Android 11+.

## Use
1. Open app → allow draw-over-apps, enable the **Digital Explorer** accessibility service, tap **Start overlay**.
2. Open the mini-game grid. In the floating panel: **Calibrate** (drag over the whole grid),
   **Labels** (toggle: marks every cell ITEM / BLOCK / visited / YOU, outlines the target in red and shows the next move arrow), **Start / Stop**.

The Digimon is found automatically from its red cape. Defaults (5x5 grid) match `docs/sample-screenshot.png`. Keep the panel off the grid.
