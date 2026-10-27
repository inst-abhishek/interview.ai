import {Component,Input,Output,EventEmitter,OnInit,OnChanges} from '@angular/core';
import {CommonModule} from '@angular/common';import {FormsModule} from '@angular/forms';
type ReviewQuestion={id:string;kind:string;text:string;choices?:string[]};
@Component({selector:'exl-template-evaluation',standalone:true,imports:[CommonModule,FormsModule],templateUrl:'./template-evaluation.component.html',styleUrls:['./template-evaluation.component.css']})
export class TemplateEvaluationComponent implements OnInit,OnChanges {
 @Input() token='';@Input() sessionId='';@Input() role:'reviewer'|'recruiter'='reviewer';@Input() view:'overview'|'answers'|'feedback'|'evidence'|'all'='all';
 @Input() questions:ReviewQuestion[]=[];@Input() answers:{questionId:string;answer:string}[]=[];@Input() preferredQuestionId='';
 @Output() published=new EventEmitter<void>();@Output() navigate=new EventEmitter<'answers'|'feedback'>();data:any;busy=false;error='';notice='';dirty=false;search='';kind='';status='';selectedId='';private generation=0;
 ngOnInit(){void this.load();}
 ngOnChanges(changes:any){if(changes.sessionId&&!changes.sessionId.firstChange){this.generation++;this.data=undefined;this.dirty=false;this.selectedId='';this.search='';this.kind='';this.status='';void this.load();}if(changes.preferredQuestionId&&this.preferredQuestionId){this.selectedId=this.preferredQuestionId;this.search='';this.kind='';this.status='';}}
 private path(){return '/'+(this.role==='reviewer'?'review/sessions/':'recruiter/submissions/')+this.sessionId;}
 private async api(suffix:string,body?:unknown){const r=await fetch('http://127.0.0.1:8080/api'+this.path()+suffix,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+this.token,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});if(!r.ok){let message='Evaluation request failed ('+r.status+')';try{message=(await r.json()).message||message;}catch{}throw Error(message);}return r.json();}
 async load(){const generation=++this.generation;this.busy=true;this.error='';try{const fresh=await this.api('/template-evaluation');if(generation!==this.generation)return;if(this.dirty&&this.data&&fresh.state!=='PUBLISHED'){const old=this.data;fresh.rubric=fresh.rubric.map((row:any)=>{const draft=old.rubric.find((r:any)=>r.questionId===row.questionId&&r.criterionId===row.criterionId);return draft?{...row,points:draft.points,notes:draft.notes}:row;});fresh.notes=old.notes;fresh.recommendation=old.recommendation;}else this.dirty=false;this.data=fresh;if(!this.selectedId)this.selectedId=this.preferredQuestionId||this.questionList[0]?.id||'';}catch(e){if(generation===this.generation)this.error=String(e);}finally{if(generation===this.generation)this.busy=false;}}
 edit(){this.dirty=true;this.notice='';}
 get locked(){return this.role!=='reviewer'||this.data?.state==='PUBLISHED'||this.busy;}
 get questionList():ReviewQuestion[]{if(this.questions.length)return this.questions;const unique=new Map<string,ReviewQuestion>();for(const q of this.data?.mcqs||[])unique.set(q.questionId,{id:q.questionId,kind:'MCQ',text:q.question});for(const r of this.data?.rubric||[])if(!unique.has(r.questionId))unique.set(r.questionId,{id:r.questionId,kind:'TECHNICAL',text:r.question});return [...unique.values()];}
 type(q:ReviewQuestion){return q.kind==='MCQ'?'MCQ':q.kind==='CODING'?'Coding':'Written response';}
 answer(id:string){return this.answers.find(a=>a.questionId===id)?.answer||this.data?.mcqs?.find((q:any)=>q.questionId===id)?.candidateAnswer||'';}
 rows(id:string):any[]{return (this.data?.rubric||[]).filter((r:any)=>r.questionId===id);}
 validPoints(r:any){return typeof r.points==='number'&&Number.isInteger(r.points)&&r.points>=0&&r.points<=r.maxPoints;}
 questionScored(id:string){const rows=this.rows(id);return rows.length>0&&rows.every(r=>this.validPoints(r));}
 statusLabel(q:ReviewQuestion){if(q.kind==='MCQ'){const result=this.data?.mcqs?.find((r:any)=>r.questionId===q.id);return !this.answer(q.id).trim()?'Unanswered':result&&result.points===result.maxPoints?'Correct':'Incorrect';}return this.questionScored(q.id)?'Scored':'Needs scoring';}
 applyFilters(){this.selectedId='';this.selectedId=this.filteredQuestions[0]?.id||'';}
 get filteredQuestions(){return this.questionList.filter(q=>(!this.kind||q.kind===this.kind)&&q.text.toLowerCase().includes(this.search.toLowerCase())&&(!this.status||q.kind!=='MCQ'&&q.id===this.selectedId&&['needs','scored'].includes(this.status)||(this.status==='unanswered'?!this.answer(q.id).trim():this.status==='needs'?q.kind!=='MCQ'&&!this.questionScored(q.id):this.status==='scored'?q.kind!=='MCQ'&&this.questionScored(q.id):this.status==='correct'?this.statusLabel(q)==='Correct':this.statusLabel(q)==='Incorrect')));}
 get current(){return this.filteredQuestions.find(q=>q.id===this.selectedId)||this.filteredQuestions[0];}
 get currentIndex(){return this.current?this.filteredQuestions.findIndex(q=>q.id===this.current!.id):-1;}
 move(delta:number){const q=this.filteredQuestions[this.currentIndex+delta];if(q)this.selectedId=q.id;}
 resetFilters(){this.search='';this.kind='';this.status='';this.applyFilters();}
 questionNumber(id:string){return this.questionList.findIndex(q=>q.id===id)+1;}
 trackQuestion(_index:number,q:ReviewQuestion){return q.id;}
 get currentMcq(){return this.data?.mcqs?.find((q:any)=>q.questionId===this.current?.id);}
 get manualIds():string[]{return [...new Set<string>((this.data?.rubric||[]).map((r:any)=>r.questionId))];}
 get scoredManual(){return this.manualIds.filter(id=>this.questionScored(id)).length;}
 get manualPoints(){return (this.data?.rubric||[]).reduce((sum:number,r:any)=>sum+(this.validPoints(r)?r.points:0),0);}
 get manualMax(){return (this.data?.rubric||[]).reduce((sum:number,r:any)=>sum+r.maxPoints,0);}
 get remainingCriteria(){return (this.data?.rubric||[]).filter((r:any)=>!this.validPoints(r)).length;}
 get codingReady(){return ['DONE','NOT_REQUIRED'].includes(this.data?.codingState)&&typeof this.data?.codingPoints==='number';}
 get total(){return this.remainingCriteria||!this.codingReady?null:Math.round((this.data.mcqPoints+this.data.codingPoints+this.manualPoints)*100)/100;}
 get blockers(){const reasons:string[]=[];if(this.remainingCriteria)reasons.push(this.remainingCriteria+' scoring criteria need valid points');if(!this.codingReady)reasons.push(this.data?.codingState==='ERROR'?'Final coding evaluation needs a retry':'Final coding evaluation is pending');if(!this.data?.recommendation)reasons.push('Choose a recommendation');return reasons;}
 get codingLabel(){return this.data?.codingState==='NOT_REQUIRED'?'No coding questions':this.data?.codingState==='DONE'?'Tests completed':this.data?.codingState==='ERROR'?'Execution unavailable':this.data?.codingState==='RUNNING'?'Tests running':'Tests queued';}
 async save(publish:boolean){if(this.busy||this.role!=='reviewer'||this.data?.state==='PUBLISHED')return;const generation=this.generation;this.busy=true;this.error='';this.notice='';try{const saved=await this.api('/template-evaluation',{marks:this.data.rubric.map((r:any)=>({questionId:r.questionId,criterionId:r.criterionId,points:r.points,notes:r.notes})),notes:this.data.notes,recommendation:this.data.recommendation,publish});if(generation!==this.generation)return;this.data=saved;this.dirty=false;this.notice=publish?'Evaluation published.':'Evaluation draft saved.';if(publish)this.published.emit();}catch(e){if(generation===this.generation)this.error=String(e);}finally{if(generation===this.generation)this.busy=false;}}
 async retry(){if(this.locked)return;this.busy=true;this.error='';try{await this.api('/retry-coding',{});this.notice='Coding evaluation queued again.';await this.load();}catch(e){this.error=String(e);}finally{this.busy=false;}}
}
