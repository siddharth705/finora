// Shared divider between the social sign-in buttons and the email/password form -- duplicated
// identically across IdentifyStep/PasswordStep/RegisterStep before this. Styled as a quieter,
// more spaced-out label (uppercase, tracked-out, smaller) rather than a plain "OR", so it reads as
// a section break instead of competing for attention with the heavy black buttons above it.
// Copy says "continue below" (not just "or") since the social buttons now come first -- this is
// what points the user at the form below rather than a bare "or" pointing at nothing. Deliberately
// NOT "continue with email": the field below accepts an email OR a mobile number (see its own
// "Email or mobile number" label), so naming just one would undersell the other.
export function AuthDivider() {
  return (
    <div className="flex items-center gap-3 my-6">
      <div className="flex-1 h-px bg-border/70" />
      <span className="text-[11px] font-medium tracking-wider text-muted uppercase">Or continue below</span>
      <div className="flex-1 h-px bg-border/70" />
    </div>
  );
}
