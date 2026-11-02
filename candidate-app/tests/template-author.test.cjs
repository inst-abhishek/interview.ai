const { test } = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/app/template-author.component.ts'), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
      target: ts.ScriptTarget.ES2022,
    },
  }
).outputText;
function setup(fetch) {
  const context = {
    exports: {},
    require: () => ({
      Component: () => (x) => x,
      Input: () => () => {},
      Output: () => () => {},
      EventEmitter: class {
        emit() {}
      },
    }),
    fetch,
    Date,
    setInterval,
    clearInterval,
    crypto: { randomUUID: () => 'example-question-id' },
    console,
  };
  vm.runInNewContext(source, context);
  return new context.exports.TemplateAuthorComponent();
}
const d = () => ({
  version: 'test-v1',
  state: 'DRAFT',
  revision: 1,
  validated: true,
  definition: { title: 'Java', skills: ['Java'], items: [], durationMinutes: 60 },
  errors: [],
});
test('editing invalidates saved validation and blocks submission', async () => {
  let calls = 0;
  const app = setup(async () => {
    calls++;
  });
  app.draft = d();
  app.approverId = 'other';
  app.changed();
  await app.submit();
  assert.equal(app.draft.validated, false);
  assert.equal(app.dirty, true);
  assert.equal(calls, 0);
});
test('AI generation failure preserves the current draft', async () => {
  const app = setup(async () => ({
    ok: false,
    status: 502,
    json: async () => ({ message: 'AI unavailable' }),
  }));
  app.draft = d();
  app.aiReplaceAcknowledged = true;
  await app.generate();
  assert.equal(app.draft.version, 'test-v1');
  assert.equal(app.draft.definition.title, 'Java');
  assert.match(app.error, /AI unavailable/);
});
test('AI generation requires a saved draft and explicit replacement acknowledgement', async () => {
  let calls = 0;
  const app = setup(async () => {
    calls++;
  });
  app.draft = d();
  await app.generate();
  app.aiReplaceAcknowledged = true;
  app.dirty = true;
  await app.generate();
  assert.equal(calls, 0);
});
test('manual coding question creates editable public and hidden tests without reasoning field', () => {
  const app = setup();
  app.draft = d();
  app.add('CODING');
  assert.equal(app.current.kind, 'CODING');
  assert.equal(app.current.publicTests.length, 1);
  assert.equal(app.current.hiddenTests.length, 1);
  assert.equal(app.dirty, true);
  assert.equal(app.draft.validated, false);
});
test('unsaved author changes prevent opening another draft', async () => {
  let calls = 0;
  const app = setup(async () => {
    calls++;
  });
  app.draft = d();
  app.dirty = true;
  await app.open('other-v1');
  assert.equal(calls, 0);
  assert.match(app.error, /Save your changes/);
});

test('ChatGPT subscription is the default and paid API use is explicit', () => {
  const app = setup();
  assert.equal(app.provider, 'chatgpt');
  app.aiAvailable = true;
  assert.equal(app.generationAvailable, false);
  app.chatgptConnected = true;
  app.chatgptModel = 'plan-model';
  assert.equal(app.generationAvailable, true);
  app.provider = 'api';
  assert.equal(app.generationAvailable, true);
});
test('subscription status loads account-specific model choices and clears stale choices on disconnect', async () => {
  let connected = true;
  const app = setup(async (url) => ({
    ok: true,
    json: async () =>
      url.endsWith('/models')
        ? [{ slug: 'plan-model', displayName: 'Plan model' }]
        : { connected, email: 'author@example.test', message: '' },
  }));
  app.chatgptModel = 'stale-model';
  await app.loadConnection();
  assert.equal(app.chatgptModel, 'plan-model');
  assert.equal(app.chatgptEmail, 'author@example.test');
  connected = false;
  await app.loadConnection();
  assert.equal(app.chatgptModels.length, 0);
  assert.equal(app.chatgptModel, '');
  assert.equal(app.generationAvailable, false);
});
test('generation sends the selected subscription provider and never retries with an API key', async () => {
  let calls = [];
  const app = setup(async (url, request) => {
    calls.push(JSON.parse(request.body));
    return { ok: false, status: 429, json: async () => ({ message: 'ChatGPT plan limit' }) };
  });
  app.draft = d();
  app.aiReplaceAcknowledged = true;
  app.chatgptConnected = true;
  app.chatgptModel = 'plan-model';
  await app.generate();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].provider, 'chatgpt');
  assert.equal(calls[0].model, 'plan-model');
  assert.equal(app.draft.version, 'test-v1');
  assert.match(app.error, /ChatGPT plan limit/);
});

test('generation progress stays visible until completion and clears its timer on success or failure', async () => {
  const app = setup();
  let finish;
  const task = app.run(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
    'generate'
  );
  assert.equal(app.operation, 'generate');
  assert.equal(app.busy, true);
  assert.notEqual(app.operationTimer, undefined);
  finish();
  await task;
  assert.equal(app.operation, '');
  assert.equal(app.operationTimer, undefined);
  assert.equal(app.busy, false);
  await app.run(async () => {
    throw Error('Provider unavailable');
  }, 'generate');
  assert.equal(app.operationTimer, undefined);
  assert.match(app.operationError, /Provider unavailable/);
});
test('reference validation failure shows test diagnostics and selects the affected question', async () => {
  const report = {
    questionId: 'coding-q',
    passed: 1,
    total: 2,
    state: 'COMPLETE',
    tests: [{ id: 'hidden-1', state: 'WRONG_ANSWER', input: '1 2', expected: '3', output: '4' }],
  };
  const app = setup(async () => ({
    ok: false,
    status: 400,
    json: async () => ({ message: 'Reference code passed 1 of 2 tests', validation: report }),
  }));
  app.draft = d();
  app.draft.definition.items = [{ id: 'mcq-q' }, { id: 'coding-q' }];
  await app.validate();
  assert.equal(app.questionIndex, 1);
  assert.equal(app.validationReport.passed, 1);
  assert.equal(app.validationReport.tests[0].output, '4');
  assert.equal(app.operationTimer, undefined);
  assert.match(app.error, /1 of 2/);
});

test('compact AI status distinguishes connected account, billing source and recoverable failures', () => {
  const app = setup();
  assert.equal(app.connectionLabel, 'Connect AI');
  app.chatgptConnected = true;
  app.chatgptEmail = 'example@gmail.com';
  app.chatgptModel = 'model';
  app.chatgptModels = [{ slug: 'model', displayName: 'Display model' }];
  assert.equal(app.connectionLabel, 'AI connected');
  assert.equal(app.selectedModelName, 'Display model');
  assert.equal(app.maskedEmail, 'exam...@gmail.com');
  app.setConnectionIssue('ChatGPT connection needs renewal. Reconnect your subscription');
  assert.equal(app.connectionLabel, 'Reconnect required');
  assert.equal(app.connectionReady, false);
  app.setConnectionIssue('Your ChatGPT plan usage limit was reached');
  assert.equal(app.connectionLabel, 'ChatGPT usage limit reached');
  app.provider = 'api';
  app.aiAvailable = true;
  assert.equal(app.connectionLabel, 'AI connected');
});
test('AI settings can open without modifying an unsaved assessment and Java status stays separate', () => {
  const app = setup();
  app.draft = d();
  app.dirty = true;
  app.openAiSettings();
  assert.equal(app.showAiSettings, true);
  assert.equal(app.dirty, true);
  app.closeAiSettings();
  assert.equal(app.showAiSettings, false);
  app.chatgptConnected = true;
  app.codingAvailable = false;
  assert.match(app.runnerLabel, /Unavailable/);
  app.codingAvailable = true;
  assert.match(app.runnerLabel, /Configured/);
  assert.doesNotMatch(app.runnerLabel, /Ready/);
});
