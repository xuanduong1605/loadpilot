const {test}=require('node:test');
const assert=require('node:assert/strict');
const {validateExecution}=require('./execution.cjs');
const input=source=>({source,targetUrl:'https://fixture.invalid/api',requestTimeoutMs:1000});
const check=source=>validateExecution(input(source));
const script=body=>`import http from 'k6/http'; export default function(){${body}}`;
test('accepts direct bounded requests and local checks without changing or executing source',()=>{
  const source=`import http from 'k6/http'; import {check,sleep} from 'k6'; import {Counter} from 'k6/metrics';
    const count=new Counter('requests');
    export default function(){const r=http.get('https://fixture.invalid/api/items?q=1',{timeout:'0.5s',redirects:0,headers:{Accept:'application/json'},tags:{operation:'read'}});
    check(r,{'status':r=>r.status===200});count.add(1);sleep(0.1);}`;
  assert.equal(check(source).policyAccepted,true);
  assert.equal(check('export default function() {}').policyAccepted,true);
  assert.equal(check('export default function() {while(true){}}').policyAccepted,true); // Parse only; runtime deadlines are independent.
});
test('all supported HTTP signatures require their exact explicit params position',()=>{
  for(const method of ['get','head'])assert.equal(check(script(`http.${method}('https://fixture.invalid/',{timeout:1000,redirects:0});`)).policyAccepted,true);
  for(const method of ['post','put','patch','del','options'])assert.equal(check(script(`http.${method}('https://fixture.invalid/',null,{timeout:'1000ms',redirects:0});`)).policyAccepted,true);
  for(const method of ['GET','HEAD','POST','PUT','PATCH','DELETE','OPTIONS'])assert.equal(check(script(`http.request('${method}','https://fixture.invalid/',null,{timeout:1,redirects:0});`)).policyAccepted,true);
  for(const source of ["http.get('https://fixture.invalid/');","http.get('https://fixture.invalid/',null,{timeout:1,redirects:0});","http.post('https://fixture.invalid/',{timeout:1,redirects:0});","http.request('CONNECT','https://fixture.invalid/',null,{timeout:1,redirects:0});","http.batch([]);","http.asyncRequest('GET','https://fixture.invalid/');"])
    assert.equal(check(script(source)).policyAccepted,false);
});
test('every request site must state finite positive timeout and redirects zero',()=>{
  for(const timeout of ['0','-1','0.5','1e-300','1001','Infinity','NaN',"'1.001s'","'0s'","'0.001ms'","'1m'","'1e3ms'",'Math.min(1,2)','t'])assert.equal(check(script(`const t=1;http.get('https://fixture.invalid/',{timeout:${timeout},redirects:0});`)).policyAccepted,false);
  for(const params of ['{}','{timeout:1000}','{redirects:0}','{timeout:1,redirects:1}','{timeout:1,redirects:0,...{redirects:1}}','{timeout:1,timeout:60000,redirects:0}','{timeout:1,redirects:0,responseCallback:()=>true}'])assert.equal(check(script(`http.get('https://fixture.invalid/',${params});`)).policyAccepted,false);
  assert.equal(check(script("http.get('https://fixture.invalid/',{timeout:1,redirects:0});http.get('https://fixture.invalid/slow',{redirects:0});")).policyAccepted,false);
});
test('literal origin scope blocks credentials, fragments, alternate ports, redirects and dynamic URL coercion',()=>{
  for(const url of ["'http://fixture.invalid/'","'https://other.invalid/'","'https://fixture.invalid:444/'","'https://user:secret@fixture.invalid/'","'https://fixture.invalid/#private'","'//fixture.invalid/'","' https://fixture.invalid/'","'https://fixture.invalid\\\\@other.invalid/'","u","`https://fixture.invalid/${1}`","{toString(){return 'https://fixture.invalid/'}}"])
    assert.equal(check(script(`const u='https://fixture.invalid/';http.get(${url},{timeout:1,redirects:0});`)).policyAccepted,false);
  assert.equal(check(script("http.get('https://fixture.invalid:443/api',{timeout:1,redirects:0});")).policyAccepted,true);
});
test('HTTP module and parameter capabilities cannot escape through aliases or prototype tricks',()=>{
  for(const body of ["const h=http;h.get('https://other.invalid/');","const get=http.get;get('https://other.invalid/');","const {get}=http;","http['get']('https://other.invalid/');","http.get.call(null,'https://other.invalid/');","http.get=()=>{};","const p={timeout:1,redirects:0};http.get('https://fixture.invalid/',p);","Object.assign(http,{});","Reflect.get(http,'get')('https://other.invalid/');","const x=(()=>{}).constructor('return this')();","const k='con'+'structor';const f=(()=>{})[k]('return this');","let h={get: http.get};","const args=['https://other.invalid/'];http.get(...args);","({}).__lookupGetter__('constructor');"])
    assert.equal(check(script(body)).policyAccepted,false);
});
test('lexical scope does not mistake a declaration in another function or block for a safe global',()=>{
  for(const source of ["function a(Date){} export default function(){Date.now();}","function a(Object){} export default function(){Object.getOwnPropertyDescriptor(()=>{},'constructor');}","function a(){let globalThis={};} export default function(){globalThis.fetch('https://other.invalid');}","export default function(){ {let fetch=()=>{};} fetch('https://other.invalid/');}","import http from 'k6/http';function f(http){return http;}export default function(){}"])
    assert.equal(check(source).policyAccepted,false);
  assert.equal(check("function local(x){return x+1;}export default function(){let x=1;{let y=local(x);x=y;}for(let i=0;i<3;i++){x++;}}").policyAccepted,true);
});
test('only explicit helper imports and metric construction are permitted',()=>{
  for(const source of ["import x from 'k6/execution';export default function(){}","import {get} from 'k6/http';export default function(){}","import 'k6/http';export default function(){}","import x from './module.js';export default function(){}","export default function(){import('k6/http');}","export const options={vus:100};export default function(){}","export default async function(){}","export default function*(){}","export default function(){new Date();}","export default function(){console.log('private');}","export default function(){__ENV.TOKEN;}"])
    assert.equal(check(source).policyAccepted,false);
});
test('module initialization cannot perform calls, loops or hidden helper work',()=>{
  for(const source of ["while(true){}export default function(){}","function work(){while(true){}}const r=work();export default function(){}","import http from 'k6/http';const r=http.get('https://fixture.invalid/',{timeout:1,redirects:0});export default function(){}","const r=JSON.parse('{}');export default function(){}"])
    assert.equal(check(source).policyAccepted,false);
  assert.equal(check("const data={text:'UTF-8',items:[1,2,3]};const helper=x=>x+1;export default function(){helper(data.items.length);}").policyAccepted,true);
});
test('routing headers, nonliteral overrides, accessor params and extra fields are refused',()=>{
  for(const params of ["{timeout:1,redirects:0,headers:{Host:'other.invalid'}}","{timeout:1,redirects:0,headers:{':authority':'other.invalid'}}","{timeout:1,redirects:0,headers:{X:'line\\r\\nInjected:1'}}","{timeout:1,redirects:0,headers:h}","{get timeout(){return 1},redirects:0}","{timeout:1,redirects:0,['headers']:{}}","{timeout:1,redirects:0,__proto__:{}}","{timeout:1,redirects:0,tags:{x:__VU}}"])
    assert.equal(check(script(`const h={};http.get('https://fixture.invalid/',${params});`)).policyAccepted,false);
});
test('bounds input, findings and diagnostic privacy',()=>{
  for(const requestTimeoutMs of [0,120001,NaN,Infinity,1.5])assert.equal(validateExecution({...input('export default function(){}'),requestTimeoutMs}).policyAccepted,false);
  assert.equal(check('x'.repeat(65537)).policyAccepted,false);
  const result=check(script("http.get('https://secret-marker.invalid/',{timeout:60000,redirects:3});".repeat(40)));
  assert.ok(result.findings.length<=16);assert.ok(!JSON.stringify(result).includes('secret-marker'));
});
