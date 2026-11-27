'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Icon } from '../../components/icon';
import { api } from '../../lib/api';
import { AssistantChat } from './assistant-chat';
import { promptFromQuery, type Conversation, type History, type Summary } from './assistant-data';
import styles from './assistant.module.css';

export function AssistantWorkspace() {
  const router = useRouter();
  const query = useSearchParams();
  const id = query.get('chat');
  const prompt = id ? '' : promptFromQuery(query.get('task'));
  const [recent, setRecent] = useState<Conversation>();
  const [historyVersion, setHistoryVersion] = useState(0);
  const [newVersion, setNewVersion] = useState(0);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [focusTarget, setFocusTarget] = useState<string>();

  function newChat() {
    setRecent(undefined);
    setHistoryOpen(false);
    setFocusTarget('');
    setNewVersion(version => version + 1);
    router.push('/assistant/');
  }

  function saved(conversation: Conversation, first: boolean) {
    setHistoryVersion(version => version + 1);
    if (first) {
      setRecent(conversation);
      router.replace(`/assistant/?chat=${conversation.summary.id}`, { scroll: false });
    }
  }

  return <div className={styles.workspace} data-assistant data-transcript={Boolean(id)}>
    <header className={styles.bar}>
      <span className={styles.brand}><Icon name="message" />Assistant</span>
      <button className={styles.historyToggle} type="button" aria-expanded={historyOpen} aria-controls="assistant-history"
        onClick={() => setHistoryOpen(open => !open)}><Icon name="clock" width={16} height={16} />History</button>
    </header>
    <div className={styles.body}>
      <aside className={styles.history} id="assistant-history" data-open={historyOpen} aria-label="Conversation history">
        <button className={styles.newChat} type="button" onClick={newChat}><Icon name="plus" width={17} height={17} />New chat</button>
        <HistoryList key={historyVersion} selected={id} onSelect={next => {
          if (next === id) return;
          setRecent(undefined);
          setHistoryOpen(false);
          setFocusTarget(next);
        }} />
      </aside>
      <AssistantChat key={id ?? `${prompt}-${newVersion}`} id={id} initial={recent?.summary.id === id ? recent : undefined}
        prompt={prompt} focusOnOpen={focusTarget === (id ?? '')} onSaved={saved} />
    </div>
  </div>;
}

const date = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const HISTORY_SIZE = 20;

function HistoryList({ selected, onSelect }: { selected: string | null; onSelect: (id: string) => void }) {
  const [items, setItems] = useState<Summary[]>([]);
  const [offset, setOffset] = useState(0);
  const [retry, setRetry] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void api<History>(`/api/assistant/conversations?offset=${offset}`, { signal: controller.signal })
      .then(page => {
        if (!active) return;
        setItems(current => offset ? [...current, ...page.items.filter(item => !current.some(entry => entry.id === item.id))] : page.items);
        setHasMore(page.hasMore);
      })
      .catch(reason => { if (active) setError(reason.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [offset, retry]);

  return <nav aria-label="Conversations" className={styles.historyList}>
    <h2>Conversations</h2>
    {items.length > 0 && <ul>{items.map(item => <li key={item.id}>
      <Link href={`/assistant/?chat=${item.id}`} scroll={false} onNavigate={() => onSelect(item.id)} aria-current={item.id === selected ? 'page' : undefined}>
        <span>{item.title}</span><time dateTime={item.updatedAt}>{date.format(new Date(item.updatedAt))}</time>
      </Link>
    </li>)}</ul>}
    {loading && <p role="status">Loading conversations…</p>}
    {error && <><p role="alert" className={styles.error}>{error}</p><button type="button" onClick={() => setRetry(value => value + 1)}>Retry history</button></>}
    {hasMore && !loading && !error && <button type="button" onClick={() => setOffset(value => value + HISTORY_SIZE)}>Load older chats</button>}
    {!items.length && !loading && !error && <p>Your chats will appear here.</p>}
  </nav>;
}
