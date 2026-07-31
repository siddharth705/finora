/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend origin for links that can't go through the /api proxy (Swagger, Actuator) -- see
   *  Diagnostics.tsx. Everything else in this app uses relative /api paths instead. */
  readonly VITE_BACKEND_ORIGIN?: string;
  /** The backend's own absolute origin for the actual axios client (src/api/client.ts) --
   *  distinct from VITE_BACKEND_ORIGIN above, which is only for a couple of manually-built
   *  human-facing links. Optional: unset falls back to a same-origin relative path, correct for
   *  local dev (Vite's dev-server proxy handles it) and wrong for any real deployment where this
   *  app and the backend don't share an origin. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
