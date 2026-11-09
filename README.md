# Field Notes Publisher

Windows-oriented publisher for preparing offline wildlife packs. It uses five real individual tracks from the MIT-licensed Migration Watch visualization dataset and preserves the source study, license, timestamps, and coordinates.

## Run

```powershell
npm install
npm run dev
```

Open the local Vite URL in a browser on Windows. The publisher catalog includes a blue whale, green sea turtle, caribou, California sea lion, and waved albatross. Select an animal, edit its display fields, generate one or more `WILD-XXXX-XXXX` codes, then choose **Export .wildpack**. If no code has been generated, export still produces a valid pack for the selected animal.

## Build

```powershell
npm run build
npm run preview
```

The current desktop deliverable is a local Vite publisher rather than a packaged Tauri executable. The pack is a ZIP container with a `.wildpack` extension containing `manifest.json`, profile JSON, track JSON, and attribution text.

Tracking records are historical observations from Migration Watch / Movebank datasets. They are not live locations. Upstream license text is preserved in `UPSTREAM-LICENSE`.