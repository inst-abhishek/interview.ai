const { test } = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/app/candidate-pool.component.ts'), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
      target: ts.ScriptTarget.ES2022,
    },
  }
).outputText;
function setup(fetch) {
  const events = [],
    context = {
      exports: {},
      require: () => ({
        Component: () => (x) => x,
        Input: () => () => {},
        Output: () => () => {},
        EventEmitter: class {
          emit(value) {
            events.push(value);
          }
        },
      }),
      fetch,
      URLSearchParams,
      Date,
      Set,
      Map,
      console,
      crypto: { randomUUID: () => 'request-12345678' },
      navigator: { clipboard: { writeText: async () => {} } },
    };
  vm.runInNewContext(source, context);
  return { app: new context.exports.CandidatePoolComponent(), context, events };
}
const ok = (data) => ({ ok: true, json: async () => data });
test('selection is retained across pages and changing selection invalidates invitation preview', async () => {
  const { app } = setup(async () =>
    ok({ items: [{ id: 'second' }], page: 1, size: 10, total: 11, totalPages: 2 })
  );
  app.pages = 2;
  app.toggle({ id: 'first' });
  await app.changePage(1);
  assert.equal(app.selected.has('first'), true);
  app.preview = { ready: 1 };
  app.requestKey = 'old-request';
  app.toggle({ id: 'second' });
  assert.equal(app.selected.size, 2);
  assert.equal(app.preview, undefined);
  assert.equal(app.requestKey, '');
});
test('CSV import failure retains preview and file for retry', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 503,
    json: async () => ({ message: 'Server unavailable' }),
  }));
  app.csv = 'name,email\nOne,one@example.com';
  app.importPreview = { ready: 1, rows: [], invalid: 0, duplicates: 0 };
  await app.importCsv();
  assert.match(app.error, /unavailable/);
  assert.equal(app.importPreview.ready, 1);
  assert.match(app.csv, /one@example.com/);
});
test('a lost bulk response preserves the same request key for safe retry', async () => {
  const inputs = [];
  let calls = 0;
  const { app } = setup(async (url, options) => {
    if (url.endsWith('/bulk-invitations')) {
      inputs.push(JSON.parse(options.body));
      if (++calls === 1) throw Error('network disconnected');
      return ok({ id: 'batch', items: [], created: 1, skipped: 0 });
    }
    return ok([]);
  });
  app.interviewId = 'job';
  app.expiresAt = '2027-01-01T12:00';
  app.selected.add('one');
  app.preview = { ready: 1 };
  app.requestKey = 'stable-request-123';
  await app.generate();
  assert.equal(app.requestKey, 'stable-request-123');
  assert.equal(app.selected.size, 1);
  assert.ok(app.preview);
  await app.generate();
  assert.equal(inputs.length, 2);
  assert.equal(inputs[0].requestKey, inputs[1].requestKey);
  assert.equal(app.selected.size, 0);
  assert.equal(app.batch.id, 'batch');
});
test('new preview uses validated recipients and assigns a new request key', async () => {
  const { app } = setup(async () =>
    ok({ interviewId: 'job', ready: 1, alreadyInvited: 1, recipients: [] })
  );
  app.interviewId = 'job';
  app.expiresAt = '2027-01-01T12:00';
  app.selected.add('one');
  app.selected.add('two');
  await app.reviewInvitations();
  assert.equal(app.preview.ready, 1);
  assert.equal(app.preview.alreadyInvited, 1);
  assert.equal(app.requestKey, 'request-12345678');
});
test('failed paging restores the previous page and selected candidate set', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 503,
    json: async () => ({ message: 'Offline' }),
  }));
  app.pages = 2;
  app.selected.add('one');
  await app.changePage(1);
  assert.equal(app.page, 0);
  assert.equal(app.selected.has('one'), true);
  assert.match(app.error, /Offline/);
});
test('email remains unavailable for manual sharing and submitted or expired assessments cannot be sent', () => {
  const { app } = setup();
  const item = {
    assessmentState: 'INVITED',
    expiresAt: Date.now() + 60000,
    deliveryStatus: 'NOT_SENT',
  };
  assert.equal(app.canSend(item), false);
  app.emailAvailable = true;
  assert.equal(app.canSend(item), true);
  assert.equal(app.canSend({ ...item, assessmentState: 'SUBMITTED' }), false);
  assert.equal(app.canSend({ ...item, expiresAt: 0 }), false);
  assert.equal(app.canSend({ ...item, deliveryStatus: 'QUEUED' }), false);
});
test('candidate editing preserves an entered zero years of experience', () => {
  const { app } = setup();
  app.edit({ id: 'one', name: 'One', email: 'one@example.com', skills: 'Java', experience: 0 });
  assert.equal(app.experience, 0);
  assert.equal(app.editingId, 'one');
});

test('expired pool access clears candidate information and asks for staff sign-in', async () => {
  const { app, events } = setup(async () => ({
    ok: false,
    status: 401,
    json: async () => ({ message: 'Sign in required' }),
  }));
  app.candidates = [{ id: 'one' }];
  app.selected.add('one');
  app.batch = { id: 'private-batch' };
  await app.run(() => app.load());
  assert.equal(app.candidates.length, 0);
  assert.equal(app.selected.size, 0);
  assert.equal(app.batch, undefined);
  assert.equal(events.length, 1);
});
test('successful import keeps its confirmation even if refreshing the pool fails', async () => {
  const { app } = setup(async (url, options) => {
    if (options.method === 'POST') return ok({ imported: 2, skipped: 1 });
    throw Error('Offline');
  });
  app.importPreview = { ready: 2, rows: [] };
  app.csv = 'file';
  await app.importCsv();
  assert.equal(app.importPreview, undefined);
  assert.match(app.notice, /2 candidates imported/);
  assert.match(app.notice, /Refresh/);
  assert.equal(app.error, '');
});
