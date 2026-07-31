/// <reference types="vite/client" />

// Bug fix: BankLogo.tsx already used `import.meta.glob` (added earlier this session) with no
// `vite/client` type reference anywhere in the project -- `tsc -b` (part of `npm run build`,
// see package.json) would have failed with "Property 'glob' does not exist on type 'ImportMeta'"
// the first time anyone actually ran a real build. This file is what every standard Vite+React
// scaffold ships with; it was simply missing here.
interface ImportMetaEnv {
  // Optional on purpose -- see BankLogo.tsx's own comment on why an unset client ID just skips
  // the Brandfetch stage of the logo provider chain rather than being treated as an error.
  readonly VITE_BRANDFETCH_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
