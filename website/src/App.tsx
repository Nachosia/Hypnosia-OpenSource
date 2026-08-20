import { Routes, Route } from 'react-router'
import Navbar from './sections/Navbar'
import DynamicBackground from './sections/DynamicBackground'
import WelcomeModulesPage from './sections/WelcomeModulesPage'
import TopsPage from './sections/TopsPage'
import ProfilePage from './sections/ProfilePage'
import TeamPage from './sections/TeamPage'
import Login from './pages/Login'
import MinecraftLinkPage from './pages/MinecraftLink'
import ProfileSettingsPage from './pages/ProfileSettings'
import AdminPage from './pages/AdminPage'
import NotFound from './pages/NotFound'
import StorePage from './sections/StorePage'
import TransactionsPage from './pages/TransactionsPage'
import ComingSoonPage from './pages/ComingSoonPage'
import SupportPage from './sections/SupportPage'
import RoadmapPage from './sections/RoadmapPage'
import PrivacyPage from './pages/PrivacyPage'
import TermsPage from './pages/TermsPage'

function Footer() {
  return (
    <footer className="w-full py-8 mt-auto relative" style={{ zIndex: 1, borderTop: '1px solid rgba(255,255,255,0.08)', background: 'rgba(11,13,18,0.95)' }}>
      <div className="mx-auto px-6 lg:px-12" style={{ maxWidth: 1200 }}>
        <div className="flex flex-col md:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-6 flex-wrap justify-center">
            <a href="/#/" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Главная</a>
            <a href="/#/store" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Магазин</a>
            <a href="/#/support" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Поддержка</a>
            <a href="/#/tops" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Топы</a>
            <a href="/#/team" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Команда</a>
          </div>
          <div className="flex items-center gap-4">
            <a href="/#/privacy" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Политика конфиденциальности</a>
            <span style={{ color: 'rgba(255,255,255,0.15)' }}>|</span>
            <a href="/#/terms" className="font-mono text-[10px] uppercase tracking-[1px] transition-colors hover:text-[#6BB7FF]" style={{ color: '#7A8A9E' }}>Правила пользования</a>
          </div>
        </div>
        <p className="text-center font-mono text-[9px] mt-4" style={{ color: 'rgba(122,138,158,0.5)' }}>
          © {new Date().getFullYear()} Hypnosia Visuals. Все права защищены.
        </p>
      </div>
    </footer>
  );
}

export default function App() {
  return (
    <div className="min-h-screen text-vanta-text font-body overflow-x-hidden flex flex-col" style={{ background: '#0B0D12' }}>
      <DynamicBackground />
      <Navbar />
      <div className="flex-1">
        <Routes>
          <Route path="/" element={<WelcomeModulesPage />} />
          <Route path="/welcome" element={<WelcomeModulesPage />} />
          <Route path="/tops" element={<TopsPage />} />
          <Route path="/profile/:id" element={<ProfilePage />} />
          <Route path="/team" element={<TeamPage />} />
          <Route path="/store" element={<StorePage />} />
          <Route path="/link" element={<MinecraftLinkPage />} />
          <Route path="/profile/settings" element={<ProfileSettingsPage />} />
          <Route path="/transactions" element={<TransactionsPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/login" element={<Login />} />
          <Route path="/roadmap" element={<RoadmapPage />} />
          <Route path="/support" element={<SupportPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </div>
      <Footer />
    </div>
  );
}
