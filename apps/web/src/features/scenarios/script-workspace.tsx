'use client';
import { useSearchParams } from 'next/navigation';
import { ScenarioEditor } from './scenario-editor';
import { ScriptLibrary } from './script-library';
export function ScriptWorkspace({ editor = false }: { editor?: boolean }) {
  const query = useSearchParams();
  if (!editor && !query.get('id')) return <ScriptLibrary />;
  return <ScenarioEditor />;
}
