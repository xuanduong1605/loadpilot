'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../../components/icon';
import { BrowserTransportNotice } from '../../components/browser-transport-notice';
import { api, ApiError, mutate } from '../../lib/api';
import { requestId } from '../../lib/request-id';
import { AssistantRunReport } from './assistant-run-report';
import { answerHref, providerIssueMessage, starterPrompts, uuid, type Conversation, type Message, type Sent, type Turn } from './assistant-data';
import styles from './assistant.module.css';

type Pending = { id: string; message: Message };
const MAX_TURNS = 50;
const MAX_LENGTH = 2000;

export function AssistantChat({ id, initial, prompt, focusOnOpen, onSaved }: {
  id: string | null; initial?: Conversation; prompt: string;
  focusOnOpen: boolean; onSaved: (value: Conversation, first: boolean) => void;
}) {
  const [conversation, setConversation] = useState(initial);
  const [loading, setLoading] = useState(Boolean(id && !initial));
  const [loadError, setLoadError] = useState('');
  const [reload, setReload] = useState(0);
  const [text, setText] = useState(prompt);
  const [reportSelection, setReportSelection] = useState<string | null>();
  const [pending, setPending] = useState<Pending>();
  const [sendError, setSendError] = useState<{ message: string; conflict: boolean }>();
  const sending = useRef(false);
  const [busy, setBusy] = useState(false);
  const active = useRef(true);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const chat = useRef<HTMLElement>(null);
  const log = useRef<HTMLDivElement>(null);
  const exchanges = useRef<HTMLDivElement>(null);
  const scrollToLatest = useRef(false);

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
        scrollToLatest.current = true;
        setConversation(value);
      })
      .catch(reason => { if (current) setLoadError(reason.message); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; controller.abort(); };
  }, [id, initial, reload]);

  const turns = conversation?.turns ?? [];
  const report = reportSelection === undefined
    ? turns.findLast(turn => turn.reply.analysis?.observations.length)
    : turns.find(turn => turn.requestId === reportSelection && turn.reply.analysis?.observations.length);
  const full = turns.length >= MAX_TURNS;
  const welcome = !loading && !loadError && turns.length === 0 && !busy;
  const blocked = loading || Boolean(loadError) || full;
  useEffect(() => {
    const viewport = log.current;
    const latest = exchanges.current?.lastElementChild;
    if (!scrollToLatest.current || !viewport || !latest) return;
    viewport.scrollTop += latest.getBoundingClientRect().top - viewport.getBoundingClientRect().top - 16;
    scrollToLatest.current = false;
  }, [busy, loading, turns.length]);

  async function send() {
    if (sending.current || blocked || sendError?.conflict || !text.trim()) return;
    const messageText = text.trim();
    const reportId = report?.requestId ?? null;
    const request = pending?.message.text === messageText && pending.message.recordId === reportId ? pending : { id: id ?? pending?.id ?? requestId(),
      message: { requestId: requestId(), expectedTurns: turns.length, text: messageText, task: reportId ? 'REPORT' : 'ASK', recordId: reportId } } satisfies Pending;
    sending.current = true;
    scrollToLatest.current = true;
    setPending(request);
    setBusy(true);
    setSendError(undefined);
    try {
      const saved = await mutate<Sent>(`/api/assistant/conversations/${request.id}/messages`, 'POST', request.message);
      const next = { summary: saved.summary, turns: [...turns, saved.turn] };
      if (!active.current) { onSaved(next, false); return; }
      scrollToLatest.current = true;
      setConversation(next);
      setText('');
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
        <p>Ask about k6 scripts, experiment settings, or saved runs.</p>
      </div> : <div ref={log} className={styles.scroll}>
        <div className={styles.transcript}>
          <h1 title={conversation?.summary.title}>{conversation?.summary.title ?? 'New chat'}</h1>
          <div ref={exchanges} className={styles.turns} role="log" aria-label="Conversation messages">
            {turns.map(turn => <Exchange key={turn.requestId} turn={turn} disabled={busy || full}
              onAsk={() => { setReportSelection(turn.requestId); textarea.current?.focus({ preventScroll: true }); }}
              onRetry={() => {
                setText(turn.text);
                setReportSelection(turn.task === 'REPORT' ? turn.recordId : null);
                textarea.current?.focus({ preventScroll: true });
              }} />)}
            {busy && pending && <div className={styles.exchange}>
              <Question text={pending.message.text} />
              <p className={styles.thinking} role="status"><Icon name="message" width={18} height={18} />
                Assistant is thinking<span aria-hidden="true" className={styles.thinkingDots}><i /><i /><i /></span>
              </p>
            </div>}
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
              <label className={styles.screenReader} htmlFor="assistant-message">Message</label>
              <textarea id="assistant-message" ref={textarea} value={busy ? '' : text} onChange={event => setText(event.target.value)} maxLength={MAX_LENGTH}
                rows={2} readOnly={busy} placeholder="Message Loadpilot…" aria-describedby="assistant-message-hint"
                onKeyDown={event => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault(); void send();
                  }
                }} />
              <div className={styles.composerFooter}>
                {report && <div className={styles.reportContext}>
                  <a href={`#analysis-${report.requestId}`} aria-label="View attached report">{report.reply.analysis?.comparison ? 'Run comparison' : 'Saved run report'}</a>
                  <button type="button" aria-label="Remove report context" disabled={busy} onClick={() => {
                    setReportSelection(null); textarea.current?.focus({ preventScroll: true });
                  }}>Remove</button>
                </div>}
                {text.length >= MAX_LENGTH * 0.9 && <span>{text.length}/{MAX_LENGTH}</span>}
                <button type="submit" className={styles.send} aria-label="Send message" disabled={busy || !text.trim() || sendError?.conflict}>
                  Send<Icon name="send" width={17} height={17} />
                </button>
              </div>
            </div>
            <p id="assistant-message-hint" className={styles.hint}>
              Chats are saved. Gemini may receive your message and recent AI exchanges.{report && ' The attached report shares saved HTTP observations and findings.'} Keep sensitive data out of chat.
            </p>
            <BrowserTransportNotice className={styles.transport} />
          </form>}
      </div>
      {welcome && <div className={styles.starters} role="group" aria-label="Example questions">
        {starterPrompts.map(example => <button type="button" key={example} onClick={() => {
          if (!text || text === prompt || starterPrompts.includes(text)) setText(example);
          textarea.current?.focus();
        }}>
          <span>{example}</span><Icon name="arrow" width={16} height={16} />
        </button>)}
      </div>}
    </>}
  </section>;
}

function Question({ text }: { text: string }) {
  return <div className={styles.question}><span className={styles.screenReader}>You</span><p>{text}</p></div>;
}

function Exchange({ turn, onAsk, onRetry, disabled }: { turn: Turn; onAsk: () => void; onRetry: () => void; disabled: boolean }) {
  return <div className={styles.exchange}>
    <Question text={turn.text} />
    <article className={styles.answer} aria-label="Assistant reply">
      <span className={styles.answerTitle}><Icon name="message" width={18} height={18} />Loadpilot</span>
      {turn.reply.suggestion ? <>
        <p className={styles.copy}>{turn.reply.suggestion}</p>
        <details className={styles.guidance}>
          <summary>Loadpilot guidance</summary><p className={styles.copy}>{turn.reply.text}</p>
        </details>
      </> : <p className={styles.copy}>{turn.reply.text}</p>}
      {turn.reply.analysis && <AssistantRunReport id={turn.requestId} report={turn.reply.analysis}
        onAsk={onAsk} disabled={disabled} />}
      {turn.reply.providerUnavailable && !turn.reply.analysis && <div className={styles.replyActions}>
        <p className={styles.waiting}>{providerIssueMessage(turn.reply.providerIssue)}</p>
        <button type="button" disabled={disabled} onClick={onRetry}>Edit and retry</button>
      </div>}
      {turn.reply.links.length > 0 && <ul className={styles.links} aria-label="Related pages">
        {turn.reply.links.map(link => {
          const href = answerHref(link);
          const label = turn.reply.analysis?.status === 'PROVIDER_UNAVAILABLE' && link.destination === 'RUN_EVIDENCE' && link.recordId === turn.recordId
            ? 'Return to run evidence to retry' : link.label;
          return href && <li key={href}><Link href={href}>{label}<Icon name="arrow" width={15} height={15} /></Link></li>;
        })}
      </ul>}
    </article>
  </div>;
}
