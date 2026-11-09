import { useState, useEffect, useCallback, useReducer } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Check, ChevronLeft } from 'lucide-react';
import { characterApi } from '../../api/characterApi';
import { apiErrorMessage } from '../../api/errors';
import Spinner from '../../components/ui/Spinner';
import {
  backgroundsQuery, classesQuery, racesQuery, subclassesQuery, useBackgrounds, useClasses, useRaces, useStartingEquipment,
} from '../../queries/reference';
import type { CharacterClassRef } from '../../types/reference';
import { initialWizardState, wizardReducer, type WizardDraft } from './wizard/wizardReducer';
import { buildCreateRequest, canAdvance, currentStepName, hasMeaningfulData, toDraft, wizardSteps } from './wizard/selectors';

import BasicInfoStep from './wizard/BasicInfoStep';
import RaceStep from './wizard/RaceStep';
import AbilityScoresStep from './wizard/AbilityScoresStep';
import ClassStep from './wizard/ClassStep';
import BackgroundStep from './wizard/BackgroundStep';
import EquipmentStep from './wizard/EquipmentStep';
import { EMPTY_KIT, type WizardKits } from './wizard/startingEquipment';
import SpellsStep from './wizard/SpellsStep';
import ReviewStep from './wizard/ReviewStep';

const DRAFT_KEY = 'characterWizardDraft';

function readDraft(): Partial<WizardDraft> | null {
  try {
    const saved = localStorage.getItem(DRAFT_KEY);
    return saved ? JSON.parse(saved) as Partial<WizardDraft> : null;
  } catch {
    // A corrupt draft is no draft
    return null;
  }
}

function hasSavedDraft(): boolean {
  const draft = readDraft();
  return !!(draft && (draft.name || draft.raceId || draft.classId));
}

/** A reference list for restoring a draft; one that can't load restores nothing from it. */
function orEmpty<T>(list: Promise<T[]>): Promise<T[]> {
  return list.catch(() => []);
}

/**
 * Creates a character in eight steps, with a draft kept in the browser. It makes most of the choices of the 1st level:
 * the first class's skills, its subclass where that comes at 1st level, as a cleric's does, a rogue's Expertise, and
 * the first class's cantrips and spells, and its starting equipment and the background's (PHB p.143). A 1st-level choice it doesn't ask for, such as a Fighter's Fighting Style,
 * every choice of a later level, of another class, and the feat a race or background gives are Pending Choices the
 * server adds, made on the sheet, where the player lands after creation (D159). The server works out hit points,
 * speed, slots, spell DCs and Hit Dice (D167).
 *
 * The choices are one WizardState (wizard/wizardReducer.ts), whose actions make their own resets; what follows
 * from them, the request included, is in wizard/selectors.ts.
 */
export default function CharacterCreateWizard() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [state, dispatch] = useReducer(wizardReducer, initialWizardState);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [showDraftBanner, setShowDraftBanner] = useState(hasSavedDraft);

  const racesList = useRaces();
  const classesList = useClasses();
  const backgroundsList = useBackgrounds();
  const races = racesList.data ?? [];
  const classes = classesList.data ?? [];
  const backgrounds = backgroundsList.data ?? [];

  // The chosen class's and background's starting equipment; one that can't load counts as none, so creation goes on
  const classKit = useStartingEquipment('classes', state.selectedClass?.id);
  const backgroundKit = useStartingEquipment('backgrounds', state.selectedBackground?.id);
  const kits: WizardKits = {
    cls: classKit.isError ? EMPTY_KIT : classKit.data,
    background: backgroundKit.isError ? EMPTY_KIT : backgroundKit.data,
  };

  const steps = wizardSteps(state);
  const stepName = currentStepName(state);
  // The reference list the step picks from, which says while it loads or why it failed rather than show an empty list
  const stepList = stepName === 'Race' ? { query: racesList, name: 'races' }
    : stepName === 'Class' ? { query: classesList, name: 'classes' }
    : stepName === 'Background' ? { query: backgroundsList, name: 'backgrounds' }
    : null;
  const meaningful = hasMeaningfulData(state);

  // ── Draft persistence ──

  const draftJson = meaningful ? JSON.stringify(toDraft(state)) : null;
  useEffect(() => {
    if (draftJson === null) return;
    try { localStorage.setItem(DRAFT_KEY, draftJson); } catch { /* the browser's storage is full; the draft is lost */ }
  }, [draftJson]);

  useEffect(() => {
    if (!meaningful) return;
    function onBeforeUnload(e: BeforeUnloadEvent) { e.preventDefault(); }
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [meaningful]);

  // ── Classes ──

  // The first class's entry, with its subclasses; a class whose subclasses can't load is offered none
  const loadPrimaryClass = useCallback((cls: CharacterClassRef) => {
    queryClient.fetchQuery(subclassesQuery(cls.id))
      .catch(() => [])
      .then(subclasses => dispatch({ type: 'primaryClassLoaded', cls, subclasses }));
  }, [queryClient]);

  const selectClass = useCallback((cls: CharacterClassRef | null) => {
    dispatch({ type: 'selectClass', cls });
    if (cls) loadPrimaryClass(cls);
  }, [loadPrimaryClass]);

  const addMulticlass = useCallback((cls: CharacterClassRef) => {
    queryClient.fetchQuery(subclassesQuery(cls.id))
      .catch(() => [])
      .then(subclasses => dispatch({ type: 'addMulticlass', cls, subclasses }));
  }, [queryClient]);

  // ── Drafts ──

  // Restores once the reference lists have loaded, so the draft's race, class and background are found
  async function restoreDraft() {
    setShowDraftBanner(false);
    const draft = readDraft();
    if (!draft) return;
    const [raceList, classList, backgroundList] = await Promise.all([
      orEmpty(queryClient.fetchQuery(racesQuery())),
      orEmpty(queryClient.fetchQuery(classesQuery())),
      orEmpty(queryClient.fetchQuery(backgroundsQuery())),
    ]);
    dispatch({ type: 'restoreDraft', draft, refs: { races: raceList, classes: classList, backgrounds: backgroundList } });
    const cls = classList.find(c => c.id === draft.classId);
    if (cls) loadPrimaryClass(cls);
  }

  function dismissDraft() {
    localStorage.removeItem(DRAFT_KEY);
    setShowDraftBanner(false);
  }

  // ── Character creation ──

  async function handleCreate() {
    const request = buildCreateRequest(state, kits);
    if (!request) return;
    setSubmitting(true); setError('');
    try {
      const res = await characterApi.create(request);
      localStorage.removeItem(DRAFT_KEY);
      // The sheet's banner lists the choices still to make, feats and later levels' among them (D159)
      navigate(`/player/characters/${res.data.id}`);
    } catch (err: unknown) {
      const data = (err as { response?: { data?: { error?: string; message?: string } } }).response?.data;
      setError(data?.error || data?.message || 'Failed to create character');
    } finally { setSubmitting(false); }
  }

  // ── Render ──

  const step = state.step;
  return (
    <div className="min-h-screen bg-page">
      <header className="sticky top-0 z-10 bg-page border-b border-rule px-6 py-3">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <button onClick={() => navigate('/player')} className="flex items-center gap-2 font-body text-[15px] font-semibold text-muted hover:text-ink transition-colors">
            <ChevronLeft className="w-4 h-4" /> Back
          </button>
          <h1 className="font-heading text-[17px] font-semibold tracking-[0.02em] text-ink">Create Character</h1>
          <div className="w-16" />
        </div>
      </header>

      {showDraftBanner && (
        <div className="max-w-3xl mx-auto px-6 pt-4">
          <div className="bg-cls-wizard-bg border border-abj-border px-4 py-3 flex items-center justify-between">
            <span className="font-body text-[15px] font-semibold text-cls-wizard">You have an unsaved character draft. Restore it?</span>
            <div className="flex gap-2">
              <button onClick={dismissDraft} className="px-3 py-1 font-body text-[14px] text-muted hover:text-ink transition-colors">Discard</button>
              <button onClick={restoreDraft} className="px-3 py-1 font-body text-[14px] bg-ink text-card hover:bg-muted transition-colors">Restore</button>
            </div>
          </div>
        </div>
      )}

      <div className="max-w-3xl mx-auto px-6 pt-6">
        <div className="flex items-center gap-1">
          {steps.map((label, i) => (
            <div key={label} className="flex-1 flex flex-col items-center">
              <div className={`w-7 h-7 flex items-center justify-center font-heading text-[13px] font-bold border-2 transition-colors ${
                i < step ? 'bg-buff border-buff text-card' : i === step ? 'bg-ink border-ink text-card' : 'bg-page border-rule text-faint'
              }`}>
                {i < step ? <Check className="w-3.5 h-3.5" /> : i + 1}
              </div>
              <span className={`font-heading text-[11px] font-semibold tracking-[0.04em] mt-1 hidden sm:block ${i === step ? 'text-ink' : 'text-faint'}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>

      <main className="max-w-3xl mx-auto px-6 py-8">
        {stepList?.query.isPending && (
          <div className="mb-4"><Spinner size="md" label={`Loading ${stepList.name}...`} /></div>
        )}
        {stepList?.query.isError && (
          <div role="alert" className="mb-4 flex flex-wrap items-center gap-3 bg-debuff-bg border border-debuff text-debuff px-4 py-3 font-body text-[15px] font-semibold">
            <span className="flex-1 min-w-0">The {stepList.name} could not be loaded. {apiErrorMessage(stepList.query.error)}</span>
            <button type="button" onClick={() => void stepList.query.refetch()} className="min-h-11 px-3 underline underline-offset-2 hover:no-underline">
              Try again
            </button>
          </div>
        )}
        {stepName === 'Basic Info' && <BasicInfoStep state={state} dispatch={dispatch} />}
        {stepName === 'Race' && <RaceStep races={races} state={state} dispatch={dispatch} />}
        {stepName === 'Ability Scores' && <AbilityScoresStep state={state} dispatch={dispatch} />}
        {stepName === 'Class' && (
          <ClassStep
            classes={classes} state={state} dispatch={dispatch}
            onSelectClass={selectClass} onAddMulticlass={addMulticlass}
          />
        )}
        {stepName === 'Background' && <BackgroundStep backgrounds={backgrounds} state={state} dispatch={dispatch} />}
        {stepName === 'Equipment' && (
          <EquipmentStep state={state} dispatch={dispatch} kits={kits} failed={classKit.isError || backgroundKit.isError} />
        )}
        {stepName === 'Spells' && <SpellsStep state={state} dispatch={dispatch} />}
        {stepName === 'Review' && <ReviewStep state={state} error={error} kits={kits} />}

        <div className="flex items-center justify-between mt-8 pt-6 border-t border-rule">
          <button onClick={() => step > 0 ? dispatch({ type: 'setStep', step: step - 1 }) : navigate('/player')} className="flex items-center gap-2 px-4 py-2 font-body text-[16px] font-semibold text-muted hover:text-ink transition-colors">
            <ArrowLeft className="w-4 h-4" /> {step > 0 ? 'Previous' : 'Cancel'}
          </button>
          {step < steps.length - 1 ? (
            <button onClick={() => dispatch({ type: 'setStep', step: step + 1 })} disabled={!canAdvance(state, kits)} className="flex items-center gap-2 px-5 py-2 bg-ink text-card font-body text-[16px] font-semibold border border-ink hover:bg-muted hover:border-muted disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
              Next <ArrowRight className="w-4 h-4" />
            </button>
          ) : (
            <button onClick={handleCreate} disabled={submitting} className="flex items-center gap-2 px-5 py-2 bg-buff text-card font-body text-[16px] font-semibold border border-buff hover:opacity-80 disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
              {submitting ? 'Creating...' : 'Create Character'} <Check className="w-4 h-4" />
            </button>
          )}
        </div>
      </main>

    </div>
  );
}
