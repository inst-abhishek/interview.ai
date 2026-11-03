import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

type Interview = { id: string; title: string; scheduledAt: number };
type Candidate = { id: string; name: string; email: string };

@Component({
  selector: 'exl-hiring-process',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './hiring-process.component.html',
  styleUrls: ['./hiring-process.component.css'],
})
export class HiringProcessComponent implements OnInit {
  @Input() token = '';
  @Input() interviews: Interview[] = [];
  @Output() authenticationRequired = new EventEmitter<void>();
  @Output() openInterview = new EventEmitter<string>();
  processes: any[] = [];
  get activeOutsidePage() {
    return this.active && !this.processes.some((p) => p.ID === this.active.ID);
  }
  processSearch = '';
  processPage = 0;
  processPages = 1;
  processTotal = 0;
  settingsVisible = false;
  expandedCandidate = '';
  shareCandidate = '';
  async loadProcessPage() {
    const result = await this.api<any>(
      '/recruiter/processes/page?' +
        new URLSearchParams({ page: String(this.processPage), search: this.processSearch.trim() })
    );
    this.processes = result.items;
    this.processPage = result.page;
    this.processPages = result.pages;
    this.processTotal = result.total;
  }
  async searchProcesses(page = 0) {
    await this.run(async () => {
      this.processPage = page;
      await this.loadProcessPage();
    });
  }
  async chooseProcess(id: string) {
    const process = this.processes.find((p) => p.ID === id);
    if (process) await this.open(process);
  }
  currentRound(c: any) {
    return (
      c.steps.find((s: any) => !['FEEDBACK_PUBLISHED', 'SKIPPED'].includes(s.STATE)) ||
      c.steps.at(-1)
    );
  }
  stageRecommendation(c: any) {
    const stage = this.currentRound(c);
    return stage?.STATE === 'FEEDBACK_PUBLISHED' ? stage.RECOMMENDATION : undefined;
  }
  stageNumber(c: any) {
    return c.steps.indexOf(this.currentRound(c)) + 1;
  }
  async share(c: any) {
    this.shareCandidate = this.shareCandidate === c.ID ? '' : c.ID;
    if (this.shareCandidate) await this.showLink(c);
  }

  active?: any;
  rounds: any[] = [];
  plans: any[] = [];
  candidates: any[] = [];
  page = 0;
  pages = 1;
  total = 0;
  search = '';
  private appliedSearch = '';
  busy = false;
  error = '';
  notice = '';
  showCreate = false;
  title = '';
  aiEnabled = false;
  aiPlanVersion = '';
  aiFocus = '';
  aiMinutes = 15;
  assessmentIds = [''];
  aiAvailable = false;
  showInvite = false;
  inviteMode: 'pool' | 'new' = 'pool';
  newName = '';
  newEmail = '';
  newSkills = '';
  newExperience: number | null = null;
  async addInvite() {
    if (!this.active || !this.newName.trim() || !this.newEmail.trim()) return;
    await this.run(async () => {
      const result = await this.api<any>('/recruiter/processes/' + this.active.ID + '/add-invite', {
        name: this.newName,
        email: this.newEmail,
        skills: this.newSkills,
        experience: this.newExperience,
        expiresAt: new Date(this.expiry).getTime(),
      });
      this.newName = this.newEmail = this.newSkills = '';
      this.newExperience = null;
      this.showInvite = false;
      await this.loadCandidates();
      this.notice = result.created
        ? 'Candidate saved or reused from your pool and invited. Share the process link below.'
        : 'This candidate is already invited. Their existing profile and invitation were preserved.';
    });
  }
  pool: Candidate[] = [];
  poolPage = 0;
  poolPages = 1;
  poolSearch = '';
  selected = new Set<string>();
  expiry = '';
  skipTarget?: { id?: string; name: string; waive?: boolean };
  feedback: Record<string, any> = {};
  async viewAiFeedback(c: any) {
    if (this.feedback[c.ID]) {
      delete this.feedback[c.ID];
      return;
    }
    await this.run(async () => {
      this.feedback[c.ID] = await this.api(
        '/recruiter/processes/' + this.active.ID + '/candidates/' + c.ID + '/ai-feedback'
      );
    });
  }
  aiState(c: any) {
    return c.steps.find((s: any) => s.KIND === 'AI_PRELIMINARY')?.STATE;
  }
  stepLabel(c: any, s: any) {
    const next = c.steps.find((v: any) => !['FEEDBACK_PUBLISHED', 'SKIPPED'].includes(v.STATE));
    if (next === s && this.canAdvance(c)) return 'Ready for invitation';
    if (s.STATE === 'SKIPPED' && s.SUBMITTED_AT) return 'Review waived by recruiter';
    return this.label(s.STATE);
  }
  skipReason = '';
  includeFuture = false;
  copiedLinks: Record<string, string> = {};
  detailsVisible = false;
  prepareAiCopy() {
    if (!this.active) return;
    this.title = this.active.TITLE + ' with AI';
    this.assessmentIds = this.rounds
      .filter((r) => r.KIND === 'ASSESSMENT')
      .map((r) => r.INTERVIEW_ID);
    this.aiEnabled = true;
    this.aiPlanVersion = '';
    this.showCreate = true;
    this.notice =
      'AI-enabled copy prepared in the create form above. Create it, then invite candidates to receive new process links. Existing attempts remain unchanged.';
  }
  linkErrors: Record<string, string> = {};

  async showLink(candidate: any) {
    if (this.copiedLinks[candidate.ID]) return;
    try {
      const result = await this.api<{ url: string }>(
        '/recruiter/processes/' + this.active.ID + '/candidates/' + candidate.ID + '/link'
      );
      this.copiedLinks[candidate.ID] = result.url;
      delete this.linkErrors[candidate.ID];
    } catch {
      this.linkErrors[candidate.ID] = 'Link could not be loaded. Select Show link to retry.';
    }
  }

  trackIndex(index: number) {
    return index;
  }
  recommendationLabel(value: string) {
    return (
      ({ SHORTLIST: 'Shortlist', HOLD: 'Hold', REJECT: 'Reject' } as Record<string, string>)[
        value
      ] || value
    );
  }

  async ngOnInit() {
    await this.run(async () => {
      await this.loadProcessPage();
      const plans = await this.api<any>('/recruiter/processes/ai-status');
      this.aiAvailable = plans.aiAvailable;
    });
  }

  async api<T>(path: string, body?: unknown): Promise<T> {
    const response = await fetch('http://127.0.0.1:8080/api' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: 'Bearer ' + this.token, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      if (response.status === 401) this.authenticationRequired.emit();
      let message = 'Request failed (' + response.status + ')';
      try {
        message = (await response.json()).message || message;
      } catch {}
      throw Error(message);
    }
    return response.json();
  }

  async run(action: () => Promise<void>) {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.notice = '';
    try {
      await action();
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
    } finally {
      this.busy = false;
    }
  }

  async open(process: any) {
    await this.run(async () => {
      const detail = await this.api<any>('/recruiter/processes/' + process.ID);
      this.settingsVisible = false;
      this.active = detail.process;
      this.rounds = detail.rounds;
      this.page = 0;
      this.search = '';
      this.appliedSearch = '';
      this.showCreate = false;
      this.showInvite = false;
      this.selected.clear();
      await this.loadCandidates();
    });
  }

  async create() {
    await this.run(async () => {
      const detail = await this.api<any>('/recruiter/processes', {
        title: this.title,
        interviewIds: this.assessmentIds,
        aiPlanVersion: this.aiEnabled ? this.aiPlanVersion : null,
        aiMinutes: this.aiMinutes,
        aiEnabled: this.aiEnabled,
        aiFocus: this.aiFocus,
      });
      if (this.aiEnabled && !detail.rounds?.some((round: any) => round.KIND === 'AI_PRELIMINARY')) {
        throw Error(
          'The backend saved this process without the requested AI round. Do not invite candidates to it. Restart the backend with the latest code, refresh this page and create the process again.'
        );
      }
      this.active = detail.process;
      this.rounds = detail.rounds;
      this.showCreate = false;
      await this.loadProcessPage();
      await this.loadCandidates();
      this.notice =
        'Process created. The round plan is frozen for this process. Invite candidates next.';
    });
  }

  async loadCandidates() {
    if (!this.active) return;
    const result = await this.api<any>(
      '/recruiter/processes/' +
        this.active.ID +
        '/candidates?' +
        new URLSearchParams({ page: String(this.page), search: this.appliedSearch })
    );
    this.candidates = result.items;
    this.page = result.page;
    this.pages = result.pages;
    this.total = result.total;
    this.expandedCandidate = this.shareCandidate = '';
    this.copiedLinks = {};
  }

  async refresh() {
    await this.run(async () => {
      await this.loadProcessPage();
      const plans = await this.api<any>('/recruiter/processes/ai-status');
      this.aiAvailable = plans.aiAvailable;
      if (this.active) {
        const detail = await this.api<any>('/recruiter/processes/' + this.active.ID);
        this.active = detail.process;
        this.rounds = detail.rounds;
        await this.loadCandidates();
      }
    });
  }

  async filter() {
    await this.run(async () => {
      this.appliedSearch = this.search.trim();
      this.page = 0;
      await this.loadCandidates();
    });
  }

  async changePage(page: number) {
    if (page < 0 || page >= this.pages) return;
    await this.run(async () => {
      this.page = page;
      await this.loadCandidates();
    });
  }

  async openInvites() {
    await this.run(async () => {
      this.showInvite = true;
      this.poolPage = 0;
      const latest = Math.max(
        Date.now(),
        ...this.rounds.map(
          (r) => this.interviews.find((j) => j.id === r.INTERVIEW_ID)?.scheduledAt || 0
        )
      );
      const date = new Date(latest + 7 * 86400000);
      this.expiry = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
        .toISOString()
        .slice(0, 16);
      await this.loadPool();
    });
  }

  async loadPool() {
    const result = await this.api<any>(
      '/recruiter/pool?' +
        new URLSearchParams({
          page: String(this.poolPage),
          size: '10',
          search: this.poolSearch.trim(),
        })
    );
    this.pool = result.items;
    this.poolPage = result.page;
    this.poolPages = result.totalPages;
  }

  async findPool(page = 0) {
    if (page < 0 || page >= this.poolPages) return;
    await this.run(async () => {
      this.poolPage = page;
      await this.loadPool();
    });
  }

  toggle(id: string) {
    if (this.selected.has(id)) this.selected.delete(id);
    else if (this.selected.size < 200) this.selected.add(id);
    else this.error = 'Select at most 200 candidates.';
  }

  async invite() {
    if (!this.active || !this.selected.size) return;
    await this.run(async () => {
      const result = await this.api<any>('/recruiter/processes/' + this.active.ID + '/candidates', {
        candidateIds: [...this.selected],
        expiresAt: new Date(this.expiry).getTime(),
      });
      this.selected.clear();
      this.showInvite = false;
      await this.loadCandidates();
      this.notice =
        result.created +
        ' process invitations created; ' +
        result.alreadyInvited +
        ' existing candidates kept. Copy each process link for manual sharing; no email was sent.';
    });
  }

  beginSkip(candidate?: any) {
    this.skipTarget = candidate
      ? {
          id: candidate.ID,
          name: candidate.CANDIDATE_NAME,
          waive: this.aiState(candidate) === 'SUBMITTED',
        }
      : { name: 'all invited candidates, including other pages' };
    this.skipReason = '';
    this.includeFuture = false;
  }

  async skip() {
    if (!this.active || !this.skipTarget || !this.skipReason.trim()) return;
    await this.run(async () => {
      const base = '/recruiter/processes/' + this.active.ID;
      await this.api(
        this.skipTarget!.id
          ? base + '/candidates/' + this.skipTarget!.id + '/skip-ai'
          : base + '/skip-ai',
        {
          reason: this.skipReason,
          includeFuture: this.includeFuture,
        }
      );
      this.skipTarget = undefined;
      this.active = (await this.api<any>(base)).process;
      await this.loadCandidates();
      this.notice =
        'AI round skipped. Saved conversation and feedback were retained. Invite the next ready assessment when appropriate.';
    });
  }

  async policy() {
    await this.run(async () => {
      await this.api('/recruiter/processes/' + this.active.ID + '/ai-policy', {
        skipFuture: !this.active.SKIP_AI_DEFAULT,
      });
      this.active = (await this.api<any>('/recruiter/processes/' + this.active.ID)).process;
      this.notice = 'Policy updated for future invitations only. Existing skips are not reopened.';
    });
  }

  async action(candidate: any, action: string) {
    await this.run(async () => {
      await this.api(
        '/recruiter/processes/' + this.active.ID + '/candidates/' + candidate.ID + '/decision',
        { action }
      );
      await this.loadCandidates();
      this.notice =
        action === 'INVITE_NEXT'
          ? 'Next available round invited. The process link shows the new round.'
          : 'Process status updated. Already-issued normal assessment links are not revoked by this action.';
    });
  }
  pendingRoundInvite?: {
    candidate: any;
    processId: string;
    roundId: string;
    title: string;
    number: number;
    total: number;
  };
  roundInviteError = '';
  beginRoundInvite(candidate: any, dialog: HTMLDialogElement) {
    if (this.busy || !this.active || !this.canAdvance(candidate)) return;
    const round = this.currentRound(candidate);
    this.pendingRoundInvite = {
      candidate,
      processId: this.active.ID,
      roundId: round.ID,
      title: round.TITLE,
      number: this.stageNumber(candidate),
      total: candidate.steps.length,
    };
    this.roundInviteError = '';
    dialog.showModal();
  }
  cancelRoundInvite() {
    this.pendingRoundInvite = undefined;
    this.roundInviteError = '';
  }
  async confirmRoundInvite(dialog: HTMLDialogElement) {
    const pending = this.pendingRoundInvite;
    if (this.busy || !pending) return;
    if (
      this.active?.ID !== pending.processId ||
      !this.canAdvance(pending.candidate) ||
      this.currentRound(pending.candidate)?.ID !== pending.roundId
    ) {
      this.roundInviteError = 'The candidate stage changed. Cancel and refresh before inviting.';
      return;
    }
    await this.action(pending.candidate, 'INVITE_NEXT');
    if (this.error) {
      this.roundInviteError = this.error;
      return;
    }
    this.cancelRoundInvite();
    dialog.close();
  }

  async copy(candidate: any) {
    await this.run(async () => {
      const result = await this.api<{ url: string }>(
        '/recruiter/processes/' + this.active.ID + '/candidates/' + candidate.ID + '/link'
      );
      this.copiedLinks[candidate.ID] = result.url;
      try {
        await navigator.clipboard.writeText(result.url);
        this.notice = 'Process link copied.';
      } catch {
        this.notice = 'Select and copy the process link shown below the candidate.';
      }
    });
  }

  canAdvance(candidate: any) {
    const step = candidate.steps.find(
      (s: any) => !['FEEDBACK_PUBLISHED', 'SKIPPED'].includes(s.STATE)
    );
    return (
      !candidate.ON_HOLD &&
      !candidate.CLOSED &&
      step?.KIND === 'ASSESSMENT' &&
      ['LOCKED', 'READY'].includes(step.STATE)
    );
  }

  hasAi(candidate?: any) {
    return candidate
      ? !candidate.CLOSED &&
          candidate.steps.some(
            (s: any) =>
              s.KIND === 'AI_PRELIMINARY' && !['SKIPPED', 'FEEDBACK_PUBLISHED'].includes(s.STATE)
          )
      : this.rounds.some((r) => r.KIND === 'AI_PRELIMINARY');
  }

  label(state: string) {
    return (
      (
        {
          LOCKED: 'Waiting for previous round',
          READY: 'Ready',
          INVITED: 'Invited',
          IN_PROGRESS: 'In progress',
          SUBMITTED: 'Awaiting human review',
          FEEDBACK_PUBLISHED: 'Feedback published',
          SKIPPED: 'Skipped by recruiter',
        } as Record<string, string>
      )[state] || state
    );
  }
}
