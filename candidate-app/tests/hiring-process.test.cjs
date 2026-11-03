const { test } = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function setup(file, className, fetch) {
  const events = [];
  const source = ts.transpileModule(
    fs.readFileSync(path.join(__dirname, '../src/app', file), 'utf8'),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        experimentalDecorators: true,
        target: ts.ScriptTarget.ES2022,
      },
    }
  ).outputText;
  const context = {
    exports: {},
    require: () => ({
      Component: () => (value) => value,
      Input: () => () => {},
      Output: () => () => {},
      EventEmitter: class {
        emit(value) {
          events.push(value);
        }
      },
    }),
    fetch,
    Date,
    URLSearchParams,
    URL,
    setInterval: () => 1,
    clearInterval() {},
    crypto: { randomUUID: () => 'stable-request-1234' },
    navigator: { clipboard: { writeText: async () => {} } },
    console,
  };
  vm.runInNewContext(source, context);
  return { app: new context.exports[className](), events, context };
}
const ok = (value) => ({ ok: true, json: async () => value });

test('recruiter can enable AI without an approved plan selection', async () => {
  let submitted;
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  app.aiEnabled = true;
  app.aiFocus = 'Java and React';
  app.api = async (url, body) => {
    if (body) {
      submitted = body;
      return { process: { ID: 'process' }, rounds: [{ KIND: 'AI_PRELIMINARY' }] };
    }
    return [];
  };
  app.loadCandidates = async () => {};
  await app.create();
  assert.equal(submitted.aiEnabled, true);
  assert.equal(submitted.aiFocus, 'Java and React');
  assert.equal(app.error, '');
});

test('an older backend cannot silently omit a requested AI round', async () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  app.aiEnabled = true;
  app.api = async () => ({ process: { ID: 'old-process' }, rounds: [{ KIND: 'ASSESSMENT' }] });
  await app.create();
  assert.match(app.error, /Restart the backend/);
  assert.equal(app.active, undefined);
});

test('AI-enabled copy preserves assessment selection without changing existing candidates', () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  app.active = { ID: 'original', TITLE: 'Java hiring' };
  app.rounds = [{ KIND: 'ASSESSMENT', INTERVIEW_ID: 'assessment' }];
  app.prepareAiCopy();
  assert.equal(app.aiEnabled, true);
  assert.equal(app.showCreate, true);
  assert.equal(app.assessmentIds[0], 'assessment');
  assert.equal(app.active.ID, 'original');
});

test('candidate invitation links are fetched only when sharing is requested', async () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent', async (url) =>
    url.endsWith('/link')
      ? ok({ url: 'http://127.0.0.1:4200/#process=private-token' })
      : ok({ items: [{ ID: 'candidate' }], page: 0, pages: 1, total: 1 })
  );
  app.active = { ID: 'process' };
  await app.loadCandidates();
  assert.equal(app.copiedLinks.candidate, undefined);
  await app.share({ ID: 'candidate' });
  assert.equal(app.copiedLinks.candidate, 'http://127.0.0.1:4200/#process=private-token');
});

test('a failed link lookup keeps candidate progress visible and can be retried', async () => {
  let fail = true;
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent', async () => {
    if (fail) throw Error('offline');
    return ok({ url: 'http://127.0.0.1:4200/#process=private-token' });
  });
  app.active = { ID: 'process' };
  await app.showLink({ ID: 'candidate' });
  assert.match(app.linkErrors.candidate, /retry/);
  fail = false;
  await app.showLink({ ID: 'candidate' });
  assert.equal(app.linkErrors.candidate, undefined);
  assert.ok(app.copiedLinks.candidate);
});
const view = (role = 'INTERVIEWER', state = 'IN_PROGRESS', revision = 1) => ({
  title: 'Java process',
  name: 'Candidate',
  onHold: false,
  closed: false,
  aiAvailable: true,
  serverTime: Date.now(),
  rounds: [
    {
      id: 'ai-round',
      title: 'Preliminary',
      kind: 'AI_PRELIMINARY',
      state,
      revision,
      deadline: Date.now() + 100000,
      draft: '',
      draftRevision: 0,
      transcript: [
        {
          id: 'question-1',
          role,
          text: 'Technical exchange',
          topic: 0,
          followUp: false,
          at: Date.now(),
        },
      ],
    },
  ],
});

test('progression is enabled only after completed feedback or a recruiter skip', () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  const c = {
    ON_HOLD: false,
    CLOSED: false,
    steps: [
      { KIND: 'AI_PRELIMINARY', STATE: 'SUBMITTED' },
      { KIND: 'ASSESSMENT', STATE: 'LOCKED' },
    ],
  };
  assert.equal(app.canAdvance(c), false);
  c.steps[0].STATE = 'FEEDBACK_PUBLISHED';
  assert.equal(app.canAdvance(c), true);
  c.steps[0].STATE = 'SKIPPED';
  assert.equal(app.canAdvance(c), true);
  c.ON_HOLD = true;
  assert.equal(app.canAdvance(c), false);
});

test('bulk skip uses explicit scope and future policy rather than the displayed candidate page', async () => {
  const calls = [];
  const { app } = setup(
    'hiring-process.component.ts',
    'HiringProcessComponent',
    async (url, request) => {
      if (request.method === 'POST') calls.push({ url, body: JSON.parse(request.body) });
      return ok(
        url.includes('/candidates?')
          ? { items: [], page: 0, pages: 1, total: 0 }
          : { process: { ID: 'process-one' } }
      );
    }
  );
  app.active = { ID: 'process-one' };
  app.beginSkip();
  assert.equal(app.includeFuture, false);
  app.skipReason = 'Use normal assessments';
  app.includeFuture = true;
  await app.skip();
  assert.equal(calls[0].url.endsWith('/process-one/skip-ai'), true);
  assert.equal(calls[0].body.includeFuture, true);
  assert.equal(app.skipTarget, undefined);
});

test('candidate answer remains saved when the follow-up provider fails', async () => {
  const calls = [];
  const saved = view('CANDIDATE', 'IN_PROGRESS', 2);
  const { app } = setup(
    'process-candidate.component.ts',
    'ProcessCandidateComponent',
    async (url, request) => {
      calls.push({ url, body: request.body && JSON.parse(request.body) });
      if (url.endsWith('/answer')) return ok(saved);
      return {
        ok: false,
        status: 429,
        json: async () => ({ message: 'AI unavailable. Your answer is saved.' }),
      };
    }
  );
  app.journey = view();
  app.code = 'private-process-code';
  app.answer = 'Use timeouts and bounded retries.';
  app.dirty = true;
  await app.send();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].body.requestId, 'stable-request-1234');
  assert.equal(app.lastTurn.role, 'CANDIDATE');
  assert.equal(app.status, 'Answer saved to server');
  assert.match(app.error, /answer is saved/);
  assert.equal(app.busy, false);
});

test('server skip makes the candidate editor inactive and stale polls cannot reopen it', () => {
  const { app } = setup('process-candidate.component.ts', 'ProcessCandidateComponent');
  app.receive(view('INTERVIEWER', 'IN_PROGRESS', 1));
  app.receive(view('INTERVIEWER', 'SKIPPED', 2));
  assert.equal(app.answering, false);
  app.receive(view('INTERVIEWER', 'IN_PROGRESS', 1));
  assert.equal(app.aiRound.state, 'SKIPPED');
});

test('candidate must consent before starting and an unsaved draft is saved before early finish', async () => {
  const calls = [];
  const { app } = setup(
    'process-candidate.component.ts',
    'ProcessCandidateComponent',
    async (url) => {
      calls.push(url);
      return ok(url.endsWith('/draft') ? { revision: 1 } : view('INTERVIEWER', 'SUBMITTED', 2));
    }
  );
  app.journey = view();
  await app.start();
  assert.equal(calls.length, 0);
  app.endConfirmed = true;
  app.answer = 'Saved unfinished draft';
  app.dirty = true;
  await app.finish();
  assert.equal(calls[0].endsWith('/draft'), true);
  assert.equal(calls[1].endsWith('/finish'), true);
  assert.equal(app.aiRound.state, 'SUBMITTED');
});

test('review publication requires human marks and a recommendation and preserves unsaved edits', async () => {
  let calls = 0;
  const { app } = setup('ai-round-review.component.ts', 'AiRoundReviewComponent', async () => {
    calls++;
  });
  app.detail = { attempt: { ID: 'one', STATE: 'SUBMITTED' } };
  app.marks = [{ questionId: 'q1', criterionId: 'c1', points: null }];
  await app.save(true);
  assert.equal(calls, 0);
  app.dirty = true;
  await app.open('other');
  assert.equal(calls, 0);
  assert.match(app.error, /Save your draft/);
});

test('candidate demo manager uses invitation access directly without staff login', async () => {
  const calls = [];
  const { app } = setup(
    'process-candidate.component.ts',
    'ProcessCandidateComponent',
    async (url, options) => {
      calls.push({ url, headers: options.headers });
      if (url.endsWith('/models')) return ok([{ slug: 'demo-model', displayName: 'Demo' }]);
      return ok({ connected: true, email: 'demo@example.com', enabled: false });
    }
  );
  app.code = 'invitation';
  app.journey = { ...view(), connectionManagerEnabled: true };
  app.refresh = async () => {};
  await app.manage('refresh');
  assert.equal(app.managerConnected, true);
  assert.equal(app.managerDemoEnabled, true);
  assert.ok(calls.some((c) => c.url.endsWith('/connection/select')));
  assert.ok(calls.every((c) => !c.url.includes('/staff/') && !c.url.includes('/author/')));
  assert.ok(calls.every((c) => c.headers['X-Process-Code'] === 'invitation'));
});
test('candidate connection manager is disabled outside demo mode', async () => {
  let called = false;
  const { app } = setup('process-candidate.component.ts', 'ProcessCandidateComponent', async () => {
    called = true;
    return ok({});
  });
  app.journey = { ...view(), connectionManagerEnabled: false };
  await app.manage('connect');
  assert.equal(called, false);
  assert.match(app.managerError, /disabled/);
});

test('ChatGPT continue opens a new tab directly and isolates its opener', () => {
  const { app, context } = setup('process-candidate.component.ts', 'ProcessCandidateComponent');
  const popup = { opener: {}, location: { href: '' } };
  context.window = { open: () => popup };
  app.managerUrl = 'http://127.0.0.1:8080/api/process-candidate/connection/authorize?ticket=test';
  app.continueWithChatGpt();
  assert.equal(popup.opener, null);
  assert.equal(popup.location.href, app.managerUrl);
  assert.equal(app.managerPopupBlocked, false);
});
test('blocked ChatGPT popup shows a fallback instead of silently doing nothing', () => {
  const { app, context } = setup('process-candidate.component.ts', 'ProcessCandidateComponent');
  context.window = { open: () => null };
  app.managerUrl = 'http://127.0.0.1:8080/api/process-candidate/connection/authorize?ticket=test';
  app.continueWithChatGpt();
  assert.equal(app.managerPopupBlocked, true);
  assert.match(app.managerError, /blocked/);
});

test('outside-click dismissal does not cancel checkbox or link default actions', () => {
  const { app } = setup('process-candidate.component.ts', 'ProcessCandidateComponent');
  app.connectionPanelOpen = true;
  assert.equal(app.closeConnectionPopover(), undefined);
  assert.equal(app.connectionPanelOpen, false);
  const html = fs.readFileSync(
    path.join(__dirname, '../src/app/process-candidate.component.html'),
    'utf8'
  );
  assert.match(html, /\(document:click\)="closeConnectionPopover\(\)"/);
});

test('published AI rounds cannot show skip and unlock the next invitation label', () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  const next = { KIND: 'ASSESSMENT', STATE: 'LOCKED' };
  const candidate = { steps: [{ KIND: 'AI_PRELIMINARY', STATE: 'FEEDBACK_PUBLISHED' }, next] };
  assert.equal(app.hasAi(candidate), false);
  assert.equal(app.stepLabel(candidate, next), 'Ready for invitation');
  candidate.steps[0].STATE = 'SUBMITTED';
  assert.equal(app.hasAi(candidate), true);
  app.beginSkip(candidate);
  assert.equal(app.skipTarget.waive, true);
  assert.equal(app.stepLabel(candidate, next), 'Waiting for previous round');
});

test('add and invite keeps missing experience null and forwards decimal years', async () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  let payload;
  app.active = { ID: 'process' };
  app.newName = 'New candidate';
  app.newEmail = 'new@example.com';
  app.expiry = '2030-01-01T12:00';
  app.api = async (path, body) => {
    payload = body;
    return { created: 1 };
  };
  app.loadCandidates = async () => {};
  await app.addInvite();
  assert.equal(payload.experience, null);
  assert.equal(payload.skills, '');
  app.newName = 'Other';
  app.newEmail = 'other@example.com';
  app.newExperience = 2.5;
  await app.addInvite();
  assert.equal(payload.experience, 2.5);
});

test('stage recommendation never carries an earlier round result into an unreviewed assessment', () => {
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent');
  const ai = { KIND: 'AI_PRELIMINARY', STATE: 'FEEDBACK_PUBLISHED', RECOMMENDATION: 'SHORTLIST' };
  const assessment = { KIND: 'ASSESSMENT', STATE: 'LOCKED' };
  const candidate = { steps: [ai, assessment] };
  assert.equal(app.currentRound(candidate), assessment);
  assert.equal(app.stageNumber(candidate), 2);
  assert.equal(app.stageRecommendation(candidate), undefined);
  assert.equal(app.canAdvance(candidate), true);
  assessment.STATE = 'INVITED';
  assert.equal(app.canAdvance(candidate), false);
  assert.equal(app.stageRecommendation(candidate), undefined);
  assessment.STATE = 'FEEDBACK_PUBLISHED';
  assessment.RECOMMENDATION = 'HOLD';
  assert.equal(app.stageRecommendation(candidate), 'HOLD');
  assert.equal(ai.RECOMMENDATION, 'SHORTLIST');
});

test('AI connection controls belong only to an active preliminary round', () => {
  const { app } = setup('process-candidate.component.ts', 'ProcessCandidateComponent');
  app.journey = view('INTERVIEWER', 'READY');
  assert.equal(app.aiConnectionVisible, true);
  app.journey.rounds[0].state = 'IN_PROGRESS';
  assert.equal(app.aiConnectionVisible, true);
  for (const state of ['SUBMITTED', 'FEEDBACK_PUBLISHED', 'SKIPPED']) {
    app.journey.rounds[0].state = state;
    assert.equal(app.aiConnectionVisible, false);
  }
  assert.equal(app.label('FEEDBACK_PUBLISHED'), 'Review complete');
});

test('round invitation requires confirmation and cancellation makes no request', async () => {
  let calls = 0,
    opened = 0,
    closed = 0;
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent', async () => {
    calls++;
    return ok({});
  });
  app.active = { ID: 'process' };
  const c = {
    ID: 'candidate',
    CANDIDATE_NAME: 'Candidate',
    steps: [{ ID: 'round', TITLE: 'Java Fullstack', KIND: 'ASSESSMENT', STATE: 'READY' }],
  };
  const dialog = { showModal: () => opened++, close: () => closed++ };
  app.beginRoundInvite(c, dialog);
  assert.equal(opened, 1);
  assert.equal(calls, 0);
  app.cancelRoundInvite();
  await app.confirmRoundInvite(dialog);
  assert.equal(calls, 0);
  app.beginRoundInvite(c, dialog);
  app.loadCandidates = async () => {};
  await app.confirmRoundInvite(dialog);
  assert.equal(calls, 1);
  assert.equal(closed, 1);
  assert.equal(app.pendingRoundInvite, undefined);
  await app.confirmRoundInvite(dialog);
  assert.equal(calls, 1);
});
test('confirmation refuses to invite a stage after the selected process changes', async () => {
  let calls = 0;
  const { app } = setup('hiring-process.component.ts', 'HiringProcessComponent', async () => {
    calls++;
    return ok({});
  });
  app.active = { ID: 'process' };
  const c = {
    ID: 'candidate',
    steps: [{ ID: 'round', TITLE: 'Java', KIND: 'ASSESSMENT', STATE: 'READY' }],
  };
  const dialog = { showModal() {}, close() {} };
  app.beginRoundInvite(c, dialog);
  app.active = { ID: 'other' };
  await app.confirmRoundInvite(dialog);
  assert.equal(calls, 0);
  assert.match(app.roundInviteError, /stage changed/);
});
