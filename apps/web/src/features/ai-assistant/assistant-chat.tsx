'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../../components/icon';
import { api, ApiError, mutate } from '../../lib/api';
import { AssistantContext } from './assistant-context';
import { answerHref, tasks, uuid, type Conversation, type Message, type Sent, type Task, type Turn } from './assistant-data';
import styles from './assistant.module.css';

type Pending = { id: string; message: Message };
const MAX_TURNS = 50;
const MAX_LENGTH = 2000;

export function AssistantChat({ id, initial, task: initialTask, geminiEnabled, focusOnOpen, onSaved }: {
  id: string | null; initial?: Conversation; task: Task; geminiEnabled?: boolean;
  focusOnOpen: boolean; onSaved: (value: Conversation, first: boolean) => void;
}) {
  const [conversation, setConversation] = useState(initial);
  const [loading, setLoading] = useState(Boolean(id && !initial));
  const [loadError, setLoadError] = useState('');
  const [reload, setReload] = useState(0);
  const [text, setText] = useState('');
  const [task, setTask] = useState(initial?.turns.at(-1)?.task ?? initialTask);
  const [recordId, setRecordId] = useState(initial?.turns.at(-1)?.recordId ?? null);
  const [pending, setPending] = useState<Pending>();
  const [sendError, setSendError] = useState<{ message: string; conflict: boolean }>();
  const sending = useRef(false);
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  const seededPrompt = useRef<string | undefined>(undefined);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const chat = useRef<HTMLElement>(null);
  const log = useRef<HTMLDivElement>(null);
  const scrollOnSend = useRef(false);

  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  useEffect(() => { if (initial) textarea.current?.focus({ preventScroll: true }); }, [initial]);
  useEffect(() => { if (focusOnOpen && !loading) chat.current?.focus({ preventScroll: true }); }, [focusOnOpen, loading]);

  useEffect(() => {
    if (!id || (initial && reload === 0)) return;
    if (!uuid.test(id)) {
      setLoadError('That conversation link is invalid.');
      setLoading(false);
      return;
    }
    let current = true;
    const controller = new AbortController();
    setLoading(true);
    setLoadError('');
    void api<Conversation>(`/api/assistant/conversations/${id}`, { signal: controller.signal })
      .then(value => {
        if (!current) return;
        scrollOnSend.current = true;
        setConversation(value);
        if (!reload) {
          setTask(value.turns.at(-1)?.task ?? initialTask);
          setRecordId(value.turns.at(-1)?.recordId ?? null);
        }
      })
      .catch(reason => { if (current) setLoadError(reason.message); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; controller.abort(); };
  }, [id, initial, initialTask, reload]);

  const turns = conversation?.turns ?? [];
  const full = turns.length >= MAX_TURNS;
  const welcome = !loading && !loadError && turns.length === 0 && !busy;
  const blocked = loading || Boolean(loadError) || full;
  useEffect(() => {
    if (scrollOnSend.current && log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [busy, turns.length]);

  function pick(next: Task, seed = false) {
    if (next !== task) {
      setTask(next);
      setRecordId(null);
    }
    if ((seed && !text) || (seededPrompt.current && text === seededPrompt.current)) {
      seededPrompt.current = tasks[next].prompt;
      setText(seededPrompt.current);
    }
    if (seed) textarea.current?.focus();
  }

  async function send() {
    if (sending.current || blocked || sendError?.conflict || !text.trim()) return;
    const messageText = text.trim();
    const reused = pending?.message.text === messageText && pending.message.task === task && pending.message.recordId === recordId;
    const request = reused ? pending : { id: id ?? pending?.id ?? crypto.randomUUID(),
      message: { requestId: crypto.randomUUID(), expectedTurns: turns.length, text: messageText, task, recordId } };
    sending.current = true;
    scrollOnSend.current = true;
    setPending(request);
    setBusy(true);
    setSendError(undefined);
    try {
      const saved = await mutate<Sent>(`/api/assistant/conversations/${request.id}/messages`, 'POST', request.message);
      const next = { summary: saved.summary, turns: [...turns, saved.turn] };
      if (!active.current) { onSaved(next, false); return; }
      setConversation(next);
      setText('');
      seededPrompt.current = undefined;
      setPending(undefined);
      onSaved(next, id === null);
      if (id && saved.summary.turnCount !== next.turns.length) setReload(value => value + 1);
    } catch (reason) {
      if (active.current) setSendError({ message: reason instanceof Error ? reason.message : 'Could not send the message.',
        conflict: Boolean(id && reason instanceof ApiError && reason.status === 409) });
    } finally {
      sending.current = false;
      if (active.current) setBusy(false);
    }
  }

  function reloadChat() {
    setSendError(undefined);
    setPending(undefined);
    setReload(value => value + 1);
  }

  return <section ref={chat} tabIndex={-1} className={styles.chat} data-welcome={welcome} aria-label="Assistant chat">
    {loading || loadError ? <div className={styles.chatState}>
      {loading ? <p role="status">Loading conversation…</p> : <>
        <h1>Cannot open conversation</h1><p role="alert" className={styles.error}>{loadError}</p>
        {id && uuid.test(id) && <button type="button" onClick={reloadChat}>Retry conversation</button>}
        <Link href="/assistant/">Start a new chat</Link>
      </>}
    </div> : <>
      {welcome ? <div className={styles.welcome}>
        <h1>Ask Loadpilot</h1>
        <p>Get help with k6 scripts, experiment setup, and test results.</p>
      </div> : <div ref={log} className={styles.scroll}>
        <div className={styles.transcript}>
          <h1>{conversation?.summary.title ?? 'New chat'}</h1>
          <div className={styles.turns} role="log" aria-label="Conversation messages">
            {turns.map(turn => <Exchange key={turn.requestId} turn={turn} />)}
            {busy && pending && <><Question text={pending.message.text} /><p className={styles.waiting} role="status">Preparing your reply…</p></>}
          </div>
        </div>
      </div>}
      <div className={styles.composerArea}>
        {full ? <p role="status">This conversation has reached 50 turns. Start a new chat to continue.</p> :
          <form onSubmit={event => { event.preventDefault(); void send(); }} aria-label="Write a message">
            {sendError && <div className={styles.sendError}>
              <p className={styles.error} role="alert">{sendError.message}</p>
              {sendError.conflict && <button type="button" onClick={reloadChat}>Reload chat</button>}
            </div>}
            <div className={styles.composer}>
              <div className={styles.controls}>
                <label><span>Task</span><select value={task} disabled={busy} onChange={event => pick(event.target.value as Task)}>
                  {(Object.keys(tasks) as Task[]).map(value => <option key={value} value={value}>{tasks[value].title}</option>)}
                </select></label>
                <AssistantContext task={task} value={recordId} onChange={setRecordId} disabled={busy} />
              </div>
              <label className={styles.screenReader} htmlFor="assistant-message">Message</label>
              <textarea id="assistant-message" ref={textarea} value={text} onChange={event => { seededPrompt.current = undefined; setText(event.target.value); }} maxLength={MAX_LENGTH}
                rows={3} readOnly={busy} placeholder="Write a message…" aria-describedby="assistant-message-hint"
                onKeyDown={event => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault(); void send();
                  }
                }} />
              <div className={styles.composerFooter}>
                <span aria-label={`${text.length} of ${MAX_LENGTH} characters`}>{text.length}/{MAX_LENGTH}</span>
                <button type="submit" className={styles.send} aria-label="Send message" disabled={busy || !text.trim() || sendError?.conflict}>
                  <Icon name="send" width={20} height={20} />
                </button>
              </div>
            </div>
            <p id="assistant-message-hint" className={styles.hint}>
              {geminiEnabled === false
                ? 'Chats are saved to your account. Keep credentials out of messages.'
                : 'Chats are saved. When Loadpilot asks Gemini, Google receives your message and recent AI exchanges. Keep sensitive data out of chat.'}
            </p>
          </form>}
      </div>
      {welcome && <div className={styles.starters} role="group" aria-label="Suggested tasks">
        {(Object.keys(tasks) as Task[]).map(value => <button type="button" key={value} onClick={() => pick(value, true)}>
          <Icon name={tasks[value].icon} width={18} height={18} /><span>{tasks[value].title}</span><Icon name="arrow" width={16} height={16} />
        </button>)}
      </div>}
    </>}
  </section>;
}

function Question({ text }: { text: string }) {
  return <div className={styles.question}><span className={styles.screenReader}>You</span><p>{text}</p></div>;
}

function Exchange({ turn }: { turn: Turn }) {
  return <div className={styles.exchange}>
    <Question text={turn.text} />
    <article className={styles.answer} aria-label="Assistant reply">
      <span className={styles.answerTitle}><Icon name="message" width={18} height={18} />Loadpilot</span>
      <p className={styles.copy}>{turn.reply.text}</p>
      {turn.reply.suggestion && <div className={styles.suggestion}>
        <h2>AI suggestion</h2><p className={styles.copy}>{turn.reply.suggestion}</p>
      </div>}
      {turn.reply.providerUnavailable && <p className={styles.waiting}>AI suggestions are temporarily unavailable. You can continue with the guidance above.</p>}
      {turn.reply.links.length > 0 && <ul className={styles.links} aria-label="Related pages">
        {turn.reply.links.map(link => {
          const href = answerHref(link);
          return href && <li key={href}><Link href={href}>{link.label}<Icon name="arrow" width={15} height={15} /></Link></li>;
        })}
      </ul>}
    </article>
  </div>;
}
