import {Component, OnDestroy, OnInit, AfterViewInit, ViewChild, ElementRef} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {RecruiterComponent} from './recruiter.component';
import {TemplateCatalogueComponent} from './template-catalogue.component';
import {TemplateEvaluationComponent} from './template-evaluation.component';
import {JavaCodeComponent} from './java-code.component';
declare const ImageCapture: new(track:MediaStreamTrack)=>{grabFrame():Promise<ImageBitmap>};
type Status='pending'|'passed'|'failed';
type Check={key:string;label:string;detail:string;status:Status};
type Question={id:string;kind:string;text:string;choices:string[]};
type Session={id:string;state:string;evidenceConsent:boolean;questions:Question[];answers:{questionId:string;answer:string}[];deadline:number;serverTime:number;submissionReason?:string;templateVersion?:string};
type ReviewSummary={sessionId:string;submittedAt:number;startedAt:number|null;score:number|null;recommendation:string|null;reviewedAt:number|null;candidateName:string|null;candidateEmail:string|null;interviewTitle:string|null};
type ReviewFilters={search:string;status:string;interview:string;from:string;to:string;sort:string;size:number};
type ReviewPage={items:ReviewSummary[];page:number;size:number;total:number;totalPages:number;allCount:number;awaitingCount:number;reviewedCount:number};
type ReviewTab='overview'|'answers'|'evidence'|'feedback';
type EvidenceSummary={id:string;eventType:string;mediaType:string;capturedAt:number;expiresAt:number};
type ReviewDetail={sessionId:string;state:string;submittedAt:number;startedAt:number|null;questions:Question[];answers:{questionId:string;answer:string}[];checks:Check[];evidenceConsent:boolean;evidenceRetentionDays:number;evidence:EvidenceSummary[];score:number|null;notes:string|null;recommendation:string|null;reviewedAt:number|null;templateVersion?:string};
@Component({selector:'exl-root',standalone:true,imports:[CommonModule,FormsModule,RecruiterComponent,TemplateCatalogueComponent,TemplateEvaluationComponent,JavaCodeComponent],templateUrl:'./app.component.html'})
export class AppComponent implements OnDestroy, OnInit, AfterViewInit {
 private api='http://127.0.0.1:8080/api';
 showTemplateCatalogue=false;
 reviewFilters:ReviewFilters={search:'',status:'AWAITING',interview:'',from:'',to:'',sort:'OLDEST',size:10};private activeReviewFilters:ReviewFilters={...this.reviewFilters};
 reviewListPage=0;reviewListTotal=0;reviewListPages=1;reviewAllCount=0;reviewAwaitingCount=0;reviewReviewedCount=0;reviewListLoading=false;private reviewListRequest=0;
 selectedCandidateName='Legacy demo candidate';selectedCandidateEmail='';selectedInterviewTitle='Shared-code assessment';
 reviewTab:ReviewTab='overview';reviewQuestionId='';reviewQuestionKind='';reviewAnswerFilter='';reviewQuestionSearch='';evidenceTypeFilter='';evidenceMediaFilter='';timelinePage=0;evidencePage=0;readonly timelinePageSize=8;readonly evidencePageSize=6;
 @ViewChild('evidenceDialog') private evidenceDialog?:ElementRef<HTMLDialogElement>;
 recruiterMode=false;invitationFromLink=false;
 @ViewChild('signInDialog') private signInDialog?:ElementRef<HTMLDialogElement>;
 assignedInterview?:{title:string;candidateName:string;scheduledAt:number;expiresAt:number;durationSeconds:number};
 step:'readiness'|'instructions'|'assessment'='readiness';
 busy=false;message='';sessionId=sessionStorage.getItem('interviewSession')||'';consent=false;evidenceConsent=false;answer='';fullscreenWarning=false;private fullscreenRequired=false;
 token=sessionStorage.getItem('interviewToken')||'';invitationCode='';signInError='';completed=false;
 reviewerToken=sessionStorage.getItem('reviewerToken')||'';reviewerCode='';reviewerError='';reviewerMessage='';reviewerSessions:ReviewSummary[]=[];selectedReview?:ReviewDetail;selectedEvidenceId='';selectedEvidenceUrl='';reviewScore=3;reviewNotes='';reviewRecommendation='';reviewStage:'candidates'|'candidate'|'feedback'='candidates';reviewBusy=false;
 drafts:Record<string,string>={};savedAnswers:Record<string,string>={};marked=new Set<string>();visited=new Set<string>();reviewing=false;confirmUnanswered=false;saveNotice='';
 questions:Question[]=[];questionIndex=0;deadline=0;remainingSeconds=0;private offset=0;
 private timer=setInterval(()=>this.tick(),1000);
 autosaving=false;timeWarning='';expiryStatus='';lastSavedAt=0;private lastAutosaveAt=0;private lastExpiryCheckAt=0;private expiryChecking=false;private warnedThresholds=new Set<number>();private saveQueue:Promise<void>=Promise.resolve();
 private pendingEvidence=new Set<string>();private destroying=false;evidenceRetentionDays=30;
 private pendingFocusEvent?:'TAB_HIDDEN'|'WINDOW_BLUR';private focusCaptureTimer?:ReturnType<typeof setTimeout>;
 checks:Check[]=[{key:'camera',label:'Webcam',detail:'Request camera stream',status:'pending'},{key:'microphone',label:'Microphone',detail:'Request microphone stream',status:'pending'},{key:'screen',label:'Entire-screen sharing',detail:'Select an entire display',status:'pending'},{key:'service',label:'Backend connectivity',detail:'Contact the API',status:'pending'},{key:'desktop',label:'Desktop integrity agent',detail:'Native monitoring is not implemented',status:'pending'}];
 camera?:MediaStream;microphone?:MediaStream;screen?:MediaStream;
 get allPassed(){return this.checks.filter(c=>c.key!=='desktop').every(c=>c.status==='passed');}
 get expired(){return this.deadline>0&&this.remainingSeconds<=0;}
 get timeLabel(){return `${Math.floor(this.remainingSeconds/60)}:${String(this.remainingSeconds%60).padStart(2,'0')}`;}
 ngAfterViewInit(){if(this.invitationFromLink)this.signInDialog?.nativeElement.showModal();}
 private readInvitationLink(){if(!window.location?.hash)return;const params=new URLSearchParams(window.location.hash.slice(1));const code=params.get('invite');if(!code||code.length>200)return;this.invitationCode=code;this.invitationFromLink=true;this.reviewerToken='';params.delete('invite');const remaining=params.toString();window.history.replaceState(window.history.state,'',window.location.pathname+window.location.search+(remaining?'#'+remaining:''));}
 ngOnInit(){this.readInvitationLink();if(this.token&&!this.invitationFromLink)void this.loadAssignedInvitation();if(this.reviewerToken){this.restoreReviewList();void this.loadReviews();}document.addEventListener('visibilitychange',this.onVisibilityChange);document.addEventListener('fullscreenchange',this.onFullscreenChange);window.addEventListener('blur',this.onWindowBlur);window.addEventListener('focus',this.onWindowFocus);}
 setCheck(key:string,status:Status,detail?:string){const c=this.checks.find(c=>c.key===key);if(c){c.status=status;if(detail)c.detail=detail;}}
 private async request<T>(path:string,body?:unknown,token=this.token):Promise<T>{const r=await fetch(`${this.api}${path}`,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});if(!r.ok){let detail=`Request failed (${r.status})`;try{const e=await r.json();detail=e.message||detail;}catch{}throw new Error(detail);}return r.json();}
 async login(dialog?:HTMLDialogElement){if(this.busy||!this.invitationCode.trim())return;this.busy=true;this.signInError='';try{const r=await this.request<{token:string;sessionId?:string}>('/auth/login',{code:this.invitationCode});this.token=r.token;sessionStorage.setItem('interviewToken',this.token);this.sessionId=r.sessionId||'';if(this.sessionId)sessionStorage.setItem('interviewSession',this.sessionId);else sessionStorage.removeItem('interviewSession');this.invitationCode='';dialog?.close();await this.loadAssignedInvitation();}catch(e){this.signInError=e instanceof Error?e.message:String(e);}finally{this.busy=false;}}
 private async loadAssignedInvitation(){try{this.assignedInterview=await this.request('/invitation');}catch(e){this.message='Signed in, but invitation details could not be loaded. You can retry device checks.';}}
 async reviewerLogin(dialog?:HTMLDialogElement){if(this.reviewBusy||!this.reviewerCode.trim())return;this.reviewBusy=true;this.reviewerError='';try{const r=await this.request<{token:string}>('/auth/reviewer-login',{code:this.reviewerCode});this.reviewerToken=r.token;sessionStorage.setItem('reviewerToken',this.reviewerToken);this.reviewerCode='';dialog?.close();await this.loadReviews();}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);}finally{this.reviewBusy=false;}}
 private restoreReviewList(){try{const saved=JSON.parse(sessionStorage.getItem('reviewerListState')||'null');if(!saved)return;const f=saved.filters;if(f&&['ALL','AWAITING','REVIEWED'].includes(f.status)&&['OLDEST','NEWEST','REVIEWED_NEWEST'].includes(f.sort)&&[10,25,50].includes(f.size)&&['search','interview','from','to'].every(k=>typeof f[k]==='string')){this.reviewFilters={...f};this.activeReviewFilters={...f};this.reviewListPage=Number.isInteger(saved.page)&&saved.page>=0?saved.page:0;}}catch{}}
 private reviewListPath(){const f=this.activeReviewFilters;const params=new URLSearchParams({page:String(this.reviewListPage),size:String(f.size),status:f.status,sort:f.sort,search:f.search.trim(),interview:f.interview.trim()});if(f.from){const date=new Date(f.from+'T00:00:00');if(!Number.isFinite(date.getTime()))throw Error('Choose a valid start date');params.set('from',String(date.getTime()));}if(f.to){const date=new Date(f.to+'T00:00:00');if(!Number.isFinite(date.getTime()))throw Error('Choose a valid end date');date.setDate(date.getDate()+1);params.set('to',String(date.getTime()));}return '/review/sessions?'+params.toString();}
 private async fetchReviewPage(){const request=++this.reviewListRequest;this.reviewListLoading=true;try{const result=await this.request<ReviewPage>(this.reviewListPath(),undefined,this.reviewerToken);if(request!==this.reviewListRequest)return;if(!Array.isArray(result.items)||!Number.isInteger(result.page)||!Number.isInteger(result.totalPages))throw Error('Restart the backend to load the updated reviewer list API.');this.reviewerSessions=result.items;this.reviewListPage=result.page;this.reviewListTotal=result.total;this.reviewListPages=result.totalPages;this.reviewAllCount=result.allCount;this.reviewAwaitingCount=result.awaitingCount;this.reviewReviewedCount=result.reviewedCount;try{sessionStorage.setItem('reviewerListState',JSON.stringify({filters:this.activeReviewFilters,page:result.page}));}catch{}}catch(e){if(request===this.reviewListRequest)throw e;}finally{if(request===this.reviewListRequest)this.reviewListLoading=false;}}
 async loadReviews(){this.reviewerError='';this.reviewerMessage='';try{await this.fetchReviewPage();return true;}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);return false;}}
 async applyReviewFilters(){const previous=this.activeReviewFilters,previousPage=this.reviewListPage;this.activeReviewFilters={...this.reviewFilters};this.reviewListPage=0;if(!await this.loadReviews()){this.activeReviewFilters=previous;this.reviewListPage=previousPage;}}
 async setReviewListStatus(status:string){this.reviewFilters.status=status;await this.applyReviewFilters();}
 async changeReviewListPage(page:number){if(this.reviewListLoading||page<0||page>=this.reviewListPages)return;const previous=this.reviewListPage;this.reviewListPage=page;if(!await this.loadReviews())this.reviewListPage=previous;}
 async resetReviewFilters(){this.reviewFilters={search:'',status:'AWAITING',interview:'',from:'',to:'',sort:'OLDEST',size:10};await this.applyReviewFilters();}
 get reviewListFirst(){return this.reviewListTotal?this.reviewListPage*this.activeReviewFilters.size+1:0;}
 get reviewListLast(){return Math.min((this.reviewListPage+1)*this.activeReviewFilters.size,this.reviewListTotal);}
 async openReview(id:string){this.reviewBusy=true;this.reviewerError='';this.reviewerMessage='';this.clearEvidencePreview();try{this.selectedReview=await this.request<ReviewDetail>(`/review/sessions/${id}`,undefined,this.reviewerToken);this.reviewScore=this.selectedReview.score??3;this.reviewNotes=this.selectedReview.notes??'';this.reviewRecommendation=this.selectedReview.recommendation??'';const identity=this.reviewerSessions.find(item=>item.sessionId===id);this.selectedCandidateName=identity?.candidateName||'Legacy demo candidate';this.selectedCandidateEmail=identity?.candidateEmail||'';this.selectedInterviewTitle=identity?.interviewTitle||'Shared-code assessment';this.reviewStage='candidate';this.reviewTab='overview';this.reviewQuestionId='';this.reviewQuestionKind='';this.reviewAnswerFilter='';this.reviewQuestionSearch='';this.evidenceTypeFilter='';this.evidenceMediaFilter='';this.timelinePage=0;this.evidencePage=0;}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);}finally{this.reviewBusy=false;}}
 hasReviewAnswer(id:string){return Boolean(this.selectedReview?.answers.some(a=>a.questionId===id&&a.answer.trim()));}
 answerFor(questionId:string){return this.selectedReview?.answers.find(answer=>answer.questionId===questionId)?.answer||'No answer submitted';}
 get answerCount(){return this.selectedReview?.answers.length??0;}
 get checksPassed(){return this.selectedReview?.checks.filter(check=>check.status==='passed').length??0;}
 get assessmentDurationMinutes(){const review=this.selectedReview;return review?.startedAt&&review.submittedAt?Math.max(0,Math.round((review.submittedAt-review.startedAt)/60000)):null;}
 get evidenceEventCount(){return this.selectedReview?.evidence.length??0;}
 recommendationLabel(value:string|null){return value==='SHORTLIST'?'Shortlist':value==='HOLD'?'Hold':value==='REJECT'?'Reject':'Review pending';}
 setReviewStage(stage:'candidates'|'candidate'|'feedback'){if(stage==='candidates')this.backToCandidates();else this.setReviewTab(stage==='feedback'?'feedback':'overview');}
 backToCandidates(){this.closeEvidence();this.selectedReview=undefined;this.reviewStage='candidates';this.reviewTab='overview';}
 setReviewTab(tab:ReviewTab){if(!this.selectedReview)return;this.reviewTab=tab;this.reviewStage=tab==='feedback'?'feedback':'candidate';}
 get filteredReviewQuestions(){return (this.selectedReview?.questions||[]).filter(q=>(!this.reviewQuestionKind||q.kind===this.reviewQuestionKind)&&q.text.toLowerCase().includes(this.reviewQuestionSearch.toLowerCase())&&(!this.reviewAnswerFilter||(this.reviewAnswerFilter==='answered')===Boolean(this.selectedReview?.answers.some(a=>a.questionId===q.id&&a.answer.trim()))));}
 get activeReviewQuestion():Question|undefined{return this.filteredReviewQuestions.find(q=>q.id===this.reviewQuestionId)||this.filteredReviewQuestions[0];}
 get reviewQuestionPosition(){return this.filteredReviewQuestions.findIndex(q=>q.id===this.activeReviewQuestion?.id);}
 reviewQuestionNumber(id:string){return (this.selectedReview?.questions.findIndex(q=>q.id===id)??-1)+1;}
 moveReviewQuestion(delta:number){const next=this.filteredReviewQuestions[this.reviewQuestionPosition+delta];if(next)this.reviewQuestionId=next.id;}
 get filteredReviewEvidence(){return (this.selectedReview?.evidence||[]).filter(e=>(!this.evidenceTypeFilter||e.eventType===this.evidenceTypeFilter)&&(!this.evidenceMediaFilter||e.mediaType===this.evidenceMediaFilter));}
 get timelinePages(){return Math.max(1,Math.ceil(this.filteredReviewEvidence.length/this.timelinePageSize));}
 get evidencePages(){return Math.max(1,Math.ceil(this.filteredReviewEvidence.length/this.evidencePageSize));}
 get timelineItems(){const page=Math.min(this.timelinePage,this.timelinePages-1);return this.filteredReviewEvidence.slice(page*this.timelinePageSize,(page+1)*this.timelinePageSize);}
 get evidenceItems(){const page=Math.min(this.evidencePage,this.evidencePages-1);return this.filteredReviewEvidence.slice(page*this.evidencePageSize,(page+1)*this.evidencePageSize);}
 resetEvidencePages(){this.timelinePage=0;this.evidencePage=0;}
 get recentReviewEvidence(){return (this.selectedReview?.evidence||[]).slice(-3).reverse();}
 get snapshotTitle(){const item=this.selectedReview?.evidence.find(e=>e.id===this.selectedEvidenceId);return item?item.mediaType+' snapshot: '+this.evidenceEventLabel(item.eventType):'Evidence snapshot';}
 closeEvidence(){this.evidenceDialog?.nativeElement.close();this.clearEvidencePreview();}
 isReviewPromptAdded(prompt:string){return this.reviewNotes.split('\n').some(line=>line.trimStart().startsWith(`${prompt}:`));}
 addReviewPrompt(prompt:string){if(this.isReviewPromptAdded(prompt))return;const prefix=this.reviewNotes.trim()?`${this.reviewNotes.trim()}\n`:'';this.reviewNotes=`${prefix}${prompt}: `;}
 async saveReview(){if(!this.selectedReview||this.reviewBusy)return;const updating=Boolean(this.selectedReview.reviewedAt);this.reviewBusy=true;this.reviewerError='';this.reviewerMessage='';try{this.selectedReview=await this.request<ReviewDetail>(`/review/sessions/${this.selectedReview.sessionId}`,{score:this.reviewScore,notes:this.reviewNotes,recommendation:this.reviewRecommendation},this.reviewerToken);this.reviewerMessage=updating?'Review updated successfully.':'Review saved successfully.';try{await this.fetchReviewPage();}catch(e){this.reviewerError=`Review saved, but the submissions list could not be refreshed: ${e instanceof Error?e.message:String(e)}`;}}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);}finally{this.reviewBusy=false;}}
 async onTemplatePublished(){if(!this.selectedReview)return;this.selectedReview=await this.request<ReviewDetail>('/review/sessions/'+this.selectedReview.sessionId,undefined,this.reviewerToken);await this.loadReviews();}
 async viewEvidence(item:EvidenceSummary){this.reviewerError='';this.clearEvidencePreview();try{const r=await fetch(`${this.api}/review/evidence/${item.id}`,{headers:{Authorization:`Bearer ${this.reviewerToken}`}});if(!r.ok)throw new Error(`Unable to load evidence (${r.status})`);this.selectedEvidenceId=item.id;this.selectedEvidenceUrl=URL.createObjectURL(await r.blob());if(this.evidenceDialog&&!this.evidenceDialog.nativeElement.open)this.evidenceDialog.nativeElement.showModal();}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);}}
 async deleteEvidence(item:EvidenceSummary){if(!this.selectedReview||this.reviewBusy)return;this.reviewBusy=true;this.reviewerError='';try{const r=await fetch(`${this.api}/review/evidence/${item.id}`,{method:'DELETE',headers:{Authorization:`Bearer ${this.reviewerToken}`}});if(!r.ok)throw new Error(`Unable to delete evidence (${r.status})`);if(this.selectedEvidenceId===item.id)this.closeEvidence();this.selectedReview.evidence=this.selectedReview.evidence.filter(evidence=>evidence.id!==item.id);this.timelinePage=Math.min(this.timelinePage,this.timelinePages-1);this.evidencePage=Math.min(this.evidencePage,this.evidencePages-1);}catch(e){this.reviewerError=e instanceof Error?e.message:String(e);}finally{this.reviewBusy=false;}}
 private clearEvidencePreview(){if(this.selectedEvidenceUrl)URL.revokeObjectURL(this.selectedEvidenceUrl);this.selectedEvidenceUrl='';this.selectedEvidenceId='';}
 evidenceEventLabel(eventType:string){return eventType==='TAB_HIDDEN'?'Browser tab hidden':eventType==='WINDOW_BLUR'?'Interview window lost focus':eventType==='FULLSCREEN_EXIT'?'Fullscreen exited':eventType==='CAMERA_STOPPED'?'Camera stream interrupted':'Screen sharing interrupted';}
 signOutReviewer(){this.closeEvidence();this.reviewerToken='';this.reviewerSessions=[];this.selectedReview=undefined;this.reviewStage='candidates';sessionStorage.removeItem('reviewerToken');sessionStorage.removeItem('reviewerListState');this.reviewFilters={search:'',status:'AWAITING',interview:'',from:'',to:'',sort:'OLDEST',size:10};this.activeReviewFilters={...this.reviewFilters};this.reviewListPage=0;this.reviewListTotal=0;this.reviewListPages=1;}
 private stop(s?:MediaStream){s?.getTracks().forEach(t=>t.stop());}
 private readonly onVisibilityChange=()=>{if(this.step!=='assessment'||!this.sessionId||this.completed)return;if(document.visibilityState==='hidden')this.queueFocusEvidence('TAB_HIDDEN');};
 private readonly onFullscreenChange=()=>{if(this.step!=='assessment'||!this.sessionId||this.completed)return;if(document.fullscreenElement){this.fullscreenRequired=true;this.fullscreenWarning=false;return;}if(!this.fullscreenRequired)return;this.fullscreenRequired=false;this.fullscreenWarning=true;this.message='Fullscreen exited. This browser cannot prevent tab or app switching; the interruption has been recorded.';void this.captureFlaggedEvidence('FULLSCREEN_EXIT');};
 private readonly onWindowBlur=()=>{if(this.step==='assessment'&&this.sessionId&&!this.completed)this.queueFocusEvidence('WINDOW_BLUR');};
 private readonly onWindowFocus=()=>{if(this.pendingFocusEvent!=='WINDOW_BLUR'||document.visibilityState==='hidden')return;if(this.focusCaptureTimer)clearTimeout(this.focusCaptureTimer);this.focusCaptureTimer=undefined;this.pendingFocusEvent=undefined;};
 private queueFocusEvidence(eventType:'TAB_HIDDEN'|'WINDOW_BLUR'){if(!this.pendingFocusEvent||eventType==='TAB_HIDDEN')this.pendingFocusEvent=eventType;if(this.focusCaptureTimer)clearTimeout(this.focusCaptureTimer);this.focusCaptureTimer=setTimeout(()=>{this.focusCaptureTimer=undefined;const queuedEvent=this.pendingFocusEvent;this.pendingFocusEvent=undefined;if(!queuedEvent||this.step!=='assessment'||this.completed)return;if(queuedEvent==='TAB_HIDDEN'&&document.visibilityState!=='hidden')return;void this.captureFlaggedEvidence(queuedEvent);},1000);}
 private isBlankFrame(canvas:HTMLCanvasElement){const context=canvas.getContext('2d');if(!context)throw new Error('Could not inspect captured frame');const {data}=context.getImageData(0,0,canvas.width,canvas.height);let samples=0,dark=0,bright=0,min=255,max=0;const step=Math.max(1,Math.floor(Math.max(canvas.width,canvas.height)/64));for(let y=0;y<canvas.height;y+=step)for(let x=0;x<canvas.width;x+=step){const i=(y*canvas.width+x)*4,luma=(data[i]*299+data[i+1]*587+data[i+2]*114)/1000;samples++;if(luma<12)dark++;if(luma>245)bright++;min=Math.min(min,data[i],data[i+1],data[i+2]);max=Math.max(max,data[i],data[i+1],data[i+2]);}return samples>0&&((dark/samples>0.97)||(bright/samples>0.97)||(max-min<3));}
 private watch(s:MediaStream,key:'camera'|'microphone'|'screen'){s.getTracks().forEach(t=>t.addEventListener('ended',()=>{const active=key==='camera'?this.camera:key==='screen'?this.screen:this.microphone;if(active===s&&!this.completed&&!this.destroying){this.setCheck(key,'failed',`${key} stream stopped`);this.message=`Monitoring interrupted: ${key} stopped. Restore access before continuing.`;if(key==='camera'||key==='screen')this.captureFlaggedEvidence(key==='camera'?'CAMERA_STOPPED':'SCREEN_STOPPED');}}));}
 private async captureFlaggedEvidence(eventType:'TAB_HIDDEN'|'WINDOW_BLUR'|'FULLSCREEN_EXIT'|'CAMERA_STOPPED'|'SCREEN_STOPPED'){
   if(!this.evidenceConsent||!this.sessionId||this.completed||this.expired)return;
   for(const mediaType of ['SCREEN','CAMERA'] as const){
     const stream=mediaType==='CAMERA'?this.camera:this.screen,track=stream?.getVideoTracks()[0],key=`${eventType}:${mediaType}`;
     if(!track||track.readyState!=='live'||this.pendingEvidence.has(key))continue;
     this.pendingEvidence.add(key);
     try{
       if(typeof ImageCapture==='undefined')throw new Error('This browser cannot capture fresh frames from the shared stream');
       const imageCapture=new ImageCapture(track),canvas=document.createElement('canvas');
       const context=canvas.getContext('2d');if(!context)throw new Error('Could not prepare snapshot canvas');
       let captured=false,grabbedFrame=false;
       let lastCaptureError:unknown;
       for(let attempt=0;attempt<4;attempt++){
         if(attempt)await new Promise(resolve=>setTimeout(resolve,250));
         let frame:ImageBitmap|undefined;
         try{
           const currentFrame=await imageCapture.grabFrame();
           frame=currentFrame;
           grabbedFrame=true;
           if(!currentFrame.width||!currentFrame.height)throw new Error('Shared stream returned an empty frame');
           const scale=Math.min(1,1280/currentFrame.width);
           canvas.width=Math.max(1,Math.round(currentFrame.width*scale));canvas.height=Math.max(1,Math.round(currentFrame.height*scale));
           context.drawImage(currentFrame,0,0,canvas.width,canvas.height);
           if(!this.isBlankFrame(canvas)){captured=true;break;}
         }catch(error){lastCaptureError=error;}
         finally{frame?.close();}
       }
       if(!captured){if(lastCaptureError&&!grabbedFrame)throw new Error(`Could not get a fresh ${mediaType.toLowerCase()} frame: ${String(lastCaptureError)}`);this.message=`Skipped blank ${mediaType.toLowerCase()} snapshot after retrying the shared stream.`;continue;}
       const blob=await new Promise<Blob>((resolve,reject)=>canvas.toBlob(value=>value?resolve(value):reject(new Error('Could not encode the snapshot')),'image/jpeg',0.72));
       if(blob.size>500_000)throw new Error('Snapshot exceeds the 500 KB upload limit');
       const bytes=new Uint8Array(await blob.arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));
       await this.request(`/sessions/${this.sessionId}/evidence`,{eventType,mediaType,contentType:'image/jpeg',imageData:btoa(binary)});
     }catch(e){this.message=`Could not save ${mediaType.toLowerCase()} snapshot: ${e instanceof Error?e.message:String(e)}`;}
     finally{this.pendingEvidence.delete(key);}
   }
 }
 async runChecks(){if(this.busy)return;this.busy=true;this.message='';try{const health=await this.request<{evidenceRetentionDays:number}>('/health');this.evidenceRetentionDays=health.evidenceRetentionDays;this.setCheck('service','passed','Backend reachable');}catch{this.setCheck('service','failed','Start backend on port 8080');}for(const key of ['camera','microphone'] as const){const previous=this[key];this[key]=undefined;this.stop(previous);try{const s=await navigator.mediaDevices.getUserMedia({video:key==='camera',audio:key==='microphone'});this[key]=s;this.watch(s,key);this.setCheck(key,'passed','Stream active');}catch{if(key==='camera'){this.setCheck(key,'passed','Demo override: webcam unavailable; only actual screen snapshots can be captured');}else{this.setCheck(key,'failed','Permission or device unavailable');}}}this.busy=false;}
 async shareScreen(){if(this.busy)return;this.busy=true;try{const previous=this.screen;this.screen=undefined;this.stop(previous);this.setCheck('screen','pending');const s=await navigator.mediaDevices.getDisplayMedia({video:{displaySurface:'monitor'},audio:false});if(s.getVideoTracks()[0]?.getSettings().displaySurface!=='monitor'){this.stop(s);this.setCheck('screen','failed','Select a verifiable entire screen');return;}this.screen=s;this.watch(s,'screen');this.setCheck('screen','passed','Entire-screen stream active');}catch{this.setCheck('screen','failed','Screen-sharing permission was declined or unavailable');}finally{this.busy=false;}}
 async proceed(){if(this.allPassed&&this.token)this.step='instructions';}
 private apply(s:Session,initialize=false){
   this.sessionId=s.id;this.questions=s.questions;this.evidenceConsent=s.evidenceConsent;this.deadline=s.deadline||0;this.offset=s.serverTime-Date.now();
   this.savedAnswers=Object.fromEntries(s.answers.map(a=>[a.questionId,a.answer]));
   if(initialize){
     this.drafts={...this.savedAnswers};this.marked=new Set();this.visited=new Set();this.warnedThresholds=new Set();this.timeWarning='';this.expiryStatus='';
     try{const local=JSON.parse(sessionStorage.getItem(this.progressKey)||'{}');for(const threshold of [300,60])if(local.warnedThresholds?.includes(threshold))this.warnedThresholds.add(threshold);for(const q of this.questions){const value=local.drafts?.[q.id];if(typeof value==='string'&&value.length<=20000&&(!q.choices.length||!value||q.choices.includes(value)))this.drafts[q.id]=value;if(local.marked?.includes(q.id))this.marked.add(q.id);if(local.visited?.includes(q.id))this.visited.add(q.id);}}catch{}
     const first=this.questions.findIndex(q=>!this.drafts[q.id]?.trim());this.questionIndex=first<0?0:first;
     this.answer=this.drafts[this.questions[this.questionIndex]?.id]||'';this.reviewing=first<0;this.confirmUnanswered=false;
     if(this.questions[this.questionIndex])this.visited.add(this.questions[this.questionIndex].id);
   }
   this.completed=s.state==='SUBMITTED';this.tick();if(this.expired&&!this.completed)this.reviewing=true;this.step='assessment';
   if(this.completed){this.releaseMedia();try{sessionStorage.removeItem(this.progressKey);}catch{}this.expiryStatus='';this.timeWarning='';this.message=s.submissionReason==='TIME_EXPIRED'?'Time expired. Your saved answers were submitted automatically.':'Assessment submitted successfully.';}else this.persistProgress();
 }
 private get progressKey(){return `assessmentProgress:${this.sessionId}`;}
 private valueFor(id:string){return this.questions[this.questionIndex]?.id===id?this.answer:(this.drafts[id]||'');}
 get answeredCount(){return this.questions.filter(q=>this.valueFor(q.id).trim()).length;}
 get remainingCount(){return this.questions.length-this.answeredCount;}
 get savedCount(){return this.questions.filter(q=>this.savedAnswers[q.id]?.trim()).length;}
 get unsavedCount(){return this.questions.filter(q=>this.valueFor(q.id)!==(this.savedAnswers[q.id]||'')).length;}
 get submissionRemaining(){return this.expired?this.questions.length-this.savedCount:this.remainingCount;}
 get markedCount(){return this.marked.size;}
 get notVisitedCount(){return this.questions.filter(q=>!this.visited.has(q.id)).length;}
 questionStatus(id:string){return `${this.valueFor(id).trim()?'Answered':this.visited.has(id)?'Unanswered':'Not visited'}${this.marked.has(id)?', marked for review':''}${this.valueFor(id)!==(this.savedAnswers[id]||'')?', not saved':''}`;}
 isAnswered(id:string){return Boolean(this.valueFor(id).trim());}
 private persistProgress(){if(!this.sessionId||this.completed)return;try{sessionStorage.setItem(this.progressKey,JSON.stringify({drafts:this.drafts,marked:[...this.marked],visited:[...this.visited],warnedThresholds:[...this.warnedThresholds]}));}catch{this.saveNotice='Browser draft recovery is unavailable. Save answers before refreshing.';}}
 updateDraft(value:string){this.answer=value;const q=this.questions[this.questionIndex];if(q)this.drafts[q.id]=value;this.saveNotice='';this.confirmUnanswered=false;this.persistProgress();}
 private stashCurrent(){const q=this.questions[this.questionIndex];if(q){this.drafts[q.id]=this.answer;this.visited.add(q.id);}this.persistProgress();}
 toggleMark(){const id=this.questions[this.questionIndex]?.id;if(!id||this.completed||this.expired)return;if(this.marked.has(id))this.marked.delete(id);else this.marked.add(id);this.persistProgress();}
 private saveDraft(id:string){const task=this.saveQueue.then(()=>this.persistDraft(id));this.saveQueue=task.catch(()=>{});return task;}
 private async persistDraft(id:string){if(this.expired||this.completed)return;const raw=this.drafts[id]||'',value=raw.trim()?raw:'';this.drafts[id]=value;if(this.questions[this.questionIndex]?.id===id)this.answer=value;if(value===(this.savedAnswers[id]||'')){this.persistProgress();return;}const s=await this.request<Session>(`/sessions/${this.sessionId}/answers`,{questionId:id,answer:value});if(s.state==='SUBMITTED'){this.apply(s);return;}if(this.completed)return;this.savedAnswers=Object.fromEntries(s.answers.map(a=>[a.questionId,a.answer]));this.lastSavedAt=Date.now();this.offset=s.serverTime-Date.now();this.tick();this.persistProgress();}
 async saveAnswer(){if(this.busy||this.completed||this.expired||!this.allPassed)return;this.stashCurrent();this.busy=true;this.saveNotice='';try{const id=this.questions[this.questionIndex]?.id;if(id)await this.saveDraft(id);this.saveNotice=this.answer.trim()?'Answer saved to server.':'Response cleared. This question is unanswered.';}catch(e){this.saveNotice=`Could not save: ${String(e)}. Your draft is retained; retry saving.`;}finally{this.busy=false;}}
 async goToQuestion(index:number){if(this.busy||this.completed||index<0||index>=this.questions.length)return;this.stashCurrent();this.busy=true;this.saveNotice='';try{const id=this.questions[this.questionIndex]?.id;if(id&&!this.expired&&this.allPassed)await this.saveDraft(id);this.questionIndex=index;const q=this.questions[index];this.answer=this.drafts[q.id]||'';this.visited.add(q.id);this.reviewing=false;this.persistProgress();}catch(e){this.saveNotice=`Could not save: ${String(e)}. Stay on this question and retry; your draft is retained.`;}finally{this.busy=false;}}
 async showSummary(){if(this.busy||this.completed)return;this.stashCurrent();this.busy=true;this.saveNotice='';try{const id=this.questions[this.questionIndex]?.id;if(id&&!this.expired&&this.allPassed)await this.saveDraft(id);this.reviewing=true;this.confirmUnanswered=false;}catch(e){this.saveNotice=`Could not save: ${String(e)}. Retry before submitting; your draft is retained.`;}finally{this.busy=false;}}
 async enterFullscreen(){const element=document.documentElement;if(!element||typeof element.requestFullscreen!=='function'){this.fullscreenWarning=true;this.message='Fullscreen is not available in this browser. Tab and app switching cannot be prevented.';return;}try{await element.requestFullscreen();if(!document.fullscreenElement)throw new Error('The browser did not enter fullscreen');this.fullscreenRequired=true;this.fullscreenWarning=false;this.message='';}catch(e){this.fullscreenWarning=true;this.message=`Could not enter fullscreen: ${e instanceof Error?e.message:String(e)}`;}}
 async start(){if(this.busy||!this.consent||!this.evidenceConsent||!this.allPassed||!this.token)return;let fullscreenRequest:Promise<void>|undefined;let fullscreenError='';const element=document.documentElement;if(!document.fullscreenElement){if(element&&typeof element.requestFullscreen==='function'){try{fullscreenRequest=element.requestFullscreen();}catch(e){fullscreenError=String(e);}}else fullscreenError='Fullscreen is not supported by this browser.';}this.busy=true;this.message='';try{if(fullscreenRequest){try{await fullscreenRequest;}catch(e){fullscreenError=String(e);}}this.fullscreenRequired=Boolean(document.fullscreenElement);this.fullscreenWarning=!this.fullscreenRequired;if(!this.fullscreenRequired)this.message=`Could not enable fullscreen: ${fullscreenError||'the browser denied the request'}. You can continue, but tab and app switching cannot be prevented.`;if(!this.sessionId){const s=await this.request<Session>('/sessions',{consent:true,evidenceConsent:true,checks:this.checks.map(({key,status})=>({key,status}))});this.sessionId=s.id;sessionStorage.setItem('interviewSession',s.id);}let s=await this.request<Session>(`/sessions/${this.sessionId}`);if(s.state==='READY')s=await this.request<Session>(`/sessions/${this.sessionId}/start`,{});this.apply(s,true);if(this.fullscreenWarning)this.message=`Could not enable fullscreen: ${fullscreenError||'the browser denied the request'}. You can continue, but tab and app switching cannot be prevented.`;}catch(e){this.message=`Unable to start: ${String(e)}`;}finally{this.busy=false;}}
 async submit(){
   if(this.busy||this.completed||!this.reviewing)return;
   if(!this.expired&&!this.allPassed)return;
   this.stashCurrent();if(!this.expired&&this.submissionRemaining>0&&!this.confirmUnanswered)return;
   this.busy=true;this.message='';this.saveNotice='';
   try{
     if(!this.expired){for(const q of this.questions)await this.saveDraft(q.id);}
     const s=await this.request<Session>(`/sessions/${this.sessionId}/submit`,{allowUnanswered:this.confirmUnanswered});
     if(s.state!=='SUBMITTED')throw new Error('Server did not confirm submission');this.apply(s);
   }catch(e){this.message=`Submission failed: ${String(e)}. Saved answers and drafts are retained; retry submission.`;}
   finally{this.busy=false;}
 }
 async autosave(){
   if(this.busy||this.autosaving||this.completed||this.expired||this.step!=='assessment'||!this.allPassed||!this.unsavedCount)return;
   this.stashCurrent();this.autosaving=true;
   try{for(const q of this.questions){if(this.expired||this.completed)break;await this.saveDraft(q.id);}if(!this.expired&&!this.completed)this.saveNotice=this.unsavedCount?'Saved changes; newer edits are waiting for the next autosave.':'Answers autosaved to server.';}
   catch(e){if(!this.completed)this.saveNotice=`Autosave failed: ${String(e)}. Drafts are retained and saving will retry.`;}
   finally{this.autosaving=false;}
 }
 async syncExpiredSubmission(){
   if(this.expiryChecking||this.completed||!this.expired||!this.sessionId||this.step!=='assessment')return;
   this.expiryChecking=true;this.expiryStatus='Time expired. Checking automatic submission with the server...';
   try{const s=await this.request<Session>(`/sessions/${this.sessionId}`);if(s.state==='SUBMITTED')this.apply(s);else this.expiryStatus='The server is finalizing your saved answers. Confirmation will be checked again automatically.';}
   catch{if(!this.completed)this.expiryStatus='Waiting for server confirmation. Saved answers are finalized by the backend; this screen will retry when the connection returns.';}
   finally{this.expiryChecking=false;}
 }
 private tick(){
   if(!this.deadline||this.completed)return;
   const now=Date.now(),wasExpired=this.expired;this.remainingSeconds=Math.max(0,Math.ceil((this.deadline-now-this.offset)/1000));
   if(this.expired){
     if(!wasExpired){this.stashCurrent();this.reviewing=true;this.confirmUnanswered=false;}this.timeWarning='';this.releaseStreams();
     if(this.step==='assessment'&&!this.destroying&&now-this.lastExpiryCheckAt>=2000){this.lastExpiryCheckAt=now;void this.syncExpiredSubmission();}
     return;
   }
   if(this.remainingSeconds<=60&&!this.warnedThresholds.has(60)){this.warnedThresholds.add(300);this.warnedThresholds.add(60);this.timeWarning='1 minute or less remaining. Finish your answer now. Saved answers will be submitted automatically when time runs out.';this.persistProgress();}
   else if(this.remainingSeconds<=300&&!this.warnedThresholds.has(300)){this.warnedThresholds.add(300);this.timeWarning='5 minutes or less remaining. Review unanswered and marked questions. Your saved answers will be submitted automatically at the deadline.';this.persistProgress();}
   if(this.step==='assessment'&&!this.destroying&&now-this.lastAutosaveAt>=5000){this.lastAutosaveAt=now;void this.autosave();}
 }
 private releaseStreams(){const streams=[this.camera,this.microphone,this.screen];this.camera=undefined;this.microphone=undefined;this.screen=undefined;streams.forEach(s=>this.stop(s));}
 private releaseMedia(){this.releaseStreams();clearInterval(this.timer);}
 ngOnDestroy(){this.destroying=true;document.removeEventListener('visibilitychange',this.onVisibilityChange);document.removeEventListener('fullscreenchange',this.onFullscreenChange);window.removeEventListener('blur',this.onWindowBlur);window.removeEventListener('focus',this.onWindowFocus);if(this.focusCaptureTimer)clearTimeout(this.focusCaptureTimer);this.clearEvidencePreview();this.releaseMedia();}
}


