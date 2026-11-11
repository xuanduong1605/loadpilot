'use client';

import { useEffect, useRef, useState } from 'react';
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

export default function CodeEditor({ value, onChange, disabled }: {
  value: string; onChange: (value: string) => void; disabled: boolean;
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
            { tag: tags.keyword, color: '#c5b4f5' },
            { tag: [tags.string, tags.special(tags.string)], color: '#9dddba' },
            { tag: [tags.number, tags.bool, tags.null], color: '#f0c06a' },
            { tag: tags.comment, color: '#a2b0c3', fontStyle: 'italic' },
            { tag: [tags.function(tags.variableName), tags.definition(tags.variableName)], color: '#9dc9ff' },
            { tag: [tags.operator, tags.punctuation], color: '#becbdd' },
          ])),
          editing.current.of([]), wrapping.current.of([]),
          EditorView.contentAttributes.of({ 'aria-label': 'Script source', 'aria-describedby': 'script-editor-help', spellcheck: 'false' }),
          EditorView.theme({
            '&': { fontSize: '13px', backgroundColor: '#11161f', color: '#e8edf5' },
            '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', overflow: 'auto', minHeight: '360px', maxHeight: '620px' },
            '.cm-content': { padding: '12px 0' },
            '.cm-gutters': { backgroundColor: '#11161f', color: '#a2b0c3', borderColor: 'var(--line)' },
            '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#1c2d41' },
            '&.cm-focused': { outline: '2px solid var(--primary)', outlineOffset: '-2px' },
            '.cm-panels': { backgroundColor: 'var(--canvas)', color: 'var(--ink)' },
            '.cm-search': { whiteSpace: 'normal' },
            '.cm-search input': { maxWidth: '100%' },
            '.cm-tooltip': { maxWidth: 'min(360px, 85vw)', backgroundColor: 'var(--surface-raised)', color: 'var(--ink)', borderColor: 'var(--line-strong)' },
            '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#9dc9ff' },
            '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#20344f' },
            '.cm-searchMatch': { backgroundColor: '#51401f', outline: '1px solid #b89550' },
          }, { dark: true }),
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
    <div className={styles.editorToolbar} role="group" aria-label="Script editor tools">
      <span className={styles.fileName}>scenario.js</span>
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(openSearchPanel)}>Find / replace</Button>
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(indentMore)}>Indent</Button>
      <Button variant="secondary" disabled={disabled || !ready} onClick={() => command(indentLess)}>Outdent</Button>
      <Button variant="secondary" disabled={!ready} aria-pressed={wrap} onClick={() => setWrap(value => !value)}>Wrap lines</Button>
    </div>
    {!ready && <p role="status">Loading code editor…</p>}
    <div className={styles.editorHost} ref={host} />
    <div className={styles.editorStatus}><span>JavaScript · UTF-8</span><span>Ln {position.line}, Col {position.column} · {position.lines} lines</span></div>
    <p id="script-editor-help" className={styles.editorHelp}>Ctrl/Cmd+F: find · Ctrl+Space: completions · Ctrl/Cmd+Z: undo · Ctrl/Cmd+]: indent. Tab moves focus.</p>
  </div>;
}
