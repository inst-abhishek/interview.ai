import { Component, Input, OnInit, OnDestroy, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
type Criterion = { id: string; label: string; maxPoints: number; guidance: string };
type Test = { id: string; input: string; expected: string };
type Item = {
  id: string;
  kind: string;
  text: string;
  choices: string[];
  correctAnswer: string;
  explanation: string;
  maxPoints: number;
  rubric: Criterion[];
  starterCode: string;
  referenceSolution: string;
  publicTests: Test[];
  hiddenTests: Test[];
};
type Definition = {
  version: string;
  title: string;
  description: string;
  durationMinutes: number;
  skills: string[];
  reviewerInstructions: string;
  items: Item[];
};
type Draft = {
  version: string;
  state: string;
  revision: number;
  definition: Definition;
  parentVersion: string | null;
  aiModel: string | null;
  aiGeneratedAt: number | null;
  feedback: string | null;
  validated: boolean;
  errors: string[];
};
@Component({
  selector: 'exl-template-author',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './template-author.component.html',
  styleUrls: ['./template-author.component.css'],
})
export class TemplateAuthorComponent implements OnInit, OnDestroy {
  @Input() token = '';
  @Output() authenticationRequired = new EventEmitter<void>();
  busy = false;
  error = '';
  notice = '';
  aiAvailable = false;
  model = '';
  codingAvailable = false;
  drafts: any[] = [];
  sources: any[] = [];
  approvers: any[] = [];
  draft?: Draft;
  dirty = false;
  questionIndex = 0;
  showAiSettings = false;
  showAiPopover = false;
  connectionIssue: '' | 'renew' | 'limit' | 'unavailable' = '';
  private settingsTrigger?: HTMLElement;
  provider = 'chatgpt';
  chatgptConnected = false;
  chatgptEmail = '';
  chatgptMessage = '';
  chatgptUrl = '';
  chatgptModels: { slug: string; displayName: string }[] = [];
  chatgptModel = '';
  connectionBusy = false;
  operation: '' | 'generate' | 'validate' = '';
  operationSeconds = 0;
  operationError = '';
  operationErrorFor: '' | 'generate' | 'validate' = '';
  private operationTimer?: ReturnType<typeof setInterval>;
  validationReport?: {
    questionId: string;
    passed: number;
    total: number;
    state: string;
    tests: { id: string; state: string; input: string; expected: string; output: string }[];
  };
  showCreate = false;
  version = '';
  title = '';
  parentVersion = '';
  skillsText = '';
  role = '';
  level = 'Mid-level';
  requirements = '';
  mcqCount = 4;
  writtenCount = 2;
  codingCount = 0;
  aiReplaceAcknowledged = false;
  approverId = '';
  preview = false;
  async ngOnInit() {
    await this.run(async () => {
      const status = await this.api<any>('/status');
      this.aiAvailable = status.aiAvailable;
      this.model = status.model;
      this.codingAvailable = status.codingAvailable;
      await this.reloadLists();
      await this.loadConnection();
    });
  }

  async loadConnection() {
    try {
      this.connectionIssue = '';
      const c = await this.api<any>('/chatgpt');
      this.chatgptConnected = c.connected;
      this.chatgptEmail = c.email;
      this.chatgptMessage = c.message || '';
      this.chatgptUrl = '';
      if (c.connected) {
        this.chatgptModels = await this.api<any[]>('/chatgpt/models');
        if (!this.chatgptModels.some((m) => m.slug === this.chatgptModel))
          this.chatgptModel = this.chatgptModels[0]?.slug || '';
      } else {
        this.chatgptModels = [];
        this.chatgptModel = '';
      }
    } catch (e) {
      this.chatgptMessage = e instanceof Error ? e.message : String(e);
      this.setConnectionIssue(this.chatgptMessage);
      this.chatgptModels = [];
      this.chatgptModel = '';
    }
  }
  async refreshConnection() {
    if (this.connectionBusy) return;
    this.connectionBusy = true;
    try {
      await this.loadConnection();
    } finally {
      this.connectionBusy = false;
    }
  }
  async connectChatGpt() {
    if (this.connectionBusy) return;
    this.connectionBusy = true;
    this.error = '';
    try {
      const result = await this.api<{ url: string }>('/chatgpt/connect', {});
      this.chatgptUrl = result.url;
      this.chatgptMessage =
        'Continue in the new tab, then return here and refresh connection status.';
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    } finally {
      this.connectionBusy = false;
    }
  }
  async disconnectChatGpt() {
    if (this.connectionBusy) return;
    this.connectionBusy = true;
    this.error = '';
    try {
      await this.api('/chatgpt/disconnect', {});
      await this.loadConnection();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    } finally {
      this.connectionBusy = false;
    }
  }
  setConnectionIssue(message: string) {
    if (/usage.limit|plan.limit|plan usage is unavailable/i.test(message))
      this.connectionIssue = 'limit';
    else if (/renew|reconnect|authorization|plan access|permissions/i.test(message))
      this.connectionIssue = 'renew';
    else if (/unavailable|could not be loaded|failed|connection ended|timed out/i.test(message))
      this.connectionIssue = 'unavailable';
  }
  get connectionLabel() {
    if (this.connectionBusy) return 'Checking AI connection';
    if (this.provider === 'api') return this.aiAvailable ? 'AI connected' : 'Configure AI';
    if (this.connectionIssue === 'renew') return 'Reconnect required';
    if (this.connectionIssue === 'limit') return 'ChatGPT usage limit reached';
    if (this.connectionIssue === 'unavailable') return 'AI temporarily unavailable';
    return this.chatgptConnected ? 'AI connected' : 'Connect AI';
  }
  get connectionReady() {
    return this.provider === 'api'
      ? this.aiAvailable
      : this.chatgptConnected && !this.connectionIssue && !!this.chatgptModel;
  }
  get selectedModelName() {
    return this.provider === 'api'
      ? this.model
      : this.chatgptModels.find((m) => m.slug === this.chatgptModel)?.displayName ||
          this.chatgptModel ||
          'No model selected';
  }
  get maskedEmail() {
    const [name, domain] = this.chatgptEmail.split('@');
    return domain ? name.slice(0, 4) + '...@' + domain : this.chatgptEmail;
  }
  openAiSettings(event?: Event) {
    this.showAiPopover = false;
    this.showAiSettings = true;
    if (typeof document !== 'undefined') {
      const target = event?.currentTarget as HTMLElement;
      this.settingsTrigger = (
        target?.closest('.ai-popover')
          ? document.getElementById('ai-status-button')
          : target || document.activeElement
      ) as HTMLElement;
      setTimeout(() => document.getElementById('ai-settings-close')?.focus(), 0);
    }
  }
  closeAiSettings() {
    this.showAiSettings = false;
    this.settingsTrigger?.focus();
  }
  settingsKeydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.closeAiSettings();
    } else if (event.key === 'Tab') {
      const dialog = event.currentTarget as HTMLElement;
      const items = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),summary'
        )
      ).filter((el) => el.getClientRects().length > 0);
      const first = items[0],
        last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
  }
  get runnerLabel() {
    if (!this.codingAvailable) return 'Unavailable ? coding execution is disabled';
    if (this.validationReport && this.validationReport.state !== 'COMPLETE')
      return 'Unavailable ? check Docker Desktop';
    return 'Configured ? availability checked during validation';
  }
  get generationAvailable() {
    return this.provider === 'chatgpt'
      ? this.chatgptConnected && !!this.chatgptModel
      : this.aiAvailable;
  }
  async api<T>(path: string, body?: unknown): Promise<T> {
    const r = await fetch('http://127.0.0.1:8080/api/author' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: 'Bearer ' + this.token, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!r.ok) {
      if (r.status === 401) {
        this.draft = undefined;
        this.drafts = [];
        this.authenticationRequired.emit();
      }
      let message = 'Request failed (' + r.status + ')';
      try {
        const body = await r.json();
        message = body.message || message;
        if (
          this.provider === 'chatgpt' &&
          (path.includes('/generate') || path.includes('/chatgpt'))
        )
          this.setConnectionIssue(message);
        if (body.validation) {
          this.validationReport = body.validation;
          const index =
            this.draft?.definition.items.findIndex((q) => q.id === body.validation.questionId) ??
            -1;
          if (index >= 0) this.questionIndex = index;
        }
      } catch {}
      throw Error(message);
    }
    return r.json();
  }
  async run(action: () => Promise<void>, operation: '' | 'generate' | 'validate' = '') {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.notice = '';
    this.operationError = '';
    this.operationErrorFor = '';
    this.operation = operation;
    this.operationSeconds = 0;
    if (operation) {
      const started = Date.now();
      this.operationTimer = setInterval(() => {
        this.operationSeconds = Math.floor((Date.now() - started) / 1000);
      }, 1000);
    }
    try {
      await action();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      if (operation) {
        this.operationError = this.error;
        this.operationErrorFor = operation;
      }
    } finally {
      this.stopOperation();
      this.busy = false;
    }
  }
  private stopOperation() {
    if (this.operationTimer !== undefined) clearInterval(this.operationTimer);
    this.operationTimer = undefined;
    this.operation = '';
  }
  ngOnDestroy() {
    this.stopOperation();
  }
  get elapsed() {
    return (
      Math.floor(this.operationSeconds / 60) +
      ':' +
      String(this.operationSeconds % 60).padStart(2, '0')
    );
  }
  testState(state: string) {
    return (
      (
        {
          PASSED: 'Passed',
          WRONG_ANSWER: 'Output did not match',
          COMPILATION_ERROR: 'Compilation error',
          RUNTIME_ERROR: 'Runtime error',
          TIME_LIMIT: 'Time limit exceeded',
          OUTPUT_LIMIT: 'Output limit exceeded',
        } as Record<string, string>
      )[state] || 'Execution unavailable'
    );
  }
  async reloadLists() {
    [this.drafts, this.approvers, this.sources] = await Promise.all([
      this.api<any[]>('/drafts'),
      this.api<any[]>('/approvers'),
      this.api<any[]>('/sources'),
    ]);
  }
  private apply(d: Draft) {
    this.validationReport = undefined;
    this.draft = d;
    this.skillsText = (d.definition.skills || []).join(', ');
    this.role = d.definition.title;
    this.questionIndex = 0;
    this.dirty = false;
    this.aiReplaceAcknowledged = false;
    this.approverId = '';
    this.preview = false;
  }
  async open(version: string) {
    if (this.dirty) {
      this.error = 'Save your changes before opening another draft.';
      return;
    }
    await this.run(async () => {
      this.apply(await this.api<Draft>('/drafts/' + version));
    });
  }
  async create() {
    await this.run(async () => {
      const d = await this.api<Draft>('/drafts', {
        version: this.version,
        title: this.title,
        parentVersion: this.parentVersion || null,
      });
      this.apply(d);
      this.showCreate = false;
      this.version = '';
      this.title = '';
      this.parentVersion = '';
      await this.reloadLists();
      this.notice = 'Draft created. Add questions or generate an AI draft.';
    });
  }
  newVersion() {
    if (!this.draft) return;
    this.showCreate = true;
    this.parentVersion = this.draft.version;
    this.title = this.draft.definition.title;
    this.version = '';
  }
  changed() {
    this.validationReport = undefined;
    this.operationError = '';
    this.dirty = true;
    if (this.draft) this.draft.validated = false;
    this.aiReplaceAcknowledged = false;
  }
  setSkills() {
    if (this.draft)
      this.draft.definition.skills = this.skillsText
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
    this.changed();
  }
  async save() {
    if (!this.draft) return;
    await this.run(async () => {
      const index = this.questionIndex;
      this.apply(
        await this.api<Draft>('/drafts/' + this.draft!.version, {
          revision: this.draft!.revision,
          definition: this.draft!.definition,
        })
      );
      this.questionIndex = Math.max(0, Math.min(index, this.draft!.definition.items.length - 1));
      await this.reloadLists();
      this.notice = 'Draft saved. Validate again after making changes.';
    });
  }
  async generate() {
    if (!this.draft || this.dirty || !this.aiReplaceAcknowledged) return;
    await this.run(async () => {
      const d = await this.api<Draft>('/drafts/' + this.draft!.version + '/generate', {
        revision: this.draft!.revision,
        provider: this.provider,
        model: this.provider === 'chatgpt' ? this.chatgptModel : undefined,
        brief: {
          role: this.role,
          level: this.level,
          skills: this.draft!.definition.skills,
          durationMinutes: this.draft!.definition.durationMinutes,
          mcqCount: this.mcqCount,
          writtenCount: this.writtenCount,
          codingCount: this.codingCount,
          requirements: this.requirements,
        },
      });
      this.apply(d);
      await this.reloadLists();
      this.notice =
        'AI draft generated and saved. Review every question, answer key, test and rubric before validation.';
    }, 'generate');
  }
  async validate() {
    if (!this.draft || this.dirty) return;
    this.validationReport = undefined;
    await this.run(async () => {
      const index = this.questionIndex;
      this.apply(
        await this.api<Draft>('/drafts/' + this.draft!.version + '/validate', {
          revision: this.draft!.revision,
        })
      );
      this.questionIndex = index;
      this.notice =
        'Saved draft validated. Java reference tests passed where required. Technical approval is still required.';
    }, 'validate');
  }
  async submit() {
    if (!this.draft || !this.approverId || this.dirty || !this.draft.validated) return;
    await this.run(async () => {
      this.apply(
        await this.api<Draft>('/drafts/' + this.draft!.version + '/submit', {
          revision: this.draft!.revision,
          approverId: this.approverId,
        })
      );
      await this.reloadLists();
      this.notice =
        'Version submitted to the selected approver. Recruiters can use it only after approval.';
    });
  }
  add(kind: string) {
    if (!this.draft) return;
    const id = 'q-' + crypto.randomUUID().slice(0, 8);
    this.draft.definition.items.push({
      id,
      kind,
      text: '',
      choices: kind === 'MCQ' ? ['Choice 1', 'Choice 2'] : [],
      correctAnswer: kind === 'MCQ' ? 'Choice 1' : '',
      explanation: '',
      maxPoints: kind === 'MCQ' ? 10 : kind === 'CODING' ? 40 : 20,
      rubric:
        kind === 'MCQ'
          ? []
          : [
              {
                id: 'criterion-1',
                label: kind === 'CODING' ? 'Code quality' : 'Technical correctness',
                maxPoints: kind === 'CODING' ? 10 : 20,
                guidance: '',
              },
            ],
      starterCode:
        kind === 'CODING'
          ? 'public class Main {\n  public static void main(String[] args) {\n    // Read stdin and print your result.\n  }\n}'
          : '',
      referenceSolution: '',
      publicTests: kind === 'CODING' ? [{ id: 'public-1', input: '', expected: '' }] : [],
      hiddenTests: kind === 'CODING' ? [{ id: 'hidden-1', input: '', expected: '' }] : [],
    });
    this.questionIndex = this.draft.definition.items.length - 1;
    this.changed();
  }
  removeQuestion() {
    if (!this.draft) return;
    this.draft.definition.items.splice(this.questionIndex, 1);
    this.questionIndex = Math.max(0, this.questionIndex - 1);
    this.changed();
  }
  choices(q: Item, text: string) {
    q.choices = text
      .split('\n')
      .filter((c) => c.trim())
      .map((c) => c.trim());
    this.changed();
  }
  criterion(q: Item) {
    q.rubric.push({
      id: 'criterion-' + crypto.randomUUID().slice(0, 8),
      label: '',
      maxPoints: 5,
      guidance: '',
    });
    this.changed();
  }
  test(q: Item, hidden: boolean) {
    (hidden ? q.hiddenTests : q.publicTests).push({
      id: (hidden ? 'hidden-' : 'public-') + crypto.randomUUID().slice(0, 8),
      input: '',
      expected: '',
    });
    this.changed();
  }
  get current() {
    return this.draft?.definition.items[this.questionIndex];
  }
  get locked() {
    return this.draft?.state !== 'DRAFT';
  }
  get total() {
    return this.draft?.definition.items.reduce((sum, q) => sum + Number(q.maxPoints || 0), 0) || 0;
  }
  get codingPresent() {
    return !!this.draft?.definition.items.some((q) => q.kind === 'CODING');
  }
  label(kind: string) {
    return (
      (
        { MCQ: 'MCQ', TECHNICAL: 'Written response', CODING: 'Java coding' } as Record<
          string,
          string
        >
      )[kind] || kind
    );
  }
}
