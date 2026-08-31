// Shared "OR" divider between the email/password form and the social sign-in buttons --
// duplicated identically across IdentifyStep/PasswordStep/RegisterStep before this. Styled as a
// quieter, more spaced-out label (uppercase, tracked-out, smaller) rather than a plain "OR", so it
// reads as a section break instead of competing for attention with the heavy black buttons below.
export function AuthDivider() {
  return (
    <div className="flex items-center gap-3 my-6">
      <div className="flex-1 h-px bg-border/70" />
      <span className="text-[11px] font-medium tracking-wider text-muted uppercase">Or</span>
      <div className="flex-1 h-px bg-border/70" />
    </div>
  );
}
