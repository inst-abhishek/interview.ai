import {Component,Input,Output,EventEmitter,OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';import {FormsModule} from '@angular/forms';
export type Template={version:string;title:string;description:string;durationMinutes:number;skills:string[];questionCount:number;status:string;runnerEnabled:boolean;role?:string;updatedAt?:number|null};
type Queue={items:Template[];page:number;size:number;total:number;totalPages:number;allCount:number;pendingCount:number;approvedCount:number;retiredCount:number;enabled:boolean};
@Component({selector:'exl-template-catalogue',standalone:true,imports:[CommonModule,FormsModule],templateUrl:'./template-catalogue.component.html',styleUrls:['./template-catalogue.component.css']})
export class TemplateCatalogueComponent implements OnInit {
 @Input() approvedOnly=false;@Output() chosen=new EventEmitter<Template>();@Input() token='';@Input() role:'recruiter'|'reviewer'='recruiter';
 templates:Template[]=[];activeTemplate?:Template;definition:any;busy=false;error='';notice='';enabled=true;
 search='';status='ALL';sort='UPDATED';page=0;size=10;total=0;pages=1;allCount=0;pendingCount=0;approvedCount=0;retiredCount=0;
 private filters={search:'',status:'ALL',sort:'UPDATED',size:10};
 displayTitle(title:string){return title.replace('Developer ? Mid-level','Developer - Mid-level');}
 statusLabel(status:string){return ({DRAFT:'Draft',IN_REVIEW:'Awaiting review',APPROVED:'Approved',RETIRED:'Retired'} as Record<string,string>)[status]||status;}
 choose(t:Template){if(t.status==='APPROVED')this.chosen.emit(t);}
 ngOnInit(){void this.load();}
 private async api<T>(path:string,body?:unknown):Promise<T>{const r=await fetch('http://127.0.0.1:8080/api'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+this.token,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});if(!r.ok){let message='Request failed ('+r.status+')';try{message=(await r.json()).message||message;}catch{}throw Error(message);}return r.json();}
 async load(){this.busy=true;this.error='';try{const params=new URLSearchParams({page:String(this.page),size:String(this.filters.size),search:this.filters.search,status:this.approvedOnly?'APPROVED':this.filters.status,sort:this.filters.sort});const result=await this.api<Queue>((this.role==='reviewer'?'/review/template-queue':'/recruiter/template-queue')+'?'+params);if(!Array.isArray(result.items))throw Error('Restart the backend to load the template queue update.');this.templates=result.items;this.page=result.page;this.total=result.total;this.pages=result.totalPages;this.allCount=result.allCount;this.pendingCount=result.pendingCount;this.approvedCount=result.approvedCount;this.retiredCount=result.retiredCount;this.enabled=result.enabled;return true;}catch(e){this.error=String(e);return false;}finally{this.busy=false;}}
 async applyFilters(){const previous=this.filters,oldPage=this.page;this.filters={search:this.search.trim(),status:this.status,sort:this.sort,size:this.size};this.page=0;if(!await this.load()){this.filters=previous;this.page=oldPage;}}
 async changePage(page:number){if(this.busy||page<0||page>=this.pages)return;const old=this.page;this.page=page;if(!await this.load())this.page=old;}
 async filterStatus(status:string){this.status=status;await this.applyFilters();}
 async reset(){this.search='';this.status='ALL';this.sort='UPDATED';this.size=10;await this.applyFilters();}
 get first(){return this.total?this.page*this.filters.size+1:0;}
 get last(){return Math.min((this.page+1)*this.filters.size,this.total);}
 async inspect(t:Template){if(this.busy)return;this.error='';this.notice='';if(this.role==='recruiter'){this.activeTemplate=t;this.definition=undefined;return;}this.busy=true;try{this.definition=await this.api('/review/templates/'+t.version);this.activeTemplate=t;}catch(e){this.error=String(e);}finally{this.busy=false;}}
 backToQueue(){this.activeTemplate=undefined;this.definition=undefined;this.error='';}
 async approve(t:Template){if(this.busy)return;this.busy=true;this.error='';try{await this.api('/review/templates/'+t.version+'/approve',{});t.status='APPROVED';t.updatedAt=Date.now();this.notice='Reference tests passed. This version is approved for recruiter use.';await this.load();}catch(e){this.error=String(e);}finally{this.busy=false;}}
}
