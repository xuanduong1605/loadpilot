import type { IconName } from '../../components/icon';

export const tasks = {
  ASK: { title: 'Ask a question', icon: 'help', prompt: 'How do I approve a script?' },
  SCRIPT: { title: 'Modify a scenario', icon: 'scenarios', prompt: 'Help me revise a k6 scenario.' },
  STATUS: { title: 'Check test status', icon: 'activity', prompt: 'What is the status of this test?' },
  START: { title: 'Prepare a test run', icon: 'play', prompt: 'Help me prepare to run this test.' },
  RESULT: { title: 'Analyze results', icon: 'chart', prompt: 'What do the saved results show?' },
  EDIT: { title: 'Modify a test', icon: 'edit', prompt: 'Help me update my experiment settings.' },
} satisfies Record<string, { title: string; icon: IconName; prompt: string }>;

export type Task = keyof typeof tasks;
export type Summary = { id: string; title: string; turnCount: number; updatedAt: string };
type Destination = 'SYSTEMS' | 'SCRIPTS' | 'SCRIPT_EDITOR' | 'RUNS' | 'RUN_MONITOR' | 'RUN_EVIDENCE' | 'INPUTS' | 'INPUT_REVIEW' | 'SETUP';
export type AnswerLink = { destination: Destination; recordId: string | null; version: number | null; label: string };
export type Message = { text: string; task: Task; recordId: string | null; requestId: string; expectedTurns: number };
export type EvidenceId = 'WHOLE_RUN' | 'PARTIAL_HTTP' | 'COMPARISON_WHOLE_RUN';
export type RunAnalysis = {
  status: 'READY' | 'INSUFFICIENT_DATA' | 'PROVIDER_UNAVAILABLE';
  runStatus: string;
  verdict: string;
  comparison?: { runId: string; runStatus: string; verdict: string; requestedIterationsPerSecond: number };
  observations: { id: EvidenceId; from: string; until: string; httpRequests: number; p95LatencyMs: number;
    httpFailureRatio: number; httpRequestsPerSecond: number | null }[];
  findings: { hypothesis: string; suggestedCheck: string; evidenceIds: EvidenceId[] }[];
};
export type Turn = Omit<Message, 'expectedTurns'> & {
  number: number;
  reply: { text: string; links: AnswerLink[]; suggestion: string | null; providerUnavailable: boolean; analysis?: RunAnalysis };
};
export type Conversation = { summary: Summary; turns: Turn[] };
export type History = { items: Summary[]; hasMore: boolean };
export type Sent = { summary: Summary; turn: Turn };

export const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
export const taskFromQuery = (value: string | null): Task => value && Object.hasOwn(tasks, value) ? value as Task : 'ASK';

export function answerHref(link: AnswerLink): string | undefined {
  const id = link.recordId && uuid.test(link.recordId) ? link.recordId : null;
  const version = Number.isSafeInteger(link.version) && Number(link.version) > 0 ? link.version : null;
  switch (link.destination) {
    case 'SYSTEMS': return '/targets/';
    case 'SCRIPTS': return '/scenarios/';
    case 'SCRIPT_EDITOR': return id && version ? `/scenarios/editor/?id=${id}&version=${version}` : '/scenarios/editor/';
    case 'RUNS': return '/experiments/';
    case 'RUN_MONITOR': return id ? `/experiments/monitor/?run=${id}` : undefined;
    case 'RUN_EVIDENCE': return id ? `/experiments/monitor/?run=${id}&panel=evidence` : undefined;
    case 'INPUTS': return '/experiments/review/';
    case 'INPUT_REVIEW': return id ? `/experiments/review/?snapshot=${id}` : undefined;
    case 'SETUP': return id ? `/experiments/new/?draft=${id}` : '/experiments/new/';
  }
}
