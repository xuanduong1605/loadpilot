'use client';

import { useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { Button, Notice, TextField } from '../../components/form-controls';
import { Icon } from '../../components/icon';
import styles from './login.module.css';

type Errors = { username?: string; password?: string };

export function LoginPreview() {
  const [ready, setReady] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [checked, setChecked] = useState(false);

  useEffect(() => setReady(true), []);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const username = form.elements.namedItem('login-username') as HTMLInputElement;
    const password = form.elements.namedItem('login-password') as HTMLInputElement;
    const next: Errors = {};
    if (!username.value.trim()) next.username = 'Enter a username to preview the form.';
    if (!password.value) next.password = 'Enter a sample password to preview the form.';
    setErrors(next);
    setChecked(!next.username && !next.password);
    if (next.username) username.focus();
    else if (next.password) password.focus();
  }

  function clearFeedback(field: keyof Errors) {
    setErrors((previous) => ({ ...previous, [field]: undefined }));
    setChecked(false);
  }

  return (
    <>
      <div className={styles.formHeading}>
        <span className={styles.eyebrow}>YOUR PERFORMANCE LAB</span>
        <h1>Welcome back.</h1>
        <p>A clearer view of performance starts here.</p>
      </div>
      <div className={styles.previewNote} id="login-preview-note"><Icon name="lock" width={17} height={17} />
        <p><strong>Sign-in preview</strong>Use sample values. Nothing is sent or saved, and this form will not sign you in.</p>
      </div>
      {/* Inputs deliberately have no name: even without JS, sample credentials cannot become a form payload. */}
      <form className={styles.form} onSubmit={submit} noValidate autoComplete="off" aria-describedby="login-preview-note">
        {(errors.username || errors.password) && <Notice title="Check the highlighted fields" tone="error">Enter both fields to continue the preview.</Notice>}
        <TextField id="login-username" label="Username" placeholder="e.g. demo.operator" required
          autoCapitalize="none" spellCheck={false} autoComplete="off" error={errors.username}
          onChange={() => clearFeedback('username')} />
        <div className={styles.passwordField}>
          <TextField id="login-password" label="Password" placeholder="Use a sample password"
            type={showPassword ? 'text' : 'password'} autoComplete="off" required
            error={errors.password} onChange={() => clearFeedback('password')} />
          <button className={styles.passwordToggle} type="button" aria-label={showPassword ? 'Hide password' : 'Show password'}
            aria-pressed={showPassword} aria-controls="login-password" onClick={() => setShowPassword(!showPassword)}>
            <Icon name="eye" width={18} height={18} />
          </button>
        </div>
        <Button type="submit" disabled={!ready} className={styles.submit}>Preview sign in <Icon name="arrow" width={17} height={17} /></Button>
        {checked && <Notice title="Form preview complete">Both fields are filled in. Authentication is not connected yet; no session has been created.</Notice>}
        <noscript><p>Enable JavaScript to try this form preview. No sign-in request can be sent from this page.</p></noscript>
      </form>
      <p className={styles.accessHelp}>Need access? Ask the person managing your workspace.</p>
      <div className={styles.backLink}><Link href="/">Explore the workspace preview <Icon name="arrow" width={15} height={15} /></Link></div>
    </>
  );
}
