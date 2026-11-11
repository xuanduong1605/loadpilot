'use client';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { ScenarioEditor } from './scenario-editor';
import { ScriptLibrary } from './script-library';
import { PageHeading } from '../../components/console-ui';
export function ScriptWorkspace({ editor = false }: { editor?: boolean }) {
  const query = useSearchParams();
  if (!editor && !query.get('id')) return <ScriptLibrary />;
  return <><p><Link href="/scenarios/">Back to scripts</Link></p><PageHeading title="Script editor" /><ScenarioEditor /></>;
}
