'use client';
import Link from 'next/link';
import { useState } from 'react';
import { Button } from '../../../../components/form-controls';
import styles from '../../../../components/feature-workspace.module.css';
import guide from '../../../../components/task-guide.module.css';
export default function Page() {
  const [prompt, setPrompt] = useState('');
  return <><p><Link href="/scenarios/">Back to scripts</Link></p><div className={styles.heading}><div><h1>Script requirements</h1><p>AI drafting is not available yet. You can still write and approve scripts manually.</p></div></div><section className={styles.lookup}><label className={guide.field}>Natural-language requirements<textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={6} maxLength={4000} placeholder="Describe endpoints, user behavior and checks…" /></label><p>This text is not submitted or saved automatically.</p><Button disabled>Draft with AI</Button></section></>;
}
