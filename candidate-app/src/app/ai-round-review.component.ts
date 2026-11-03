import { Component, Input, Output, EventEmitter, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'exl-ai-round-review',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './ai-round-review.component.html',
  styleUrls: ['./ai-round-review.component.css'],
})
export class AiRoundReviewComponent implements OnInit {
  @Input() token = '';
  @Output() authenticationRequired = new EventEmitter<void>();
  items: any[] = [];
  page = 0;
  pages = 1;
  total = 0;
  detail?: any;
  topicIndex = 0;
  marks: { questionId: string; criterionId: string; points: number | null }[] = [];
  notes = '';
  recommendation = '';
  busy = false;
  dirty = false;
  error = '';
  notice = '';

  async ngOnInit() {
    await this.refresh();
  }

  async api<T>(path = '', body?: unknown): Promise<T> {
    const response = await fetch('http://127.0.0.1:8080/api/review/ai-rounds' + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: 'Bearer ' + this.token, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      if (response.status === 401) this.authenticationRequired.emit();
      let message = 'Review request failed';
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

  async refresh() {
    await this.run(async () => {
      const result = await this.api<any>('?page=' + this.page);
      this.items = result.items;
      this.page = result.page;
      this.pages = result.pages;
      this.total = result.total;
    });
  }

  async changePage(page: number) {
    if (page < 0 || page >= this.pages || this.dirty) return;
    this.page = page;
    await this.refresh();
  }

  private apply(detail: any) {
    this.detail = detail;
    const saved = JSON.parse(detail.attempt.REVIEW_MARKS);
    this.marks = detail.plan.topics.flatMap((topic: any) =>
      topic.rubric.map((criterion: any) => ({
        questionId: topic.id,
        criterionId: criterion.id,
        points:
          saved.find((m: any) => m.questionId === topic.id && m.criterionId === criterion.id)
            ?.points ?? null,
      }))
    );
    this.notes = detail.attempt.REVIEW_NOTES;
    this.recommendation = detail.attempt.RECOMMENDATION || '';
    this.topicIndex = 0;
    this.dirty = false;
  }

  async open(id: string) {
    if (this.dirty) {
      this.error = 'Save your draft before opening another submission.';
      return;
    }
    await this.run(async () => this.apply(await this.api('/' + id)));
  }

  mark(criterionId: string) {
    return this.marks.find((m) => m.questionId === this.topic.id && m.criterionId === criterionId)!;
  }

  get topic() {
    return this.detail?.plan.topics[this.topicIndex];
  }
  get conversation() {
    return this.detail?.transcript.filter((t: any) => t.topic === this.topicIndex) || [];
  }
  get draftTopic() {
    return this.detail?.transcript.at(-1)?.topic ?? 0;
  }
  get locked() {
    return this.detail?.attempt.STATE !== 'SUBMITTED';
  }
  get complete() {
    return (
      this.marks.length > 0 &&
      this.marks.every((m) => Number.isInteger(m.points)) &&
      !!this.recommendation
    );
  }

  async save(publish: boolean) {
    if (!this.detail || this.locked || (publish && !this.complete)) return;
    await this.run(async () => {
      this.apply(
        await this.api('/' + this.detail.attempt.ID, {
          marks: this.marks,
          notes: this.notes,
          recommendation: this.recommendation,
          publish,
        })
      );
      this.notice = publish
        ? 'Human feedback published. The recruiter still controls progression.'
        : 'Review draft saved.';
    });
  }

  back() {
    if (this.dirty) {
      this.error = 'Save the draft before returning to the queue.';
      return;
    }
    this.detail = undefined;
    void this.refresh();
  }
}
