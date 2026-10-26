// Opt-in, loopback-only acceptance test. Requires the isolated runtime and fixture.
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
if(process.env.PAISMART_ISOLATED_RUNTIME!=='1') throw new Error('Set PAISMART_ISOLATED_RUNTIME=1 for the isolated test runtime');
const base='http://127.0.0.1:8081/api/v1', fixture='http://127.0.0.1:19135/__test/control';
let token;
async function api(path,body){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(10000)});
  assert.equal(response.status,200,path);const value=await response.json();assert.equal(value.code,200,path);return value.data;
}
async function control(mode){return (await fetch(fixture,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode})})).json();}
token=(await api('/users/login',{username:'ragtest',password:'RagTest2026'})).token;
const config=await api('/admin/model-providers');
assert.equal(config.llm.providers.find(p=>p.active).apiBaseUrl.replace(/\/$/,''),'http://127.0.0.1:19135/v1','Refuse external provider');
const cmd=await api('/chat/websocket-token');
const report=[];
for(const [mode,status] of [['normal','COMPLETED'],['cached','COMPLETED'],['partial','INCOMPLETE'],['length','COMPLETED'],['unknown','INCOMPLETE'],['empty','FAILED'],['cancel','CANCELLED'],['reconnect','COMPLETED']]){
  const before=await control(mode), session=await api('/users/conversations',{});
  const socket=new WebSocket(`ws://127.0.0.1:8081/chat/${token}`);
  const events=[];
  const terminal=await new Promise((resolve,reject)=>{
    const timeout=setTimeout(()=>{socket.close();reject(new Error(`Terminal timeout: ${mode}`));},45000);
    socket.addEventListener('error',()=>{clearTimeout(timeout);reject(new Error('WebSocket connection failed'));});
    socket.addEventListener('open',()=>socket.send(`可靠性自动验收 ${mode}：支付服务和库存服务的依赖关系及来源`));
    socket.addEventListener('message',event=>{
      let value;try{value=JSON.parse(event.data);}catch{return;}
      events.push(value);
      if(mode==='cancel' && value.type==='chunk') socket.send(JSON.stringify({type:'stop',generationId:value.generationId,_internal_cmd_token:cmd.cmdToken}));
      if(mode==='reconnect' && value.type==='chunk') {
        socket.close();
        (async()=>{
          for(let i=0;i<40;i++) {
            const snapshot=await api(`/chat/generation/${value.generationId}`);
            if(snapshot.status==='COMPLETED'){clearTimeout(timeout);resolve(snapshot);return;}
            assert.notEqual(snapshot.status,'CANCELLED','Disconnect is not cancellation');
            await new Promise(r=>setTimeout(r,200));
          }
          throw new Error('Disconnected generation failed to finish');
        })().catch(reject);
      }
      if(value.type==='completion'){clearTimeout(timeout);resolve(value);}
    });
  });
  socket.close();
  assert.equal(terminal.status,status,mode);
  assert.ok(terminal.token);assert.equal(terminal.persistenceStatus,'SAVED');
  const snapshot=await api(`/chat/generation/${terminal.generationId}`);
  const history=await api(`/users/conversation?conversationId=${terminal.conversationId}`);
  const answers=history.filter(m=>m.generationId===terminal.generationId);
  assert.equal(answers.length,1);assert.equal(answers[0].content,terminal.content);assert.equal(answers[0].status,status);
  assert.deepEqual(answers[0].referenceMappings||{},terminal.referenceMappings||{});
  assert.equal(snapshot.content,terminal.content);assert.equal(snapshot.status,status);
  const chunks=events.filter(e=>e.type==='chunk');
  for(let i=1;i<chunks.length;i++) assert.ok(chunks[i].sequence>chunks[i-1].sequence);
  assert.ok(terminal.lastSequence>=(chunks.at(-1)?.sequence||0));
  if(mode==='length') assert.ok(terminal.content.endsWith('续写结束。'));
  const after=await control(mode);
  assert.equal(after.active,0,'All model connections must close');
  if(mode==='empty') assert.equal(after.requests-before.requests,3,'Initial request plus two shared recovery admissions');
  report.push({mode,status,generationId:terminal.generationId,conversationId:session.conversationId,httpRequests:after.requests-before.requests,streams:after.streams-before.streams,bodyLength:terminal.content.length,references:Object.keys(terminal.referenceMappings||{}).length,sequence:terminal.lastSequence});
}
await control('normal');
await writeFile('target/llm-runtime-verification.json',JSON.stringify(report,null,2));
console.log(JSON.stringify(report,null,2));
