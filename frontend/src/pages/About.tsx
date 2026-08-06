import { Brain, ShieldCheck, Target, TrendingUp } from 'lucide-react';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

export default function About() {
  return (
    <PublicLayout
      title="About Finora"
      subtitle="A financial operating system built for people who are tired of spreadsheets."
    >
      <PublicSection title="What Finora Is">
        <p>
          Finora is a personal finance platform that brings your accounts, transactions, budgets, goals, and
          investments into one place. Import a bank or credit card statement and Finora detects the account,
          categorizes every transaction, and updates your dashboard instantly — no manual data entry required.
        </p>
      </PublicSection>

      <PublicSection title="Our Mission">
        <p>
          To make understanding your own money as easy as looking at a single screen — replacing scattered
          spreadsheets, bank apps, and mental math with one clear, trustworthy view of your financial life.
        </p>
      </PublicSection>

      <PublicSection title="Why Finora Exists">
        <p>
          Most people's financial picture is spread across multiple bank apps, credit card statements, and a
          spreadsheet nobody updates consistently. Categorizing spend by hand is tedious, and by the time
          someone gets around to reviewing it, the insight has already lost its usefulness. Finora exists to
          close that gap — turning a raw statement into an organized, understandable picture in seconds.
        </p>
      </PublicSection>

      <PublicSection title="Problems We Solve">
        <ul className="list-disc list-inside space-y-1.5 ml-1">
          <li>Manually re-typing transactions from a bank statement into a spreadsheet</li>
          <li>Losing track of which categories are over budget until the month is already over</li>
          <li>Having no single view across savings accounts, credit cards, and investments</li>
          <li>Automatic categorization tools that get something wrong once and never learn from the correction</li>
        </ul>
      </PublicSection>

      <div className="grid sm:grid-cols-2 gap-6 mb-10">
        {[
          { icon: <Target size={18} />, title: 'Product Vision', body: 'A single financial command center — not another expense tracker, but a platform that grows into budgeting, goals, investments, and AI-driven insight together.' },
          { icon: <ShieldCheck size={18} />, title: 'Security-First Philosophy', body: 'Hashed passwords, rotating refresh tokens, and server-verified sessions on every request. Security decisions are made before feature decisions, not after.' },
          { icon: <Brain size={18} />, title: 'AI-Powered Finance', body: 'Categorization is always a suggestion, never a silent final decision — and every correction you make teaches the system for next time.' },
          { icon: <TrendingUp size={18} />, title: 'Future Roadmap', body: 'Data export, richer investment tracking, and deeper AI-driven recommendations are all active areas of development.' },
        ].map((item) => (
          <div key={item.title} className="bg-[#12142a] border border-white/10 rounded-xl p-5">
            <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center mb-3">{item.icon}</div>
            <h3 className="font-semibold text-white text-sm mb-1.5">{item.title}</h3>
            <p className="text-xs text-gray-400 leading-relaxed">{item.body}</p>
          </div>
        ))}
      </div>
    </PublicLayout>
  );
}
