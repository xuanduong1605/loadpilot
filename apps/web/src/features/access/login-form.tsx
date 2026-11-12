'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, accessPost, returnPath } from '../../lib/api';
import { Button, Notice, TextField } from '../../components/form-controls';
import { Icon } from '../../components/icon';
import styles from './login.module.css';

type Errors = { username?: string; password?: string };

export function LoginForm() {
  const [ready, setReady] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [pending, setPending] = useState(false);
  const [feedback, setFeedback] = useState<{ message: string; invalidCredentials: boolean }>();

  useEffect(() => {
    setReady(true);
    void api('/api/access/session').then(() => {
      window.location.replace(returnPath(new URLSearchParams(window.location.search).get('next')));
    }).catch(() => {});
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const form = event.currentTarget;
    const username = form.elements.namedItem('login-username') as HTMLInputElement;
    const password = form.elements.namedItem('login-password') as HTMLInputElement;
    const next: Errors = {};
    if (!username.value.trim()) next.username = 'Enter your username.';
    if (!password.value) next.password = 'Enter your password.';
    setErrors(next);
    setFeedback(undefined);
    if (next.username) username.focus();
    else if (next.password) password.focus();
    else {
      setPending(true);
      try {
        await accessPost('login', new URLSearchParams({ username: username.value.trim(), password: password.value }));
        password.value = '';
        window.location.replace(returnPath(new URLSearchParams(window.location.search).get('next')));
      } catch (reason) {
        password.value = '';
        setFeedback({
          message: reason instanceof ApiError && reason.code === 'CSRF_INVALID'
            ? 'Your sign-in form expired. Try signing in again.'
            : reason instanceof Error ? reason.message : 'Sign in failed. Try again.',
          invalidCredentials: reason instanceof ApiError && reason.code === 'INVALID_CREDENTIALS',
        });
        // Wait until the pending render has re-enabled the password field.
        requestAnimationFrame(() => password.focus());
      } finally { setPending(false); }
    }
  }

  function clearFeedback(field: keyof Errors) {
    setErrors((previous) => ({ ...previous, [field]: undefined }));
    setFeedback(undefined);
  }

  return (
    <>
      <div className={styles.formHeading}>
        <h1>Sign in</h1>
        <p id="login-note">Use your operator account.</p>
      </div>
      {/* Unnamed inputs prevent accidental native GET submission when JavaScript is unavailable. */}
      <form className={styles.form} onSubmit={submit} noValidate aria-describedby="login-note" aria-busy={pending}>
        {(errors.username || errors.password) && <Notice title="Check the highlighted fields" tone="error">Enter both fields to sign in.</Notice>}
        {feedback && <Notice id="login-error" title="Unable to sign in" tone="error">{feedback.message}</Notice>}
        <TextField id="login-username" label="Username" required maxLength={128} disabled={pending}
          autoCapitalize="none" spellCheck={false} autoComplete="username" error={errors.username}
          aria-invalid={feedback?.invalidCredentials || undefined} aria-describedby={feedback?.invalidCredentials ? 'login-error' : undefined}
          onChange={() => clearFeedback('username')} />
        <div className={styles.passwordField}>
          <TextField id="login-password" label="Password" disabled={pending}
            type={showPassword ? 'text' : 'password'} autoComplete="current-password" required
            aria-invalid={feedback?.invalidCredentials || undefined} aria-describedby={feedback?.invalidCredentials ? 'login-error' : undefined}
            error={errors.password} onChange={() => clearFeedback('password')} />
          <button className={styles.passwordToggle} type="button" aria-label={showPassword ? 'Hide password' : 'Show password'}
            aria-pressed={showPassword} aria-controls="login-password" onClick={() => setShowPassword(!showPassword)}>
            <Icon name="eye" width={18} height={18} />
          </button>
        </div>
        <Button type="submit" disabled={!ready || pending} className={styles.submit}>{pending ? 'Signing in…' : 'Sign in'}</Button>
        <noscript><p>Enable JavaScript to sign in.</p></noscript>
      </form>
      <p className={styles.accessHelp}>Need access? Ask the person managing your workspace.</p>
    </>
  );
}
