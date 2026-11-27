export type Task = 'ASK' | 'SCRIPT' | 'STATUS' | 'START' | 'RESULT' | 'EDIT' | 'REPORT';

export const starterPrompts = [
  'How do I approve a script?',
  'Show me an example k6 response check.',
  'What do my latest run results show?',
];

const linkedPrompts: Partial<Record<Task, string>> = {
  SCRIPT: 'Help me revise a k6 scenario.',
  STATUS: 'What is the status of my latest run?',
  START: 'How do I start a test run?',
  RESULT: 'What do my latest run results show?',
  EDIT: 'Help me update my experiment settings.',
};
export type Summary = { id: string; title: string; turnCount: number; updatedAt: string };
type Destination = 'SYSTEMS' | 'SCRIPTS' | 'SCRIPT_EDITOR' | 'RUNS' | 'RUN_MONITOR' | 'RUN_EVIDENCE' | 'INPUTS' | 'INPUT_REVIEW' | 'SETUP';
export type AnswerLink = { destination: Destination; recordId: string | null; version: number | null; label: string };
export type Message = { text: string; task: Task; recordId: string | null; requestId: string; expectedTurns: number };
export type EvidenceId = 'WHOLE_RUN' | 'PARTIAL_HTTP' | 'COMPARISON_WHOLE_RUN';
export type ProviderIssue = 'BUSY' | 'TIMEOUT' | 'INVALID_RESPONSE' | 'UNAVAILABLE';

export function providerIssueMessage(issue?: ProviderIssue) {
  switch (issue) {
    case 'BUSY': return 'Gemini is handling too many requests. Wait a moment and try again.';
    case 'TIMEOUT': return 'Gemini took too long to reply. Try again.';
    case 'INVALID_RESPONSE': return 'Gemini returned an unusable response. Try again.';
    default: return 'Gemini is temporarily unavailable. Try again later.';
  }
}

export type RunAnalysis = {
  status: 'READY' | 'INSUFFICIENT_DATA' | 'PROVIDER_UNAVAILABLE';
  runStatus: string;
  verdict: string;
  providerIssue?: ProviderIssue;
  comparison?: { runId: string; runStatus: string; verdict: string; requestedIterationsPerSecond: number };
  observations: { id: EvidenceId; from: string; until: string; httpRequests: number; p95LatencyMs: number;
    httpFailureRatio: number; httpRequestsPerSecond: number | null }[];
  findings: { hypothesis: string; suggestedCheck: string; evidenceIds: EvidenceId[] }[];
};
export type Turn = Omit<Message, 'expectedTurns'> & {
  number: number;
  reply: { text: string; links: AnswerLink[]; suggestion: string | null; providerUnavailable: boolean; analysis?: RunAnalysis; providerIssue?: ProviderIssue };
};
export type Conversation = { summary: Summary; turns: Turn[] };
export type History = { items: Summary[]; hasMore: boolean };
export type Sent = { summary: Summary; turn: Turn };

export const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
export const promptFromQuery = (value: string | null): string => value && Object.hasOwn(linkedPrompts, value)
  ? linkedPrompts[value as Task] ?? '' : '';

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
