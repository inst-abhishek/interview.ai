const { test } = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = ts.transpileModule(
  fs.readFileSync(path.join(__dirname, '../src/app/staff.component.ts'), 'utf8'),
  {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
      target: ts.ScriptTarget.ES2022,
    },
  }
).outputText;
function setup(fetch) {
  const values = new Map(),
    events = [];
  const context = {
    exports: {},
    require: () => ({
      Component: () => (x) => x,
      Output: () => () => {},
      EventEmitter: class {
        emit(value) {
          events.push(value);
        }
      },
    }),
    sessionStorage: {
      getItem: (k) => values.get(k) || null,
      setItem: (k, v) => values.set(k, v),
      removeItem: (k) => values.delete(k),
    },
    fetch,
    Date,
    console,
  };
  vm.runInNewContext(source, context);
  return { app: new context.exports.StaffComponent(), values, events };
}
const ok = (data) => ({ ok: true, json: async () => data });
test('named reviewer signs into reviewer workspace and clears credentials', async () => {
  const { app, events } = setup(async () =>
    ok({ token: 'named', account: { id: 'r', role: 'REVIEWER' } })
  );
  app.email = 'r@example.com';
  app.password = 'private-password';
  await app.login();
  assert.equal(events[0].token, 'named');
  assert.equal(app.password, '');
  assert.equal(app.token, '');
});
test('password reset failure keeps token and password available for correction', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 400,
    json: async () => ({ message: 'Reset token invalid or expired' }),
  }));
  app.mode = 'reset';
  app.resetToken = 'expired';
  app.newPassword = 'new-password-123';
  await app.login();
  assert.equal(app.mode, 'reset');
  assert.equal(app.resetToken, 'expired');
  assert.match(app.error, /expired/);
  assert.equal(app.notice, '');
});
test('successful reset clears secrets and returns to sign in', async () => {
  const { app } = setup(async () => ok({ success: true }));
  app.mode = 'reset';
  app.resetToken = 'once';
  app.newPassword = 'new-password-123';
  await app.login();
  assert.equal(app.mode, 'login');
  assert.equal(app.resetToken, '');
  assert.equal(app.newPassword, '');
  assert.match(app.notice, /Password updated/);
});
test('expired administrator session clears locally stored access', async () => {
  const { app, values } = setup(async () => ({
    ok: false,
    status: 401,
    json: async () => ({ message: 'Staff sign in expired' }),
  }));
  app.token = 'expired';
  app.account = { id: 'a' };
  values.set('adminToken', 'expired');
  await app.reload();
  assert.equal(app.account, undefined);
  assert.equal(app.token, '');
  assert.equal(values.has('adminToken'), false);
  assert.match(app.error, /expired/);
});
test('failed access update preserves rows and reports error without success message', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 503,
    json: async () => ({ message: 'Service unavailable' }),
  }));
  const account = { id: 'r', active: true, role: 'REVIEWER' };
  app.accounts = [account];
  await app.change(account, false, 'REVIEWER');
  assert.equal(app.accounts[0].active, true);
  assert.match(app.error, /unavailable/);
  assert.equal(app.notice, '');
});

test('saved reviewer selection survives refresh and can be unassigned', async () => {
  let saved = 'reviewer-1';
  const { app } = setup(async (url, options) => {
    if (options.method === 'POST') {
      saved = JSON.parse(options.body).reviewerId;
      return ok({ success: true });
    }
    if (url.endsWith('/accounts'))
      return ok([{ id: 'reviewer-1', name: 'Reviewer', role: 'REVIEWER', active: true }]);
    if (url.endsWith('/assignments'))
      return ok([{ ID: 'job', TITLE: 'Java', REVIEWER_ID: saved || null }]);
    return ok([]);
  });
  await app.refresh();
  assert.equal(app.assignments[0].REVIEWER_ID, 'reviewer-1');
  await app.assign('job', '');
  assert.equal(app.assignments[0].REVIEWER_ID, null);
  await app.assign('job', 'reviewer-1');
  await app.refresh();
  assert.equal(app.assignments[0].REVIEWER_ID, 'reviewer-1');
});
test('saved assignment remains selected if the subsequent list refresh fails', async () => {
  const { app } = setup(async (url, options) => {
    if (options.method === 'POST') return ok({ success: true });
    throw Error('offline');
  });
  app.assignments = [{ ID: 'job', REVIEWER_ID: null }];
  await app.assign('job', 'reviewer-1');
  assert.equal(app.assignments[0].REVIEWER_ID, 'reviewer-1');
  assert.match(app.notice, /saved/);
  assert.equal(app.error, '');
});
test('failed assignment restores the previous saved selection', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 503,
    json: async () => ({ message: 'Service unavailable' }),
  }));
  const previous = { ID: 'job', REVIEWER_ID: 'original' };
  app.assignments = [previous];
  await app.assign('job', 'replacement');
  assert.equal(app.assignments[0].REVIEWER_ID, 'original');
  assert.notEqual(app.assignments[0], previous);
  assert.match(app.error, /unavailable/);
  assert.equal(app.notice, '');
});

test('template approver selection is persisted separately from candidate reviewer selection', async () => {
  let assigned = '';
  const { app } = setup(async (url, options) => {
    if (options.method === 'POST') {
      assert.match(url, /template-assignments\/java-v1$/);
      assigned = JSON.parse(options.body).reviewerId;
      return ok({ success: true });
    }
    if (url.endsWith('/template-assignments'))
      return ok([{ VERSION: 'java-v1', REVIEWER_ID: assigned }]);
    if (url.endsWith('/assignments'))
      return ok([{ ID: 'interview', REVIEWER_ID: 'candidate-reviewer' }]);
    return ok([]);
  });
  await app.assignTemplate('java-v1', 'technical-approver');
  assert.equal(app.templateAssignments[0].REVIEWER_ID, 'technical-approver');
  assert.equal(app.assignments[0].REVIEWER_ID, 'candidate-reviewer');
});
test('failed template approver update retains original assignment', async () => {
  const { app } = setup(async () => ({
    ok: false,
    status: 403,
    json: async () => ({ message: 'Not allowed' }),
  }));
  app.templateAssignments = [{ VERSION: 'v', REVIEWER_ID: 'original' }];
  await app.assignTemplate('v', 'replacement');
  assert.equal(app.templateAssignments[0].REVIEWER_ID, 'original');
  assert.match(app.error, /Not allowed/);
  assert.equal(app.notice, '');
});
