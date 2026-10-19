export const syncLockModes={
  'sync-redis':{strategy:'REDIS',gate:false,precheck:false,max:10,queueEnabled:false},
  'sync-pessimistic':{strategy:'PESSIMISTIC',gate:false,precheck:false,max:10,queueEnabled:false}
};
export function validateSyncLockMode(mode,state){
  const expected=syncLockModes[mode];
  if(!expected)return;
  for(const [key,value] of Object.entries(expected))
    if(state[key]!==value)throw Error(`${mode}: ${key} expected=${value}, actual=${state[key]}`);
}
