import type { ButtonHTMLAttributes, InputHTMLAttributes } from 'react';
import styles from './form-controls.module.css';

export function Button({ children, busy = false, disabled, type = 'button', className = '', variant = 'primary', ...props }:
  ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: 'primary' | 'secondary' | 'danger' }) {
  return (
    <button {...props} type={type} disabled={disabled || busy} aria-busy={busy} className={`${styles.button} ${variant === 'primary' ? '' : styles[variant]} ${className}`}>
      {busy && <span className={styles.spinner} aria-hidden="true" />}{children}
    </button>
  );
}

export function TextField({ id, label, error, hint, className = '', ...props }:
  InputHTMLAttributes<HTMLInputElement> & { id: string; label: string; error?: string; hint?: string }) {
  const description = [hint && `${id}-hint`, error && `${id}-error`, props['aria-describedby']].filter(Boolean).join(' ');
  return (
    <div className={styles.field}>
      <label htmlFor={id}>{label}</label>
      <input {...props} id={id} className={`${styles.input} ${className}`}
        aria-invalid={error ? true : undefined} aria-describedby={description || undefined} />
      {hint && <span className={styles.hint} id={`${id}-hint`}>{hint}</span>}
      {error && <span className={styles.error} id={`${id}-error`}>{error}</span>}
    </div>
  );
}

export function Notice({ title, children, tone = 'info' }: {
  title: string;
  children: React.ReactNode;
  tone?: 'info' | 'error';
}) {
  return (
    <div className={`${styles.notice} ${tone === 'error' ? styles.noticeError : ''}`} role={tone === 'error' ? 'alert' : 'status'}>
      <strong>{title}</strong><p>{children}</p>
    </div>
  );
}
