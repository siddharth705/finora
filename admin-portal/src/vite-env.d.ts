/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend origin for links that can't go through the /api proxy (Swagger, Actuator) -- see
   *  Diagnostics.tsx. Everything else in this app uses relative /api paths instead. */
  readonly VITE_BACKEND_ORIGIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
