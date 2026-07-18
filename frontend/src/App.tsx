import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import RoleSelectPage from './pages/RoleSelectPage';
import DmDashboard from './pages/dm/DmDashboard';
import CampaignManagePage from './pages/dm/CampaignManagePage';
import CampaignDetailPage from './pages/dm/CampaignDetailPage';
import PlayerDashboard from './pages/player/PlayerDashboard';
import CharacterSheetPage from './pages/player/CharacterSheetPage';
import BestiaryPage from './pages/dm/BestiaryPage';
import SpellsPage from './pages/dm/SpellsPage';
import ItemsPage from './pages/dm/ItemsPage';
import ConditionsPage from './pages/dm/ConditionsPage';
import PlaceholderPage from './components/common/PlaceholderPage';
import QuickReferencePage from './pages/QuickReferencePage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <Navigate to="/select-role" replace /> : <>{children}</>;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicRoute><LoginPage /></PublicRoute>} />
      <Route path="/register" element={<PublicRoute><RegisterPage /></PublicRoute>} />
      <Route path="/select-role" element={<ProtectedRoute><RoleSelectPage /></ProtectedRoute>} />

      <Route path="/dm" element={<ProtectedRoute><DmDashboard /></ProtectedRoute>} />
      <Route path="/dm/campaigns" element={<ProtectedRoute><CampaignManagePage /></ProtectedRoute>} />
      <Route path="/dm/campaigns/:campaignId" element={<ProtectedRoute><CampaignDetailPage /></ProtectedRoute>} />
      <Route path="/dm/bestiary" element={<ProtectedRoute><BestiaryPage /></ProtectedRoute>} />
      <Route path="/dm/spells" element={<ProtectedRoute><SpellsPage /></ProtectedRoute>} />
      <Route path="/dm/items" element={<ProtectedRoute><ItemsPage /></ProtectedRoute>} />
      <Route path="/dm/conditions" element={<ProtectedRoute><ConditionsPage /></ProtectedRoute>} />
      <Route path="/dm/encounters" element={<ProtectedRoute><PlaceholderPage title="Encounters" milestone={4} description="Build encounters, add monsters and players, then run live combat." /></ProtectedRoute>} />
      <Route path="/dm/quickref" element={<ProtectedRoute><QuickReferencePage /></ProtectedRoute>} />

      <Route path="/player" element={<ProtectedRoute><PlayerDashboard /></ProtectedRoute>} />
      <Route path="/player/quickref" element={<ProtectedRoute><QuickReferencePage /></ProtectedRoute>} />
      <Route path="/player/characters/:characterId" element={<ProtectedRoute><CharacterSheetPage /></ProtectedRoute>} />

      <Route path="/" element={<Navigate to="/login" replace />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
