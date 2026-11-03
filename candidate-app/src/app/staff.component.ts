import { Component, EventEmitter, Output, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
type Account = {
  id: string;
  name: string;
  email: string;
  role: string;
  active: boolean;
  canAuthor?: boolean;
};
@Component({
  selector: 'exl-staff',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './staff.component.html',
  styleUrls: ['./staff.component.css'],
})
export class StaffComponent implements OnInit {
  @Output() exit = new EventEmitter<void>();
  @Output() signedIn = new EventEmitter<{ token: string; account: Account }>();
  token = sessionStorage.getItem('adminToken') || '';
  account?: Account;
  accounts: Account[] = [];
  assignments: any[] = [];
  assignmentFilters = { search: '', recruiter: '', reviewer: '', status: 'ALL' };
  appliedAssignmentFilters = { ...this.assignmentFilters };
  assignmentPage = 0;
  assignmentPages = 1;
  assignmentTotal = 0;
  assignmentDrafts: Record<string, string> = {};
  reviewerSearch: Record<string, string> = {};
  reviewerOptions: Record<string, any[]> = {};
  processesFor = '';
  processUsagePage = 0;
  processUsagePages = 1;
  assignmentProcesses: any[] = [];
  templateAssignments: any[] = [];
  activity: any[] = [];
  mode: 'login' | 'bootstrap' | 'reset' = 'login';
  tab = 'accounts';
  setupRequired = false;
  busy = false;
  error = '';
  notice = '';
  email = '';
  password = '';
  name = '';
  setupCode = '';
  role = 'REVIEWER';
  resetToken = '';
  newPassword = '';
  resetResult = '';
  async ngOnInit() {
    try {
      this.setupRequired = (await this.api('/setup')).required;
      if (this.token) {
        this.account = await this.api('/me');
        await this.refresh();
      }
    } catch (e) {
      this.token = '';
      sessionStorage.removeItem('adminToken');
      this.error = String(e);
    }
  }
  async api(path: string, body?: unknown): Promise<any> {
    const r = await fetch('http://127.0.0.1:8080/api/staff' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: 'Bearer ' + this.token } : {}),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!r.ok) {
      if (r.status === 401) {
        this.token = '';
        this.account = undefined;
        sessionStorage.removeItem('adminToken');
      }
      let message = 'Request failed (' + r.status + ')';
      try {
        message = (await r.json()).message || message;
      } catch {}
      throw Error(message);
    }
    return r.json();
  }
  async run(action: () => Promise<void>) {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    this.notice = '';
    try {
      await action();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }
  async login() {
    await this.run(async () => {
      if (this.mode === 'reset') {
        await this.api('/reset-password', { token: this.resetToken, password: this.newPassword });
        this.resetToken = '';
        this.newPassword = '';
        this.mode = 'login';
        this.notice = 'Password updated. Sign in with your new password.';
        return;
      }
      const r = await this.api(
        this.mode === 'bootstrap' ? '/bootstrap' : '/login',
        this.mode === 'bootstrap'
          ? {
              name: this.name,
              email: this.email,
              password: this.password,
              setupCode: this.setupCode,
              role: 'ADMIN',
            }
          : { email: this.email, password: this.password }
      );
      this.password = '';
      this.setupCode = '';
      this.name = '';
      this.email = '';
      if (r.account.role === 'ADMIN') {
        this.token = r.token;
        this.account = r.account;
        sessionStorage.removeItem('recruiterToken');
        sessionStorage.removeItem('reviewerToken');
        sessionStorage.removeItem('reviewerName');
        sessionStorage.setItem('adminToken', this.token);
        await this.refresh();
      } else this.signedIn.emit(r);
    });
  }
  async reload() {
    await this.run(() => this.refresh());
  }
  async refresh() {
    [this.accounts, this.activity, this.templateAssignments] = await Promise.all([
      this.api('/accounts'),
      this.api('/activity'),
      this.api('/template-assignments'),
    ]);
    await this.loadAssignments();
  }
  async create() {
    await this.run(async () => {
      await this.api('/accounts', {
        name: this.name,
        email: this.email,
        password: this.password,
        role: this.role,
      });
      this.name = '';
      this.email = '';
      this.password = '';
      await this.refresh();
      this.notice =
        'Account created. Share initial credentials privately; the user can reset them using an administrator-issued token.';
    });
  }
  async change(a: Account, active: boolean, role: string) {
    await this.run(async () => {
      await this.api('/accounts/' + a.id, { active, role });
      await this.refresh();
      this.notice = 'Access updated. Existing sessions revoked.';
    });
  }
  async authorPermission(a: Account, enabled: boolean) {
    await this.run(async () => {
      await this.api('/accounts/' + a.id + '/author-permission', { enabled });
      await this.refresh();
      this.notice = 'Author permission updated. The reviewer must sign in again.';
    });
  }
  async reset(a: Account) {
    this.resetResult = '';
    await this.run(async () => {
      const r = await this.api('/accounts/' + a.id + '/reset', {});
      this.resetResult = r.token;
      this.notice =
        'Reset token for ' +
        a.name +
        '. Share privately. Valid for 30 minutes and one use; open Staff sign in > Reset password.';
      await this.refresh();
    });
  }
  async loadAssignments() {
    const params = new URLSearchParams({
      ...this.appliedAssignmentFilters,
      page: String(this.assignmentPage),
    });
    const result = await this.api('/assignments/page?' + params);
    this.assignments = result.items;
    this.assignmentPage = result.page;
    this.assignmentPages = result.pages;
    this.assignmentTotal = result.total;
    for (const row of this.assignments) {
      this.assignmentDrafts[row.ID] ??= row.REVIEWER_ID || '';
    }
  }
  async filterAssignments() {
    await this.run(async () => {
      this.appliedAssignmentFilters = { ...this.assignmentFilters };
      this.assignmentPage = 0;
      await this.loadAssignments();
    });
  }
  async changeAssignmentPage(page: number) {
    if (page < 0 || page >= this.assignmentPages) return;
    await this.run(async () => {
      this.assignmentPage = page;
      await this.loadAssignments();
    });
  }
  async findReviewers(id: string) {
    await this.run(async () => {
      const selected = (this.reviewerOptions[id] || []).find(
        (r) => r.ID === this.assignmentDrafts[id]
      );
      const results = await this.api(
        '/reviewer-options?search=' + encodeURIComponent(this.reviewerSearch[id] || '')
      );
      this.reviewerOptions[id] =
        selected && !results.some((r: any) => r.ID === selected.ID)
          ? [selected, ...results]
          : results;
    });
  }
  optionsFor(row: any) {
    const options = this.reviewerOptions[row.ID] || [];
    return row.REVIEWER_ID && !options.some((r: any) => r.ID === row.REVIEWER_ID)
      ? [{ ID: row.REVIEWER_ID, NAME: row.REVIEWER_NAME, EMAIL: row.REVIEWER_EMAIL }, ...options]
      : options;
  }
  async showProcesses(id: string, page = 0) {
    await this.run(async () => {
      const result = await this.api('/assignments/' + id + '/processes?page=' + page);
      this.assignmentProcesses = result.items;
      this.processUsagePage = result.page;
      this.processUsagePages = result.pages;
      this.processesFor = id;
    });
  }
  async assign(id: string, reviewerId: string) {
    await this.run(async () => {
      await this.api('/assignments/' + id, { reviewerId });
      const row = this.assignments.find((a) => a.ID === id);
      if (row) {
        const selected = this.optionsFor(row).find((r: any) => r.ID === reviewerId);
        row.REVIEWER_ID = reviewerId || null;
        row.REVIEWER_NAME = selected?.NAME || null;
        row.REVIEWER_EMAIL = selected?.EMAIL || null;
      }
      this.assignmentDrafts[id] = reviewerId;
      this.notice = 'Reviewer assignment saved.';
      try {
        await this.loadAssignments();
      } catch {
        this.notice = 'Reviewer assignment saved. Refresh to reload the latest information.';
      }
    });
  }
  async assignTemplate(version: string, reviewerId: string) {
    await this.run(async () => {
      try {
        await this.api('/template-assignments/' + version, { reviewerId });
      } catch (e) {
        this.templateAssignments = this.templateAssignments.map((t) => ({ ...t }));
        throw e;
      }
      this.templateAssignments = this.templateAssignments.map((t) =>
        t.VERSION === version ? { ...t, REVIEWER_ID: reviewerId || null } : t
      );
      this.notice = 'Template approver assignment updated.';
      try {
        await this.refresh();
      } catch {
        this.notice = 'Template approver saved. Refresh to reload the latest information.';
      }
    });
  }
  templateStatus(status: string) {
    return (
      (
        {
          DRAFT: 'Needs approval',
          IN_REVIEW: 'Awaiting approval',
          APPROVED: 'Approved',
          RETIRED: 'Retired',
        } as Record<string, string>
      )[status] || status
    );
  }
  async logout() {
    await this.run(async () => {
      await this.api('/logout', {});
      this.token = '';
      this.account = undefined;
      this.resetResult = '';
      sessionStorage.removeItem('adminToken');
      this.exit.emit();
    });
  }
  activityLabel(action: string) {
    return (
      (
        {
          TEMPLATE_APPROVER_ASSIGNED: 'Updated the template approver assignment.',
          TEMPLATE_APPROVED: 'Approved an assessment template.',
          SIGN_IN: 'Signed in.',
          SIGN_OUT: 'Signed out.',
          ADMIN_BOOTSTRAP: 'Set up the first administrator account.',
          ACCOUNT_CREATED: 'Created a staff account.',
          ACCESS_CHANGED: 'Updated account access.',
          RESET_ISSUED: 'Issued a password reset token.',
          PASSWORD_RESET: 'Reset the account password.',
          REVIEWER_ASSIGNED: 'Updated the reviewer assignment.',
          REVIEW_SAVED: 'Saved a candidate review.',
          EVALUATION_SAVED: 'Saved an evaluation draft.',
          EVALUATION_PUBLISHED: 'Published a candidate evaluation.',
        } as Record<string, string>
      )[action] || 'Recorded an activity.'
    );
  }
  get reviewers() {
    return this.accounts.filter((a) => a.active && a.role === 'REVIEWER');
  }
}
