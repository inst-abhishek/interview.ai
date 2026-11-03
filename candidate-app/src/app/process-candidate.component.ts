import { Component, Input, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

type Turn = {
  id: string;
  role: string;
  text: string;
  topic: number;
  followUp: boolean;
  at: number;
};
type Round = {
  id: string;
  title: string;
  state: string;
  kind: string;
  deadline: number | null;
  transcript?: Turn[];
  draft?: string;
  draftRevision?: number;
  revision?: number;
  assessmentUrl?: string;
  submittedAt?: number;
  submissionReference?: string;
};
type Journey = {
  title: string;
  name: string;
  onHold: boolean;
  closed: boolean;
  rounds: Round[];
  aiAvailable: boolean;
  connectionManagerEnabled?: boolean;
  serverTime: number;
};

@Component({
  selector: 'exl-process-candidate',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './process-candidate.component.html',
  styleUrls: ['./process-candidate.component.css'],
})
export class ProcessCandidateComponent implements OnInit, OnDestroy {
  @Input() code = '';
  journey?: Journey;
  busy = false;
  saving = false;
  error = '';
  status = '';
  consent = false;
  answer = '';
  dirty = false;
  draftRevision = 0;
  endConfirmed = false;
  repeatVisible = false;
  connectionPanelOpen = false;
  closeConnectionPopover(): void {
    this.connectionPanelOpen = false;
  }
  interviewerRequestFailed = false;
  managerBusy = false;
  managerError = '';
  managerNotice = '';
  managerConnected = false;
  managerChecked = false;
  managerCheckFailed = false;
  get connectionLabel() {
    if (this.managerCheckFailed) return 'Unable to check connection';
    if (!this.managerChecked)
      return this.managerBusy ? 'Checking connection...' : 'Connection not checked';
    return this.managerConnected ? 'ChatGPT connected' : 'ChatGPT not connected';
  }
  managerAccount = '';
  managerModels: { slug: string; displayName: string }[] = [];
  managerModel = '';
  managerUrl = '';
  managerPopupBlocked = false;
  continueWithChatGpt() {
    if (!this.managerUrl) return;
    this.managerError = '';
    this.managerPopupBlocked = false;
    try {
      const target = new URL(this.managerUrl);
      if (
        target.origin !== 'http://127.0.0.1:8080' ||
        target.pathname !== '/api/process-candidate/connection/authorize'
      )
        throw Error('Invalid connection link. Select Connect ChatGPT again.');
      const popup = window.open('about:blank', '_blank');
      if (!popup) {
        this.managerPopupBlocked = true;
        this.managerError =
          'Your browser blocked the sign-in tab. Allow pop-ups for 127.0.0.1, then try Continue with ChatGPT again, or use the sign-in link below.';
        return;
      }
      popup.opener = null;
      popup.location.href = target.href;
      this.managerNotice =
        'ChatGPT sign-in opened in a new tab. Complete it, then return here and refresh connection.';
    } catch (e) {
      this.managerError =
        e instanceof Error ? e.message : 'Could not open sign-in. Select Connect ChatGPT again.';
    }
  }
  managerDemoEnabled = false;
  async manage(action: 'refresh' | 'connect' | 'enable' | 'disable' | 'disconnect') {
    if (this.managerBusy) return;
    this.managerBusy = true;
    this.managerError = '';
    this.managerNotice = '';
    try {
      if (!this.journey?.connectionManagerEnabled)
        throw Error('Local demo connection manager is disabled.');
      if (action === 'connect') {
        this.managerPopupBlocked = false;
        this.managerUrl = (await this.api<{ url: string }>('/connection/connect', {})).url;
        this.managerNotice = 'Continue with ChatGPT, then refresh connection here.';
      } else {
        if (action === 'enable' || action === 'disable')
          await this.api('/connection/select', {
            enabled: action === 'enable',
            model: this.managerModel,
          });
        if (action === 'disconnect') await this.api('/connection/disconnect', {});
        const status = await this.api<{ connected: boolean; email: string; enabled: boolean }>(
          '/connection'
        );
        this.managerConnected = status.connected;
        this.managerChecked = true;
        this.managerCheckFailed = false;
        this.managerAccount = status.email;
        this.managerDemoEnabled = status.enabled;
        this.managerModels = status.connected ? await this.api('/connection/models') : [];
        if (!this.managerModels.some((m) => m.slug === this.managerModel))
          this.managerModel = this.managerModels[0]?.slug || '';
        if (status.connected && !status.enabled && this.managerModel && action !== 'disable') {
          await this.api('/connection/select', { enabled: true, model: this.managerModel });
          this.managerDemoEnabled = true;
        }
        this.managerNotice = this.managerDemoEnabled
          ? 'ChatGPT is selected for interview questions and follow-ups.'
          : 'Connect ChatGPT to enable interview questions and follow-ups automatically.';
        if (status.connected) this.managerUrl = '';
        await this.refresh();
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      this.managerCheckFailed = true;
      this.managerError = /failed to fetch|networkerror|load failed/i.test(message)
        ? 'Cannot reach the connection manager. Restart the backend with the latest code, then refresh this page. If it persists, check backend connectivity and CORS configuration.'
        : message;
    } finally {
      this.managerBusy = false;
    }
  }
  get interviewerLabel() {
    if (!this.active) return 'AI paused';
    if (this.interviewerRequestFailed) return 'AI needs attention';
    if (this.journey?.connectionManagerEnabled) {
      if (this.managerCheckFailed) return 'AI connection unavailable';
      if (!this.managerChecked) return 'AI connection not checked';
      if (!this.managerConnected) return 'AI not connected';
      return this.managerDemoEnabled ? 'AI connected' : 'AI connected - not enabled';
    }
    return this.journey?.aiAvailable ? 'AI connected' : 'AI not connected';
  }
  remaining = 0;
  private offset = 0;
  private timer?: ReturnType<typeof setInterval>;
  private poll?: ReturnType<typeof setInterval>;
  private pending?: { questionId: string; text: string; requestId: string };

  async ngOnInit() {
    await this.refresh();
    if (this.journey?.connectionManagerEnabled && this.aiConnectionVisible)
      await this.manage('refresh');
    this.timer = setInterval(() => this.updateTime(), 1000);
    this.poll = setInterval(() => void this.tick(), 5000);
  }

  ngOnDestroy() {
    if (this.timer !== undefined) clearInterval(this.timer);
    if (this.poll !== undefined) clearInterval(this.poll);
  }

  async api<T>(path = '', body?: unknown): Promise<T> {
    const response = await fetch('http://127.0.0.1:8080/api/process-candidate' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'X-Process-Code': this.code, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      let message = 'Connection failed. Your last successfully saved answers are retained.';
      try {
        message = (await response.json()).message || message;
      } catch {}
      throw Error(message);
    }
    return response.json();
  }

  get completed() {
    return this.journey?.rounds.every((r) => ['FEEDBACK_PUBLISHED', 'SKIPPED'].includes(r.state));
  }
  get aiRound() {
    return this.journey?.rounds.find((r) => r.kind === 'AI_PRELIMINARY');
  }
  get aiConnectionVisible() {
    return this.active && !!this.aiRound && ['READY', 'IN_PROGRESS'].includes(this.aiRound.state);
  }
  get lastTurn() {
    return this.aiRound?.transcript?.at(-1);
  }
  get answering() {
    return this.aiRound?.state === 'IN_PROGRESS' && this.lastTurn?.role === 'INTERVIEWER';
  }
  get waitingNext() {
    return this.aiRound?.state === 'IN_PROGRESS' && this.lastTurn?.role === 'CANDIDATE';
  }
  get active() {
    return !!this.journey && !this.journey.closed && !this.journey.onHold;
  }
  get timeLabel() {
    return Math.floor(this.remaining / 60) + ':' + String(this.remaining % 60).padStart(2, '0');
  }

  private receive(view: Journey) {
    const before = this.aiRound;
    const incoming = view.rounds.find((r) => r.kind === 'AI_PRELIMINARY');
    if (before && incoming && (incoming.revision || 0) < (before.revision || 0)) return;
    const previousQuestion = this.lastTurn?.id;
    this.journey = view;
    this.offset = view.serverTime - Date.now();
    if (!this.dirty && !this.saving && (incoming?.draftRevision || 0) >= this.draftRevision) {
      this.answer = incoming?.draft || '';
      this.draftRevision = incoming?.draftRevision || 0;
    }
    if (
      previousQuestion &&
      previousQuestion !== this.lastTurn?.id &&
      this.lastTurn?.role === 'INTERVIEWER'
    ) {
      this.answer = incoming?.draft || '';
      this.dirty = false;
      this.draftRevision = incoming?.draftRevision || 0;
    }
    this.updateTime();
  }

  updateTime() {
    this.remaining = this.aiRound?.deadline
      ? Math.max(0, Math.ceil((this.aiRound.deadline - Date.now() - this.offset) / 1000))
      : 0;
  }

  async refresh() {
    try {
      this.receive(await this.api<Journey>());
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
    }
  }

  async tick() {
    if (this.saving) return;
    if (this.dirty && !this.busy && this.answering && this.active) await this.saveDraft();
    await this.refresh();
  }

  async run(action: () => Promise<void>) {
    if (this.busy || this.saving) return;
    this.busy = true;
    this.error = '';
    try {
      await action();
      this.interviewerRequestFailed = false;
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
      if (this.waitingNext) this.interviewerRequestFailed = true;
    } finally {
      this.busy = false;
    }
  }

  async start() {
    if (!this.aiRound || !this.consent) return;
    await this.run(async () =>
      this.receive(
        await this.api<Journey>('/' + this.aiRound!.id + '/start', { consent: this.consent })
      )
    );
  }

  changed() {
    this.dirty = true;
    this.status = 'Unsaved draft';
  }

  async saveDraft() {
    if (!this.aiRound || !this.lastTurn || !this.dirty || this.saving) return;
    this.saving = true;
    const savedText = this.answer;
    try {
      const saved = await this.api<{ revision: number }>('/' + this.aiRound.id + '/draft', {
        questionId: this.lastTurn.id,
        text: savedText,
        revision: this.draftRevision,
      });
      this.draftRevision = saved.revision;
      if (savedText === this.answer) {
        this.dirty = false;
        this.status = 'Draft saved to server';
      }
    } catch (error) {
      this.status = 'Draft not saved. Keep this page open and retry.';
      this.error = error instanceof Error ? error.message : String(error);
    } finally {
      this.saving = false;
    }
  }

  async send() {
    if (!this.aiRound || !this.lastTurn || !this.answer.trim() || !this.answering) return;
    await this.run(async () => {
      const question = this.lastTurn!;
      if (
        !this.pending ||
        this.pending.questionId !== question.id ||
        this.pending.text !== this.answer
      ) {
        this.pending = {
          questionId: question.id,
          text: this.answer,
          requestId: crypto.randomUUID(),
        };
      }
      const view = await this.api<Journey>('/' + this.aiRound!.id + '/answer', this.pending);
      this.dirty = false;
      this.pending = undefined;
      this.receive(view);
      this.status = 'Answer saved to server';
      this.receive(await this.api<Journey>('/' + this.aiRound!.id + '/next', {}));
    });
  }

  async next() {
    if (!this.aiRound) return;
    await this.run(async () =>
      this.receive(await this.api<Journey>('/' + this.aiRound!.id + '/next', {}))
    );
  }

  async finish() {
    if (!this.aiRound || !this.endConfirmed) return;
    if (this.dirty) {
      await this.saveDraft();
      if (this.dirty) return;
    }
    await this.run(async () => {
      this.receive(await this.api<Journey>('/' + this.aiRound!.id + '/finish', {}));
      this.endConfirmed = false;
    });
  }

  label(state: string) {
    return (
      (
        {
          LOCKED: 'Waiting for an earlier round',
          READY: 'Ready',
          INVITED: 'Available',
          IN_PROGRESS: 'In progress',
          SUBMITTED: 'Round complete - awaiting staff review',
          FEEDBACK_PUBLISHED: 'Review complete',
          SKIPPED: 'Skipped by your recruiter',
        } as Record<string, string>
      )[state] || state
    );
  }
}
