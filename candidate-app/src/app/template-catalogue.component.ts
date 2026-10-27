import {Component,Input,Output,EventEmitter,OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';import {FormsModule} from '@angular/forms';
type Template={version:string;title:string;description:string;durationMinutes:number;skills:string[];questionCount:number;status:string;runnerEnabled:boolean};
@Component({selector:'exl-template-catalogue',standalone:true,imports:[CommonModule,FormsModule],templateUrl:'./template-catalogue.component.html',styleUrls:['./template-catalogue.component.css']})
export class TemplateCatalogueComponent implements OnInit {
 @Input() token='';@Input() role:'recruiter'|'reviewer'='recruiter';@Output() created=new EventEmitter<string>();templates:Template[]=[];selected?:Template;definition:any;title='';schedule=new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,16);busy=false;error='';notice='';
 displayTitle(title:string){return title.replace('Developer ? Mid-level','Developer - Mid-level');}
 choose(t:Template){this.selected=t;this.title=this.displayTitle(t.title);}
 ngOnInit(){void this.load();}
 private async api<T>(path:string,body?:unknown):Promise<T>{const r=await fetch('http://127.0.0.1:8080/api'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+this.token,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});if(!r.ok){let message='Request failed ('+r.status+')';try{message=(await r.json()).message||message;}catch{}throw Error(message);}return r.json();}
 async load(){this.busy=true;this.error='';try{this.templates=await this.api(this.role==='reviewer'?'/review/templates':'/recruiter/templates');}catch(e){this.error=String(e);}finally{this.busy=false;}}
 async inspect(t:Template){this.busy=true;this.error='';try{this.definition=await this.api('/review/templates/'+t.version);}catch(e){this.error=String(e);}finally{this.busy=false;}}
 async approve(t:Template){this.busy=true;this.error='';try{await this.api('/review/templates/'+t.version+'/approve',{});this.notice='Reference tests passed. This version is approved for recruiter use.';await this.load();}catch(e){this.error=String(e);}finally{this.busy=false;}}
 async create(){if(!this.selected||this.busy)return;this.busy=true;this.error='';try{const r=await this.api<{id:string}>('/recruiter/templates/'+this.selected.version+'/interviews',{title:this.title,scheduledAt:new Date(this.schedule).getTime()});this.created.emit(r.id);this.selected=undefined;}catch(e){this.error=String(e);}finally{this.busy=false;}}
}
