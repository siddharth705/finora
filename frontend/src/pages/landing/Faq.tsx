import { useState } from 'react';
import { Minus, Plus } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';

/**
 * Every answer here is checkable against the product as it stands today.
 *
 * Two are deliberately worded as limitations rather than features, because they are: self-service
 * account deletion does not exist (there is no DELETE /users/me -- only an administrator can
 * remove an account), and a full raw-transaction export is not built. Saying so costs a little
 * polish and buys the only thing that matters on a page whose whole argument is trust.
 */
const ITEMS: [string, string][] = [
  [
    'Is my financial data secure?',
    'Passwords are hashed with bcrypt and never stored in readable form, sessions use short-lived access tokens with rotating refresh tokens, and every request to a protected endpoint is verified server-side. Traffic is encrypted in transit over HTTPS, and uploaded statements are fingerprinted so a corrupted or swapped file is detected rather than served. Your data is never sold or shared.',
  ],
  [
    'Does Finora connect to my bank account?',
    'No, and that is deliberate. Finora never asks for your net-banking credentials and holds no connection to your bank — it reads only the statements you upload yourself. There is no standing access for anyone to misuse.',
  ],
  [
    'Can I import several bank accounts?',
    'Yes. Savings accounts, credit cards, wallets and deposits, as many as you have. Each statement is matched to the right account automatically, and a single statement covering several accounts is split into them rather than flattened into one.',
  ],
  [
    'Can I upload password-protected PDFs?',
    'Yes. Most Indian banks e-mail statements locked with a password — enter it during upload and Finora opens the file to read it. The password travels in the request body, never in a URL, and is not stored afterwards, so a later re-import will ask again.',
  ],
  [
    'How does categorization get better?',
    'Correct a transaction once and Finora remembers that merchant, applying your preference on future imports. It records how confident each suggestion was and which signals matched, and anything below your confidence threshold waits for you rather than being filed quietly. It is always a suggestion, never a decision you cannot see or change.',
  ],
  [
    'Can I export or delete my data?',
    'Partly, and it is worth being precise. You can download any statement you uploaded and export a month\'s category breakdown as CSV. A full export of your raw transaction list, and self-service account deletion, are genuinely not built yet — deletion currently goes through support. These are missing features, not a lock-in strategy.',
  ],
];

export function Faq() {
  const [open, setOpen] = useState<number | null>(0);

  return (
    <Section id="faq" tone="alt">
      <SectionHeading eyebrow="Questions" title="Anything else?" />
      <div className="max-w-3xl mx-auto">
        {ITEMS.map(([q, a], i) => {
          const isOpen = open === i;
          return (
            <Reveal key={q} delayMs={i * 40}>
              <div className="border-b" style={{ borderColor: 'var(--m-line)' }}>
                <button
                  type="button"
                  onClick={() => setOpen(isOpen ? null : i)}
                  aria-expanded={isOpen}
                  className="w-full flex items-center justify-between gap-4 py-5 text-left"
                >
                  <span className="text-[15px] font-semibold" style={{ color: 'var(--m-ink)' }}>{q}</span>
                  <span className="shrink-0" style={{ color: 'var(--m-ink-3)' }}>
                    {isOpen ? <Minus size={17} /> : <Plus size={17} />}
                  </span>
                </button>
                {isOpen ? (
                  <p className="pb-5 text-[15px] leading-relaxed max-w-2xl" style={{ color: 'var(--m-ink-2)' }}>{a}</p>
                ) : null}
              </div>
            </Reveal>
          );
        })}
      </div>
    </Section>
  );
}
