'use client';
import { useState } from 'react';
import { PageHeading } from '../../../../components/console-ui';
import { Button } from '../../../../components/form-controls';
import styles from '../../../../components/feature-workspace.module.css';
import guide from '../../../../components/task-guide.module.css';
export default function Page() {
  const [prompt, setPrompt] = useState('');
  return <><PageHeading title="Script requirements" back={{ href: '/scenarios/', label: 'Back to scripts' }} /><section className={styles.lookup}><label className={guide.field}>Natural-language requirements<textarea value={prompt} onChange={event => setPrompt(event.target.value)} rows={6} maxLength={4000} placeholder="Describe endpoints, user behavior and checks…" /></label><p>AI drafting is unavailable. This text is not saved or submitted.</p><Button disabled>Draft with AI</Button></section></>;
}
