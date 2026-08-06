import { Database, Fingerprint, Lock, MonitorSmartphone, Server } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

/**
 * Security, shown as a path rather than asserted as an adjective.
 *
 * Every label here was checked against the code before it was written, and the wording is
 * deliberately specific because vague security copy is worse than none -- it is the kind of claim
 * a reader can't verify and a regulator can:
 *
 *  - TLS in transit is true.
 *  - bcrypt at cost 12 is literal (SecurityConfig).
 *  - Per-request JWT verification and per-user scoping are how every protected route works.
 *  - Statement files are content-addressed and their digest is re-derived on read, so a swapped
 *    or corrupted object is detected rather than served (see ContentAddress / StatementStorage).
 *
 * What is NOT claimed, on purpose: "bank-level encryption", "end-to-end encryption", or
 * encryption at rest. The object store may well encrypt at rest as a platform default, but the
 * application does not implement it, and this page does not take credit for something it cannot
 * point at. If application-level at-rest encryption ever lands, add it here then.
 */
const CHAIN = [
  { icon: <MonitorSmartphone size={17} />, title: 'Your browser', body: 'Nothing leaves the device unencrypted.' },
  { icon: <Lock size={17} />, title: 'HTTPS', body: 'Every request in transit is TLS-encrypted.' },
  { icon: <Fingerprint size={17} />, title: 'Verified session', body: 'Each request re-checks your identity server-side.' },
  { icon: <Server size={17} />, title: 'Scoped to you', body: 'Queries are bound to your account, never global.' },
  { icon: <Database size={17} />, title: 'Integrity-checked files', body: 'Statements are fingerprinted and re-verified on read.' },
];

export function Security() {
  return (
    <Section id="security" tone="alt">
      <SectionHeading
        eyebrow="Security & privacy"
        title="You never hand us your bank login."
        blurb="Finora reads statements you upload. There is no standing connection to your bank, so there is nothing for anyone to misuse."
      />

      <div className="grid sm:grid-cols-2 lg:grid-cols-5 gap-3">
        {CHAIN.map((s, i) => (
          <Reveal key={s.title} delayMs={i * 80}>
            <div className="m-card m-card-hover p-5 h-full relative">
              {/* Connector, drawn only between cards on a row that actually has a next one. */}
              {i < CHAIN.length - 1 ? (
                <span
                  aria-hidden="true"
                  className="hidden lg:block absolute top-1/2 -right-3 w-3 h-[2px]"
                  style={{ background: '#CBD5E1' }}
                />
              ) : null}
              <span className="w-9 h-9 rounded-xl grid place-items-center mb-3" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {s.icon}
              </span>
              <p className="text-[13px] font-semibold mb-1" style={{ color: 'var(--m-ink)' }}>{s.title}</p>
              <p className="text-xs leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{s.body}</p>
            </div>
          </Reveal>
        ))}
      </div>

      <Reveal delayMs={220}>
        <p className="text-center text-sm mt-8" style={{ color: 'var(--m-ink-3)' }}>
          Passwords are hashed with bcrypt and never stored in readable form — not even we can see them.
        </p>
      </Reveal>

      {/* The section ends on ownership rather than on cryptography. Everything above is a
          mechanism; this is what the mechanisms are FOR, and it is the sentence worth remembering
          once the details have faded. Deliberately a claim about intent, not a technical boast --
          the technical claims are all above it and all verifiable. */}
      <Reveal delayMs={300}>
        <p
          className="max-w-2xl mx-auto text-center mt-12 text-xl sm:text-2xl leading-snug"
          style={{ fontFamily: "'Manrope', Inter, sans-serif", fontWeight: 700, letterSpacing: '-.02em', color: 'var(--m-ink)' }}
        >
          Your financial data belongs to you.
          <br />
          <span style={{ color: 'var(--m-brand)' }}>
            Finora exists to help you understand it — not to profit from it.
          </span>
        </p>
      </Reveal>
    </Section>
  );
}
