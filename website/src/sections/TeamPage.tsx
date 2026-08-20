/* ───── Types ───── */
interface TeamMember {
  name: string;
  role: string;
  description: string;
  color: string;
  isOpen?: boolean;
}

interface TeamSection {
  title: string;
  subtitle: string;
  accent: string;
  members: TeamMember[];
}

/* ───── Data ───── */
const sections: TeamSection[] = [
  {
    title: 'Команда мода',
    subtitle: 'Hypnosia Visuals Client',
    accent: '#80FF97',
    members: [
      { name: 'Nachosia', role: 'Coder', description: 'В помощниках Kimi GPT Gemini', color: '#80FF97' },
      { name: '—', role: 'Designer', description: 'Ищем дизайнера', color: '#6BB7FF', isOpen: true },
      { name: 'Nachosia', role: 'Sponsor', description: 'Проект кушает много денег', color: '#FFD700' },
      { name: '—', role: 'QA Tester', description: 'Ищем QA тестеров', color: '#FF8C42', isOpen: true },
    ],
  },
  {
    title: 'Команда сайта',
    subtitle: 'hypnosia-visuals.web',
    accent: '#6BB7FF',
    members: [
      { name: 'kimi', role: 'Coder', description: 'Фуллстак разработка', color: '#80FF97' },
      { name: 'kimi', role: 'Designer', description: 'UI/UX дизайн', color: '#6BB7FF' },
      { name: 'Nachosia', role: 'Idea', description: 'Просто Nachosia — идеи, концепция, контроль', color: '#FFD700' },
    ],
  },
];

/* ───── Component ───── */
export default function TeamPage() {
  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 800 }}>
        {/* Header */}
        <div className="text-center mb-14">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// TEAM</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(32px, 6vw, 48px)', letterSpacing: '-1px', color: '#E8E4E0' }}>
            Team
          </h1>
          <p className="font-body text-base mt-3 max-w-lg mx-auto" style={{ color: '#B8C4D0' }}>
            Люди за Hypnosia Visuals — от клиента до сайта.
          </p>
          <div className="mt-6 w-16 h-1 mx-auto rounded-full" style={{ background: 'rgba(128, 255, 151, 0.3)' }} />
        </div>

        {/* Sections */}
        {sections.map((sec) => (
          <div key={sec.title} className="mb-12">
            {/* Section header */}
            <div className="flex items-center gap-3 mb-5">
              <div className="w-2 h-2 rounded-full" style={{ background: sec.accent }} />
              <h2 className="font-mono text-xs tracking-[3px] uppercase" style={{ color: '#A0AEBF' }}>
                {sec.title}
              </h2>
              <span className="font-mono text-[10px]" style={{ color: '#7A8A9E' }}>
                {sec.subtitle}
              </span>
              <div className="flex-1 h-px" style={{ background: 'rgba(255,255,255,0.1)' }} />
            </div>

            {/* Members */}
            <div className="space-y-3">
              {sec.members.map((member, i) => (
                <div
                  key={`${member.role}-${i}`}
                  className="rounded-xl p-5"
                  style={{
                    background: member.isOpen ? '#1a2030' : '#222a3a',
                    border: member.isOpen
                      ? '1px dashed rgba(107, 183, 255, 0.3)'
                      : '1px solid rgba(255, 255, 255, 0.12)',
                  }}
                >
                  <div className="flex items-start gap-4">
                    {/* Avatar */}
                    <div
                      className="flex-shrink-0 flex items-center justify-center rounded-full font-bold text-sm"
                      style={{
                        width: 44,
                        height: 44,
                        background: member.isOpen ? 'transparent' : `${member.color}18`,
                        color: member.color,
                        border: member.isOpen ? `1px dashed ${member.color}50` : `1px solid ${member.color}40`,
                      }}
                    >
                      {member.isOpen ? '?' : member.name.charAt(0)}
                    </div>

                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-display font-bold text-base" style={{ color: member.isOpen ? '#9BA8B8' : '#F0ECE8' }}>
                          {member.name}
                        </span>
                        <span
                          className="font-mono text-[10px] tracking-[1px] uppercase px-2 py-0.5 rounded-full"
                          style={{ background: `${member.color}18`, color: member.color, border: `1px solid ${member.color}35` }}
                        >
                          {member.role}
                        </span>
                        {member.isOpen && (
                          <span
                            className="font-mono text-[9px] tracking-[1px] uppercase px-2 py-0.5 rounded-full"
                            style={{ background: 'rgba(255, 100, 100, 0.12)', color: '#ff6464', border: '1px solid rgba(255, 100, 100, 0.25)' }}
                          >
                            OPEN
                          </span>
                        )}
                      </div>
                      <p className="font-body text-sm mt-1" style={{ color: '#C5CDD8' }}>
                        {member.description}
                      </p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}

        {/* Footer */}
        <div className="text-center mt-14">
          <p className="font-body text-sm" style={{ color: '#A0AEBF' }}>
            Hypnosia Visuals — <span style={{ color: '#80FF97' }}>Nachosia</span> + AI + Discord.
          </p>
          <p className="font-mono text-[10px] mt-2" style={{ color: '#7A8A9E' }}>
            Want to join? DM Nachosia on Discord.
          </p>
        </div>
      </div>
    </div>
  );
}
