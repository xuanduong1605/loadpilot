'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { basicSetup } from 'codemirror';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { javascript, javascriptLanguage } from '@codemirror/lang-javascript';
import { completeFromList } from '@codemirror/autocomplete';
import { indentMore, indentLess } from '@codemirror/commands';
import { openSearchPanel } from '@codemirror/search';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';
import { Button } from '../../components/form-controls';
import styles from './scenarios.module.css';

const k6Completion = completeFromList([
  { label: 'http', type: 'namespace', info: 'k6/http — import http from \'k6/http\'' },
  { label: 'check', type: 'function', info: 'k6 check(value, predicates)' },
  { label: 'sleep', type: 'function', info: 'k6 sleep(seconds)' },
  { label: 'group', type: 'function', info: 'k6 group(name, callback)' },
  { label: '__ENV', type: 'variable', info: 'Execution environment; never embed credential values in source.' },
]);

export default function CodeEditor({ value, onChange, disabled, toolsHost }: {
  value: string; onChange: (value: string) => void; disabled: boolean; toolsHost: HTMLDivElement | null;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const change = useRef(onChange);
  const external = useRef(false);
  const editing = useRef(new Compartment());
  const wrapping = useRef(new Compartment());
  const initialValue = useRef(value);
  const [position, setPosition] = useState({ line: 1, column: 1, lines: 1 });
  const [wrap, setWrap] = useState(false);
  const [ready, setReady] = useState(false);
  change.current = onChange;

  useEffect(() => {
    if (!host.current) return;
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: initialValue.current,
        extensions: [
          basicSetup, javascript(), javascriptLanguage.data.of({ autocomplete: k6Completion }),
          syntaxHighlighting(HighlightStyle.define([
            { tag: tags.keyword, color: 'var(--primary)', fontWeight: '600' },
            { tag: [tags.string, tags.special(tags.string)], color: 'var(--success)' },
            { tag: [tags.number, tags.bool, tags.null], color: 'var(--warning)' },
            { tag: tags.comment, color: 'var(--muted)', fontStyle: 'italic' },
            { tag: [tags.function(tags.variableName), tags.definition(tags.variableName)], color: 'var(--primary-hover)' },
          ])),
          editing.current.of([]), wrapping.current.of([]),
          EditorView.contentAttributes.of({ 'aria-label': 'Script source', 'aria-describedby': 'script-editor-help', spellcheck: 'false' }),
          EditorView.theme({
            '&': { fontSize: '13px', backgroundColor: 'var(--surface)', color: 'var(--ink)' },
            '.cm-scroller': { fontFamily: 'var(--mono)', overflow: 'auto', height: 'clamp(360px, 62dvh, 760px)' },
            '.cm-content': { padding: '12px 0' },
            '.cm-gutters': { backgroundColor: 'var(--surface)', color: 'var(--muted)', borderColor: 'var(--line)' },
            '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'var(--surface-hover)' },
            '&.cm-focused': { outline: '2px solid var(--primary)', outlineOffset: '-2px' },
            '.cm-panels': { backgroundColor: 'var(--canvas)', color: 'var(--ink)' },
            '.cm-search': { whiteSpace: 'normal' },
            '.cm-panel.cm-search .cm-textfield': { maxWidth: '100%', minHeight: '30px', border: '1px solid var(--line-strong)', borderRadius: 'var(--radius-sm)', fontSize: 'inherit' },
            '.cm-panel.cm-search .cm-button': { minHeight: '30px', background: 'var(--surface)', border: '1px solid var(--line-strong)', borderRadius: 'var(--radius-sm)', fontSize: 'inherit', cursor: 'pointer' },
            '.cm-panel.cm-search .cm-button:hover': { background: 'var(--surface-hover)' },
            '.cm-tooltip': { maxWidth: 'min(360px, 85vw)', backgroundColor: 'var(--surface)', color: 'var(--ink)', borderColor: 'var(--line-strong)' },
            '.cm-tooltip-autocomplete > ul > li[aria-selected="true"]': { backgroundColor: 'var(--primary-soft)', color: 'var(--ink)' },
            '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--primary)' },
            '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: 'var(--primary-soft)' },
            '.cm-selectionMatch': { backgroundColor: 'var(--primary-soft)' },
            '.cm-searchMatch': { backgroundColor: 'var(--surface-hover)', outline: '1px solid var(--warning)' },
            '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: 'var(--warning-soft)' },
          }),
          EditorView.updateListener.of(update => {
            if (update.docChanged && !external.current) change.current(update.state.doc.toString());
            if (update.docChanged || update.selectionSet) {
              const line = update.state.doc.lineAt(update.state.selection.main.head);
              setPosition({ line: line.number, column: update.state.selection.main.head - line.from + 1, lines: update.state.doc.lines });
            }
          }),
        ],
      }),
    });
    view.current = editor; setReady(true);
    return () => { editor.destroy(); view.current = null; };
  }, []);

  useEffect(() => {
    const editor = view.current;
    if (!editor || editor.state.doc.toString() === value) return;
    external.current = true;
    try { editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } }); }
    finally { external.current = false; }
  }, [value]);
  useEffect(() => {
    view.current?.dispatch({ effects: editing.current.reconfigure([EditorState.readOnly.of(disabled), EditorView.editable.of(!disabled)]) });
  }, [disabled]);
  useEffect(() => {
    view.current?.dispatch({ effects: wrapping.current.reconfigure(wrap ? EditorView.lineWrapping : []) });
  }, [wrap]);

  function command(action: (editor: EditorView) => boolean) {
    if (!view.current) return;
    action(view.current); view.current.focus();
  }
  return <div className={styles.codeEditor}>
    {toolsHost && createPortal(<><div className={styles.editorTools} role="group" aria-label="Script editor tools">
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(openSearchPanel)}>Find / replace</Button>
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(indentMore)}>Indent</Button>
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(indentLess)}>Outdent</Button>
      <Button variant="secondary" disabled={!ready} aria-pressed={wrap} onClick={() => setWrap(value => !value)}>Wrap lines</Button>
    </div><p id="script-editor-help" className={styles.editorHelp}>Ctrl/Cmd+F: find · Ctrl+Space: completions · Ctrl/Cmd+Z: undo. Tab moves focus.</p></>, toolsHost)}
    {!ready && <p role="status">Loading code editor…</p>}
    <div className={styles.editorHost} ref={host} />
    <div className={styles.editorStatus}><span>JavaScript · UTF-8</span><span>Ln {position.line}, Col {position.column} · {position.lines} lines</span></div>
  </div>;
}
