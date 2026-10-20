export const syncLockModes={
  'sync-db_only-precheck':{strategy:'DB_ONLY',gate:false,precheck:true,max:10,queueEnabled:false},
  'sync-redis':{strategy:'REDIS',gate:false,precheck:false,max:10,queueEnabled:false},
  'sync-pessimistic':{strategy:'PESSIMISTIC',gate:false,precheck:false,max:10,queueEnabled:false},
  'sync-redis-precheck':{strategy:'REDIS',gate:false,precheck:true,max:10,queueEnabled:false},
  'sync-pessimistic-precheck':{strategy:'PESSIMISTIC',gate:false,precheck:true,max:10,queueEnabled:false}
};
for(const strategy of ['REDIS','PESSIMISTIC']) {
  syncLockModes[`sync-${strategy.toLowerCase()}-precheck-gate`]={strategy,gate:true,precheck:true,max:10,queueEnabled:false};
}
export function validateSyncLockMode(mode,state){
  const expected=syncLockModes[mode];
  if(!expected)return;
  for(const [key,value] of Object.entries(expected))
    if(state[key]!==value)throw Error(`${mode}: ${key} expected=${value}, actual=${state[key]}`);
}
