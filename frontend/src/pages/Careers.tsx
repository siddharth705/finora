import { Heart, Compass, Rocket, Mail } from 'lucide-react';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

export default function Careers() {
  return (
    <PublicLayout
      title="Careers at Finora"
      subtitle="We're building a financial operating system people trust with their money — that starts with the team building it."
    >
      <div className="grid sm:grid-cols-3 gap-6 mb-10">
        {[
          { icon: <Heart size={18} />, title: 'Why Join Finora', body: 'Work on a product where security, correctness, and user trust matter as much as speed of shipping.' },
          { icon: <Compass size={18} />, title: 'Company Culture', body: 'Small team, high ownership, and a bias toward getting the details right — especially anywhere real money is involved.' },
          { icon: <Rocket size={18} />, title: 'Mission', body: 'Make understanding your own finances effortless, for everyone, not just people comfortable with spreadsheets.' },
        ].map((item) => (
          <div key={item.title} className="bg-[#12142a] border border-white/10 rounded-xl p-5">
            <div className="w-9 h-9 rounded-lg bg-indigo-500/10 text-indigo-300 flex items-center justify-center mb-3">{item.icon}</div>
            <h3 className="font-semibold text-white text-sm mb-1.5">{item.title}</h3>
            <p className="text-xs text-gray-400 leading-relaxed">{item.body}</p>
          </div>
        ))}
      </div>

      <PublicSection title="Future Opportunities">
        <p>
          Finora is early — the team is small today, but we expect to grow across engineering, design, and
          product as the platform matures. If that timeline interests you, we'd rather hear from you early than
          not at all.
        </p>
      </PublicSection>

      <PublicSection title="Hiring Philosophy">
        <p>
          We look for people who care about getting financial software right — clear communication, good
          judgment under ambiguity, and a genuine interest in the problem, over any specific list of
          technologies. We'd rather hire slowly and well than quickly.
        </p>
      </PublicSection>

      <PublicSection title="Current Openings">
        <div className="bg-[#12142a] border border-white/10 rounded-xl p-6 text-center">
          <p className="text-sm text-gray-300 mb-1">There are no open roles listed right now.</p>
          <p className="text-xs text-gray-500">
            We'd rather say that plainly than list a placeholder job that isn't real. Check back, or reach out
            below if you'd like to be considered when a role opens up.
          </p>
        </div>
      </PublicSection>

      <PublicSection title="Contact Information">
        <p className="flex items-center gap-2">
          <Mail size={15} className="text-primary" />
          <a href="mailto:careers@finora.app" className="text-primary hover:underline">careers@finora.app</a>
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
