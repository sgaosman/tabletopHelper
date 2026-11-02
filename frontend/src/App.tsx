import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import RoleSelectPage from './pages/RoleSelectPage';

// Every other page downloads the first time it opens (D171). A download that fails, such as a
// page an update has replaced, throws to the ErrorBoundary in main.tsx, which offers a reload.
const DmDashboard = lazy(() => import('./pages/dm/DmDashboard'));
const CampaignManagePage = lazy(() => import('./pages/dm/CampaignManagePage'));
const CampaignDetailPage = lazy(() => import('./pages/dm/CampaignDetailPage'));
const PlayerDashboard = lazy(() => import('./pages/player/PlayerDashboard'));
const CharacterSheetPage = lazy(() => import('./pages/player/CharacterSheetPage'));
const BestiaryPage = lazy(() => import('./pages/dm/BestiaryPage'));
const SpellsPage = lazy(() => import('./pages/dm/SpellsPage'));
const ItemsPage = lazy(() => import('./pages/dm/ItemsPage'));
const ConditionsPage = lazy(() => import('./pages/dm/ConditionsPage'));
const EncounterBuilderPage = lazy(() => import('./pages/dm/EncounterBuilderPage'));
const EncounterSessionPage = lazy(() => import('./pages/dm/EncounterSessionPage'));
const JoinEncounterPage = lazy(() => import('./pages/player/JoinEncounterPage'));
const PlayerEncounterSessionPage = lazy(() => import('./pages/player/EncounterSessionPage'));
const CharacterCreateWizard = lazy(() => import('./pages/player/CharacterCreateWizard'));
const QuickReferencePage = lazy(() => import('./pages/QuickReferencePage'));

// Shown while the app asks the server whether the browser still has a session (D156), and
// while a page's code downloads
function PageLoading() {
  return (
    <div className="min-h-screen bg-page flex items-center justify-center">
      <p role="status" className="font-body text-[16px] text-muted">Loading...</p>
    </div>
  );
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') return <PageLoading />;
  return status === 'authenticated' ? <>{children}</> : <Navigate to="/login" replace />;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === 'loading') return <PageLoading />;
  return status === 'authenticated' ? <Navigate to="/select-role" replace /> : <>{children}</>;
}

export default function App() {
  return (
    <Suspense fallback={<PageLoading />}>
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
        <Route path="/dm/encounters" element={<ProtectedRoute><EncounterBuilderPage /></ProtectedRoute>} />
        <Route path="/dm/encounter/:encounterId/session" element={<ProtectedRoute><EncounterSessionPage /></ProtectedRoute>} />
        <Route path="/dm/quickref" element={<ProtectedRoute><QuickReferencePage /></ProtectedRoute>} />

        <Route path="/player" element={<ProtectedRoute><PlayerDashboard /></ProtectedRoute>} />
        <Route path="/player/quickref" element={<ProtectedRoute><QuickReferencePage /></ProtectedRoute>} />
        {/* The reference pages are shared with the DM side */}
        <Route path="/player/spells" element={<ProtectedRoute><SpellsPage /></ProtectedRoute>} />
        <Route path="/player/items" element={<ProtectedRoute><ItemsPage /></ProtectedRoute>} />
        <Route path="/player/conditions" element={<ProtectedRoute><ConditionsPage /></ProtectedRoute>} />
        <Route path="/player/characters/new" element={<ProtectedRoute><CharacterCreateWizard /></ProtectedRoute>} />
        <Route path="/player/characters/:characterId" element={<ProtectedRoute><CharacterSheetPage /></ProtectedRoute>} />
        <Route path="/player/encounter/join" element={<ProtectedRoute><JoinEncounterPage /></ProtectedRoute>} />
        <Route path="/player/encounter/:encounterId/session" element={<ProtectedRoute><PlayerEncounterSessionPage /></ProtectedRoute>} />

        <Route path="/" element={<Navigate to="/login" replace />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </Suspense>
  );
}
