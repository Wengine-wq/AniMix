const {test}=require('node:test');const assert=require('node:assert/strict');const {newDb}=require('pg-mem');
const {PgStore}=require('../dist/pg');const {validPlayback,validDeviceId}=require('../dist/social');
test('handoff validates identity, payload and bounds',()=>{
 const good={anime:{id:21,source:'Shikimori'},episode:'1',dubbingKey:'kodik|studio',quality:'720p',positionMs:12000};
 assert.equal(validPlayback(good),true);assert.equal(validPlayback({...good,positionMs:-1}),false);
 assert.equal(validPlayback({...good,anime:{id:21,source:'unknown'}}),false);assert.equal(validDeviceId('../device'),false);
});
test('devices and handoffs are scoped to their owner, target and lifetime',async()=>{
 const db=newDb();const{Pool}=db.adapters.createPg();const store=new PgStore(new Pool());await store.migrate();
 await store.heartbeat('u1','phone','Phone','Android',true,null);await store.heartbeat('u1','pc','PC','Windows',true,null);
 assert.equal((await store.devices('u1')).length,2);assert.equal((await store.devices('u2')).length,0);
 const id='11111111-1111-1111-1111-111111111111';
 await store.handoff('u1','phone','pc',{episode:'1'},id);await store.handoff('u1','phone','pc',{episode:'1'},id);
 assert.equal((await store.pendingHandoffs('u1','pc')).length,1);
 assert.equal(await store.claimHandoff('u2','pc',id),false);assert.equal(await store.claimHandoff('u1','other',id),false);
 assert.equal(await store.claimHandoff('u1','pc',id),true);assert.equal(await store.claimHandoff('u1','pc',id),true);
 await store.heartbeat('u1','phone','Phone','Android',false,null);assert.equal((await store.devices('u1')).find(d=>d.device_id==='phone').active,false);
});
